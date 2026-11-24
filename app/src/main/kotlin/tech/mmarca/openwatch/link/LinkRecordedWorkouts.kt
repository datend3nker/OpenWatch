package tech.mmarca.openwatch.link

import android.util.Log
import dagger.Lazy
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tech.mmarca.openlink.OpenLinkDevice
import tech.mmarca.openlink.OpenLinkIntegration
import tech.mmarca.openlink.OpenLinkRecordingPhase
import tech.mmarca.openlink.OpenLinkRecordingRequest
import tech.mmarca.openlink.OpenLinkRecordingState
import tech.mmarca.openlink.OpenLinkWorkoutPlan
import tech.mmarca.openlink.host.OpenLinkHost
import tech.mmarca.openwatch.data.repository.BleDeviceRepository
import tech.mmarca.openwatch.devices.wearos.WearOsCompanionManager
import tech.mmarca.openwatch.devices.wearos.WearOsLinkFailure
import tech.mmarca.openwatch.devices.wearos.WearOsWorkoutLink
import tech.mmarca.openwatch.features.watches.DeviceSyncController
import tech.mmarca.openwatch.wearlink.WearPlan
import tech.mmarca.openwatch.wearlink.WearWorkoutPhase
import tech.mmarca.openwatch.wearlink.WorkoutSport

/**
 * The `RECORD` sessions (version 2) of other apps, run on a Wear OS watch: the watch app starts the workout itself,
 * records it and keeps it; the next sync brings it in and writes it to Health Connect, with the exercise segments of
 * the client's last plan. Whoever records, writes.
 *
 * One connection to the watch per workout does all of it: the client's pause, resume and stop, its plan (version 4),
 * and, every [POLL_MILLIS], what the watch says of the workout, so a pause, resume or stop made on the watch reaches
 * the client the same way. The connection is renewed before the watch's limit and after a drop. Only the sports
 * without a route start: the watch records no GPS for a workout the phone started.
 */
