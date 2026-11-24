package tech.mmarca.openwatch.link

import android.util.Log
import dagger.Lazy
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
import tech.mmarca.openlink.host.OpenLinkHost
import tech.mmarca.openwatch.data.repository.BleDeviceRepository
import tech.mmarca.openwatch.devices.wearos.WearOsCompanionManager
import tech.mmarca.openwatch.devices.wearos.WearOsLinkFailure
import tech.mmarca.openwatch.domain.model.BleSensorDevice
import tech.mmarca.openwatch.features.watches.DeviceSyncController
import tech.mmarca.openwatch.wearlink.WearLinkWorkoutControl
import tech.mmarca.openwatch.wearlink.WearLinkWorkoutControl.Command
import tech.mmarca.openwatch.wearlink.WearLinkWorkoutControl.Phase

/**
 * Workouts a Wear OS watch records for another app (OpenLink's `RECORD` mode): the watch's own
 * workout, started, paused, resumed and stopped from here ([WearLinkWorkoutControl]). The watch
 * never speaks first, so while one runs its state is asked every [POLL_MILLIS]: a pause or stop on
 * the wrist reaches the app that way. Once it ends, a sync of the watch writes it to Health Connect,
 * as for a workout started on the watch. A Garmin cannot be told to record one.
 *
 * Every report names its owner, as the OpenLink host library asks; the library holds the rules
 * that need no device (owner, repeats, order), so every command is handed on here once.
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
        wearOs: WearOsCompanionManager,
        syncController: DeviceSyncController,
        host: Lazy<OpenLinkHost>,
    ) : this(
        watch = object : Watch {
            override fun device(deviceId: String): BleSensorDevice? = deviceRepository.devices.firstOrNull { it.id == deviceId }
            override suspend fun control(device: BleSensorDevice, control: WearLinkWorkoutControl.Control) =
                wearOs.controlWorkout(device.address, device.bluetoothName, control)
        },
        publish = { state, owner ->
            runCatching { host.get().publishRecordingState(state, owner) }.onFailure { Log.w(TAG, "publishing a workout state failed", it) }
        },
        syncAfter = { deviceId -> syncController.syncDevice(deviceId) },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

    /** The watch, for the tests. */
    internal interface Watch {
        fun device(deviceId: String): BleSensorDevice?

        /** Null: no bonded watch matches. Throws [WearOsLinkFailure] when the link fails. */
        suspend fun control(device: BleSensorDevice, control: WearLinkWorkoutControl.Control): WearLinkWorkoutControl.State?
    }

    private class Workout(val owner: String, val device: BleSensorDevice, val exerciseType: Int) {
        val lock = Mutex()
        var poll: Job? = null
        var last: Phase? = null
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
        if (!device.isWearosWatch) return publish(failed(sessionId, deviceId, OpenLinkRecordingState.REASON_UNSUPPORTED), owner)
        val workout = workouts[sessionId]?.takeIf { it.owner == owner } ?: Workout(owner, device, request.exerciseType).also { workouts[sessionId] = it }
        _activity.update { it + (sessionId to LinkSessionActivity(sessionId, listOf(LinkSessionDevice(device.id, device.displayName, streaming = true)), recorded = true)) }
        command(sessionId, workout, Command.START)
    }

    fun pause(sessionId: String) = handOn(sessionId, Command.PAUSE)

    fun resume(sessionId: String) = handOn(sessionId, Command.RESUME)

    fun stop(sessionId: String) = handOn(sessionId, Command.STOP)

    private fun handOn(sessionId: String, command: Command) {
        val workout = synchronized(this) { workouts[sessionId] } ?: return
        command(sessionId, workout, command)
    }

    private fun command(sessionId: String, workout: Workout, command: Command) {
        scope.launch {
            workout.lock.withLock {
                val state = ask(sessionId, workout, command)
                report(sessionId, workout, state, asked = command)
            }
        }
    }

    /** The watch's answer; null when it could not be reached. */
    private suspend fun ask(sessionId: String, workout: Workout, command: Command): WearLinkWorkoutControl.State? =
        try {
            watch.control(workout.device, WearLinkWorkoutControl.Control(command, sessionId, workout.exerciseType))
        } catch (error: CancellationException) {
            throw error
        } catch (_: WearOsLinkFailure) {
            null
        } catch (_: SecurityException) {
            null
        }

    /** Tells the owner what the watch said; a workout that is over is let go and synced. */
    private fun report(sessionId: String, workout: Workout, state: WearLinkWorkoutControl.State?, asked: Command?) {
        val deviceId = workout.device.id
        if (state == null) {
            // Out of reach. A start or a stop must be answered; a pause, a resume or a poll waits for the next ask.
            when (asked) {
                Command.START, Command.STOP -> end(sessionId, workout, failed(sessionId, deviceId, OpenLinkRecordingState.REASON_NOT_CONNECTED))
                else -> if (asked != null && workout.last != null) {
                    publish(OpenLinkRecordingState(sessionId, deviceId, phaseOf(workout.last!!), null, clock()), workout.owner)
                }
            }
            return
        }
        val phase = state.phase
        when (phase) {
            Phase.RECORDING, Phase.PAUSED -> {
                if (phase != workout.last || asked != null) {
                    publish(OpenLinkRecordingState(sessionId, deviceId, phaseOf(phase), null, clock()), workout.owner)
                }
                workout.last = phase
                if (workout.poll == null) workout.poll = scope.launch { poll(sessionId, workout) }
            }
            Phase.STOPPED -> {
                val reason = state.reason ?: OpenLinkRecordingState.REASON_STOPPED_ON_DEVICE
                end(sessionId, workout, OpenLinkRecordingState(sessionId, deviceId, OpenLinkRecordingPhase.STOPPED, reason, clock()))
                // The watch keeps the workout a moment after the stop; the sync writes it to Health Connect.
                scope.launch {
                    delay(SYNC_DELAY_MILLIS)
                    syncAfter(deviceId)
                }
            }
            Phase.FAILED -> end(sessionId, workout, failed(sessionId, deviceId, state.reason ?: OpenLinkRecordingState.REASON_UNSUPPORTED))
            // The watch holds none for it: its app was reset, or the workout never began.
            Phase.NONE -> end(sessionId, workout, OpenLinkRecordingState(sessionId, deviceId, OpenLinkRecordingPhase.STOPPED, OpenLinkRecordingState.REASON_HOST_STOPPED, clock()))
        }
    }

    private suspend fun poll(sessionId: String, workout: Workout) {
        while (true) {
            delay(pollMillis)
            workout.lock.withLock {
                if (synchronized(this) { workouts[sessionId] } !== workout) return
                report(sessionId, workout, ask(sessionId, workout, Command.STATE), asked = null)
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

    private fun failed(sessionId: String, deviceId: String, reason: String) =
        OpenLinkRecordingState(sessionId, deviceId, OpenLinkRecordingPhase.FAILED, reason, clock())

    private fun phaseOf(phase: Phase): OpenLinkRecordingPhase =
        if (phase == Phase.PAUSED) OpenLinkRecordingPhase.PAUSED else OpenLinkRecordingPhase.RECORDING

    private companion object {
        const val TAG = "LinkRecordedWorkouts"

        /** How soon a pause or stop on the wrist reaches the app; each ask is one short link. */
        const val POLL_MILLIS = 20_000L

        const val SYNC_DELAY_MILLIS = 5_000L
    }
}
