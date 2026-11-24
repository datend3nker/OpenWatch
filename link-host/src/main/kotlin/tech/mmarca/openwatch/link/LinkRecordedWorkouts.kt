package tech.mmarca.openwatch.link

import dagger.Lazy
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tech.mmarca.openlink.OpenLinkRecordingPhase
import tech.mmarca.openlink.OpenLinkRecordingRequest
import tech.mmarca.openlink.OpenLinkRecordingState
import tech.mmarca.openlink.OpenLinkWorkoutSummary
import tech.mmarca.openlink.host.OpenLinkHost
import tech.mmarca.openwatch.core.performance.ApplicationScope
import tech.mmarca.openwatch.data.repository.BleDeviceRepository
import tech.mmarca.openwatch.devices.core.RecordedWorkoutCommand
import tech.mmarca.openwatch.devices.core.RecordedWorkoutPhase
import tech.mmarca.openwatch.devices.core.RecordedWorkoutPort
import tech.mmarca.openwatch.devices.core.RecordedWorkoutState
import tech.mmarca.openwatch.devices.core.RecordedWorkoutSummary
import tech.mmarca.openwatch.devices.core.sync.DeviceSyncController
import tech.mmarca.openwatch.domain.model.BleSensorDevice

/**
 * Workouts a device records for another app (OpenLink's `RECORD` mode), through the family that
 * claims the device ([RecordedWorkoutPort]: today a Wear OS watch's own workout), started, paused,
 * resumed and stopped from here. The watch never speaks first, so while one runs the phone watches
 * its state on the link, one ask after the other ([poll]), each a background request that lets a
 * sync go first: a pause or stop on the wrist reaches the app as it happens. Once it ends, a sync of the device writes it to Health
 * Connect, as for a workout started on the watch. A device no port claims cannot be told to record
 * one: `FAILED`, `unsupported`.
 *
 * Every report names its owner, as the OpenLink host library asks; the library holds the rules
 * that need no device (owner, repeats, order), so every command is handed on here once. A start
 * for a session another app holds here is refused as `busy`.
 */