@Singleton
class LinkRecordedWorkouts @Inject constructor(
    private val devices: LinkDevices,
    private val deviceRepository: BleDeviceRepository,
    private val wearOs: WearOsCompanionManager,
    private val syncController: DeviceSyncController,
    private val workoutPlans: LinkWorkoutPlans,
    private val host: Lazy<OpenLinkHost>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = mutableMapOf<String, Workout>()

    private sealed interface Command {
        data object Pause : Command
        data object Resume : Command
        data object Stop : Command

        /** A new plan is wanted on the watch, or the client's own wake-up of the loop. */
        data object Wake : Command
    }

    /** The plan to send next, or none: the owner lost the capability. */
    private class PlanUpdate(val plan: WearPlan?)

    private class Workout(val sessionId: String, val device: OpenLinkDevice, val sportId: String, val startedAtMillis: Long) {
        val commands = LinkedBlockingQueue<Command>()
        val wantedPlan = AtomicReference<PlanUpdate?>(null)

        /** The newest plan the client sent: what the workout's segments are written from. */
        @Volatile
        var lastPlan: WearPlan? = null

        @Volatile
        var stopping = false

        // The loop's own state, one coroutine.
        var started = false
        var reported = OpenLinkRecordingPhase.STARTING
        var endPhase = OpenLinkRecordingPhase.STOPPED
        var endReason: String? = null
    }

    private enum class Served { ENDED, TIME_UP }

    /** Starts the workout of [request] on its watch, or reports why not. */
    @Synchronized
    fun start(request: OpenLinkRecordingRequest) {
        val sessionId = request.sessionId
        if (sessionId in running) return
        fun fail(reason: String) = publish(sessionId, request.deviceId.orEmpty(), OpenLinkRecordingPhase.FAILED, reason)
        val device = devices.devices.value.firstOrNull { it.id == request.deviceId } ?: return fail(OpenLinkRecordingState.REASON_NO_DEVICE)
        val sport = WorkoutSport.fromExerciseType(request.exerciseType)
        when {
            device.integration != OpenLinkIntegration.WEAROS || sport == null -> return fail(OpenLinkRecordingState.REASON_UNSUPPORTED)
            sport.gps -> return fail(OpenLinkRecordingState.REASON_NO_GPS)
            running.values.any { it.device.id == device.id } -> return fail(OpenLinkRecordingState.REASON_BUSY)
        }
        val workout = Workout(sessionId, device, sport.id, System.currentTimeMillis())
        running[sessionId] = workout
        scope.launch { run(workout) }
    }

    /** The owner's stop. False for a session that is not a recorded one here. */
    @Synchronized
    fun stop(sessionId: String): Boolean {
        val workout = running[sessionId] ?: return false
        workout.stopping = true
        workout.commands.add(Command.Stop)
        return true
    }

    @Synchronized
    fun pause(sessionId: String) {
        running[sessionId]?.commands?.add(Command.Pause)
    }

    @Synchronized
    fun resume(sessionId: String) {
        running[sessionId]?.commands?.add(Command.Resume)
    }

    /** The client's whole plan for its recorded session, or none ([plan] null); dropped for a session that is not recorded here. */
    @Synchronized
    fun setPlan(sessionId: String, plan: OpenLinkWorkoutPlan?) {
        val workout = running[sessionId] ?: return
        val wear = plan?.toWearPlan()
        if (wear != null) workout.lastPlan = wear
        workout.wantedPlan.set(PlanUpdate(wear))
        workout.commands.add(Command.Wake)
    }

    private suspend fun run(workout: Workout) {
        publish(workout, OpenLinkRecordingPhase.STARTING)
        var failures = 0
        while (true) {
            val registered = deviceRepository.devices.firstOrNull { it.id == workout.device.id }
            val served = try {
                wearOs.withWorkoutLink(registered?.address, registered?.bluetoothName ?: registered?.displayName) { link -> serve(link, workout) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: WearOsLinkFailure) {
                Log.w(TAG, "the watch could not be reached for a recorded session", error)
                null
            } catch (error: SecurityException) {
                Log.w(TAG, "no Bluetooth grant for a recorded session", error)
                null
            }
            when (served) {
                Served.ENDED -> break
                Served.TIME_UP -> failures = 0
                null -> {
                    failures++
                    val giveUp = (!workout.started && failures >= START_ATTEMPTS) || (workout.stopping && failures >= STOP_ATTEMPTS)
                    if (giveUp) {
                        workout.endPhase = OpenLinkRecordingPhase.FAILED
                        workout.endReason = OpenLinkRecordingState.REASON_NOT_CONNECTED
                        break
                    }
                    delay(RETRY_MILLIS)
                }
            }
        }
        finish(workout)
    }

    /** Runs on the connection until it ends, the workout does, or [LINK_MILLIS] pass. Blocking. */
    private fun serve(link: WearOsWorkoutLink, workout: Workout): Served {
        val id = workout.sessionId
        if (!workout.started) {
            try {
                link.start(id, workout.sportId)
            } catch (_: WearOsLinkFailure.Refused) {
                // The watch has a workout already, or does not know the sport.
                workout.endPhase = OpenLinkRecordingPhase.FAILED
                workout.endReason = OpenLinkRecordingState.REASON_BUSY
                return Served.ENDED
            }
            workout.started = true
            workout.reported = OpenLinkRecordingPhase.RECORDING
            publish(workout, OpenLinkRecordingPhase.RECORDING)
            Log.i(TAG, "recorded session started on the watch")
        }
        val until = System.currentTimeMillis() + LINK_MILLIS
        while (System.currentTimeMillis() < until) {
            sendPlan(link, workout)
            when (workout.commands.poll(POLL_MILLIS, TimeUnit.MILLISECONDS)) {
                Command.Stop -> {
                    try {
                        link.stop(id)
                    } catch (_: WearOsLinkFailure.Refused) {
                        // Nothing runs there any more: the wearer ended it.
                        workout.endReason = OpenLinkRecordingState.REASON_STOPPED_ON_DEVICE
                    }
                    workout.endReason = workout.endReason ?: OpenLinkRecordingState.REASON_STOPPED_BY_CLIENT
                    return Served.ENDED
                }
                Command.Pause -> {
                    runCatchingRefused { link.pause(id) }
                    if (follow(link, workout)) return Served.ENDED
                }
                Command.Resume -> {
                    runCatchingRefused { link.resume(id) }
                    if (follow(link, workout)) return Served.ENDED
                }
                Command.Wake -> Unit
                null -> if (follow(link, workout)) return Served.ENDED
            }
        }
        return Served.TIME_UP
    }

    /** Reads the watch's state and reports a change of it; true when the workout is over there. */
    private fun follow(link: WearOsWorkoutLink, workout: Workout): Boolean {
        val phase = when (link.phase(workout.sessionId)) {
            WearWorkoutPhase.RUNNING -> OpenLinkRecordingPhase.RECORDING
            WearWorkoutPhase.PAUSED -> OpenLinkRecordingPhase.PAUSED
            WearWorkoutPhase.NONE -> {
                // Ended on the watch, unless the client's stop is on its way.
                if (!workout.stopping) workout.endReason = OpenLinkRecordingState.REASON_STOPPED_ON_DEVICE
                return !workout.stopping
            }
        }
        if (phase != workout.reported) {
            workout.reported = phase
            publish(workout, phase)
        }
        return false
    }

    /** Sends the plan the client wants shown, once; a failure leaves it wanted for the next connection. */
    private fun sendPlan(link: WearOsWorkoutLink, workout: Workout) {
        val update = workout.wantedPlan.get() ?: return
        runCatchingRefused { update.plan?.let(link::pushPlan) ?: link.clearPlan(workout.sessionId) }
        workout.wantedPlan.compareAndSet(update, null)
    }

    /** A watch with no workout for the session answers `bad_request`: nothing left to do, not a lost link. */
    private inline fun runCatchingRefused(call: () -> Unit) {
        try {
            call()
        } catch (_: WearOsLinkFailure.Refused) {
            Log.i(TAG, "the watch refused a command: no workout runs there for the session")
        }
    }

    /** The end, reported once: the state, and for a workout that ran, the plan kept for its segments and the sync that brings it in. */
    private fun finish(workout: Workout) {
        synchronized(this) { running.remove(workout.sessionId) }
        Log.i(TAG, "recorded session over: ${workout.endPhase}")
        publish(workout, workout.endPhase, workout.endReason)
        if (workout.endPhase == OpenLinkRecordingPhase.STOPPED) {
            workout.lastPlan?.let { workoutPlans.remember(workout.device.id, workout.startedAtMillis, it) }
            syncController.syncDevice(workout.device.id)
        }
    }

    private fun publish(workout: Workout, phase: OpenLinkRecordingPhase, reason: String? = null) =
        publish(workout.sessionId, workout.device.id, phase, reason)

    private fun publish(sessionId: String, deviceId: String, phase: OpenLinkRecordingPhase, reason: String? = null) {
        runCatching {
            host.get().publishRecordingState(OpenLinkRecordingState(sessionId, deviceId, phase, reason, System.currentTimeMillis()))
        }.onFailure { Log.w(TAG, "publishing a session state failed", it) }
    }

    private companion object {
        const val TAG = "LinkRecordedWorkouts"

        /** How often the watch is asked what it holds of the workout; a pause on it reaches the client this soon. */
        const val POLL_MILLIS = 2_000L

        /** One connection's length, inside the connection's own limit ([BluetoothWearOsNodePort]) and the watch's. */
        const val LINK_MILLIS = 6L * 60 * 1000

        const val RETRY_MILLIS = 4_000L
        const val START_ATTEMPTS = 2
        const val STOP_ATTEMPTS = 3
    }
}