@Singleton
class LinkRecordedWorkouts internal constructor(
    private val watch: Watch,
    private val publish: (OpenLinkRecordingState, String) -> Unit,
    private val syncAfter: (deviceId: String) -> Unit,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pollMillis: Long = POLL_MILLIS,
) {
    @Inject
    constructor(
        deviceRepository: BleDeviceRepository,
        ports: Set<@JvmSuppressWildcards RecordedWorkoutPort>,
        syncController: DeviceSyncController,
        host: Lazy<OpenLinkHost>,
        @ApplicationScope appScope: CoroutineScope,
    ) : this(
        watch = object : Watch {
            override fun device(deviceId: String): BleSensorDevice? = deviceRepository.devices.firstOrNull { it.id == deviceId }
            override fun canRecord(device: BleSensorDevice): Boolean = ports.any { it.canRecord(device) }
            override suspend fun control(
                device: BleSensorDevice,
                command: RecordedWorkoutCommand,
                sessionId: String,
                exerciseType: Int,
                background: Boolean,
                known: RecordedWorkoutPhase?,
            ): RecordedWorkoutState? = ports.firstOrNull { it.canRecord(device) }?.control(device, command, sessionId, exerciseType, background, known)
        },
        // A client that went away while it was told cannot be told otherwise.
        publish = { state, owner -> runCatching { host.get().publishRecordingState(state, owner) } },
        syncAfter = { deviceId -> syncController.syncDevice(deviceId) },
        scope = appScope,
    )

    /** The devices, for the tests. */
    internal interface Watch {
        fun device(deviceId: String): BleSensorDevice?

        fun canRecord(device: BleSensorDevice): Boolean

        /** Null: the device could not be reached. */
        suspend fun control(
            device: BleSensorDevice,
            command: RecordedWorkoutCommand,
            sessionId: String,
            exerciseType: Int,
            background: Boolean,
            known: RecordedWorkoutPhase? = null,
        ): RecordedWorkoutState?
    }

    private class Workout(val owner: String, val device: BleSensorDevice, val exerciseType: Int) {
        val lock = Mutex()
        var poll: Job? = null
        var last: RecordedWorkoutPhase? = null
    }

    private val workouts = mutableMapOf<String, Workout>()

    private val _activity = MutableStateFlow<Map<String, LinkSessionActivity>>(emptyMap())

    /** Each recorded workout's watch, by session id, for the notification. */
    val activity: StateFlow<Map<String, LinkSessionActivity>> = _activity.asStateFlow()

    /** Whether [sessionId] is a workout recorded here. */
    @Synchronized
    fun holds(sessionId: String): Boolean = sessionId in workouts

    @Synchronized
    fun start(request: OpenLinkRecordingRequest, owner: String) {
        val sessionId = request.sessionId
        val deviceId = request.deviceId.orEmpty()
        val device = watch.device(deviceId)
        if (device == null) return publish(failed(sessionId, deviceId, OpenLinkRecordingState.REASON_NO_DEVICE), owner)
        if (!watch.canRecord(device)) return publish(failed(sessionId, deviceId, OpenLinkRecordingState.REASON_UNSUPPORTED), owner)
        val held = workouts[sessionId]
        // Another app's session is not taken over; the same app starting again is a repeat.
        if (held != null && held.owner != owner) return publish(failed(sessionId, deviceId, OpenLinkRecordingState.REASON_BUSY), owner)
        val workout = held ?: Workout(owner, device, request.exerciseType).also { workouts[sessionId] = it }
        _activity.update { it + (sessionId to LinkSessionActivity(sessionId, listOf(LinkSessionDevice(device.id, device.displayName, streaming = true)), recorded = true)) }
        command(sessionId, workout, RecordedWorkoutCommand.START)
    }

    fun pause(sessionId: String) = handOn(sessionId, RecordedWorkoutCommand.PAUSE)

    fun resume(sessionId: String) = handOn(sessionId, RecordedWorkoutCommand.RESUME)

    fun stop(sessionId: String) = handOn(sessionId, RecordedWorkoutCommand.STOP)

    private fun handOn(sessionId: String, command: RecordedWorkoutCommand) {
        val workout = synchronized(this) { workouts[sessionId] } ?: return
        command(sessionId, workout, command)
    }

    private fun command(sessionId: String, workout: Workout, command: RecordedWorkoutCommand) {
        scope.launch {
            workout.lock.withLock {
                val state = ask(sessionId, workout, command)
                report(sessionId, workout, state, asked = command)
            }
        }
    }

    /** The device's answer; null when it could not be reached. A poll of the state lets every other request go first. */
    private suspend fun ask(
        sessionId: String,
        workout: Workout,
        command: RecordedWorkoutCommand,
        known: RecordedWorkoutPhase? = null,
    ): RecordedWorkoutState? = watch.control(
        workout.device, command, sessionId, workout.exerciseType,
        background = command == RecordedWorkoutCommand.STATE || command == RecordedWorkoutCommand.WATCH,
        known = known,
    )

    /** Tells the owner what the watch said; a workout that is over is let go and synced. */
    private fun report(sessionId: String, workout: Workout, state: RecordedWorkoutState?, asked: RecordedWorkoutCommand?) {
        val deviceId = workout.device.id
        if (state == null) {
            // Out of reach. A start or a stop must be answered; a pause, a resume or a poll waits for the next ask.
            when (asked) {
                RecordedWorkoutCommand.START, RecordedWorkoutCommand.STOP -> end(sessionId, workout, failed(sessionId, deviceId, OpenLinkRecordingState.REASON_NOT_CONNECTED))
                else -> if (asked != null && workout.last != null) {
                    publish(OpenLinkRecordingState(sessionId, deviceId, phaseOf(workout.last!!), null, clock()), workout.owner)
                }
            }
            return
        }
        val phase = state.phase
        when (phase) {
            RecordedWorkoutPhase.RECORDING, RecordedWorkoutPhase.PAUSED -> {
                if (phase != workout.last || asked != null) {
                    publish(OpenLinkRecordingState(sessionId, deviceId, phaseOf(phase), null, clock()), workout.owner)
                }
                workout.last = phase
                if (workout.poll == null) workout.poll = scope.launch { poll(sessionId, workout) }
            }
            RecordedWorkoutPhase.STOPPED -> {
                val reason = state.reason ?: OpenLinkRecordingState.REASON_STOPPED_ON_DEVICE
                val summary = state.summary?.let(::linkSummary)
                end(sessionId, workout, OpenLinkRecordingState(sessionId, deviceId, OpenLinkRecordingPhase.STOPPED, reason, clock(), summary))
                // A recovery is worked out two minutes after the stop: once it may be there, the end goes again, fuller.
                if (summary != null && summary.heartRateRecovery1MinBpm == null) {
                    scope.launch {
                        delay(RECOVERY_WAIT_MILLIS)
                        val fuller = ask(sessionId, workout, RecordedWorkoutCommand.STATE)?.summary?.let(::linkSummary)
                        if (fuller != null && fuller != summary) {
                            publish(OpenLinkRecordingState(sessionId, deviceId, OpenLinkRecordingPhase.STOPPED, reason, clock(), fuller), workout.owner)
                        }
                    }
                }
                // The watch keeps the workout a moment after the stop; the sync writes it to Health Connect.
                scope.launch {
                    delay(SYNC_DELAY_MILLIS)
                    syncAfter(deviceId)
                }
            }
            RecordedWorkoutPhase.FAILED -> end(sessionId, workout, failed(sessionId, deviceId, state.reason ?: OpenLinkRecordingState.REASON_UNSUPPORTED))
            // The watch holds none for it: its app was reset, or the workout never began.
            RecordedWorkoutPhase.NONE -> end(sessionId, workout, OpenLinkRecordingState(sessionId, deviceId, OpenLinkRecordingPhase.STOPPED, OpenLinkRecordingState.REASON_HOST_STOPPED, clock()))
        }
    }

    /**
     * Watches the state on the device's link, one ask after the other: the device answers once the
     * phase differs from the one last told, or after its few seconds, so a press on the wrist reaches
     * the app as it happens and the link stays up while the workout runs. Not under the lock, so an
     * app's pause or stop is not held behind it; out of reach, it waits [pollMillis] before asking again.
     */
    private suspend fun poll(sessionId: String, workout: Workout) {
        while (true) {
            if (synchronized(this) { workouts[sessionId] } !== workout) return
            val askedAt = clock()
            val state = ask(sessionId, workout, RecordedWorkoutCommand.WATCH, known = workout.last)
            workout.lock.withLock {
                if (synchronized(this) { workouts[sessionId] } !== workout) return
                report(sessionId, workout, state, asked = null)
            }
            when {
                state == null -> delay(pollMillis)
                // A device that answers at once without a change would make this a busy loop.
                clock() - askedAt < MIN_WATCH_MILLIS -> delay(MIN_WATCH_MILLIS)
            }
        }
    }

    private fun end(sessionId: String, workout: Workout, state: OpenLinkRecordingState) {
        synchronized(this) {
            if (workouts[sessionId] === workout) workouts.remove(sessionId)
        }
        _activity.update { it - sessionId }
        // From inside the poll too: it ends at its next wait.
        workout.poll?.cancel()
        workout.poll = null
        publish(state, workout.owner)
    }

    private fun linkSummary(summary: RecordedWorkoutSummary) = OpenLinkWorkoutSummary(
        zoneSeconds = summary.zoneSeconds,
        zoneLowerBoundsBpm = summary.zoneLowerBoundsBpm,
        trimp = summary.trimp,
        heartRateRecovery1MinBpm = summary.recovery1MinBpm,
        heartRateRecovery2MinBpm = summary.recovery2MinBpm,
    )

    private fun failed(sessionId: String, deviceId: String, reason: String) =
        OpenLinkRecordingState(sessionId, deviceId, OpenLinkRecordingPhase.FAILED, reason, clock())

    private fun phaseOf(phase: RecordedWorkoutPhase): OpenLinkRecordingPhase =
        if (phase == RecordedWorkoutPhase.PAUSED) OpenLinkRecordingPhase.PAUSED else OpenLinkRecordingPhase.RECORDING

    private companion object {
        /** How long a device out of reach is left before the next ask. */
        const val POLL_MILLIS = 60_000L

        /** The shortest time between two watches of the state. */
        const val MIN_WATCH_MILLIS = 1_000L

        const val SYNC_DELAY_MILLIS = 5_000L

        /** The recovery's two minutes after the stop, and a margin for working its falls out. */
        const val RECOVERY_WAIT_MILLIS = 150_000L
    }
}
