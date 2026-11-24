package tech.mmarca.openwatch.wear.workout

import java.util.Base64
import kotlinx.coroutines.flow.StateFlow
import tech.mmarca.openwatch.wear.MetricStore
import tech.mmarca.openwatch.wearlink.WearLinkWorkoutControl
import tech.mmarca.openwatch.wearlink.WearLinkWorkoutControl.Command
import tech.mmarca.openwatch.wearlink.WearLinkWorkoutControl.Phase
import tech.mmarca.openwatch.wearlink.WearLinkWorkoutControl.State
import tech.mmarca.openwatch.wearlink.WorkoutSport

/**
 * The workout the phone has the watch record for an app ([WearLinkWorkoutControl]): which session
 * it belongs to, and its phase as the workout's own state shows it. The workout is the ordinary one,
 * so the watch's buttons pause and stop it too; the phone learns of that when it asks for the state.
 * The session is kept in the watch's store ([Stored]), so a restarted service still answers for it.
 */
internal class RemoteWorkout(
    private val session: Store,
    private val controls: Controls,
    private val workout: StateFlow<WorkoutUiState>,
    /** A stopped workout's summary, by the start its rows are kept under; read off the main thread. */
    private val summaryOf: (workoutStartMillis: Long) -> WearLinkWorkoutControl.Summary? = { null },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** What starts and steers the watch's workout; each hands its command to the service and returns. */
    interface Controls {
        fun start(sport: WorkoutSport)
        fun pause()
        fun resume()
        fun stop()
    }

    /**
     * The session of the workout the phone started, with the phase last told and since when, and
     * the start of its workout once the workout shows it ([workoutStartMillis]), by which its summary is read.
     */
    data class Held(
        val sessionId: String,
        val phase: Phase,
        val sinceMillis: Long,
        val reason: String? = null,
        val workoutStartMillis: Long? = null,
    )

    interface Store {
        fun read(): Held?
        fun write(held: Held?)
    }

    @Synchronized
    fun control(control: WearLinkWorkoutControl.Control): State {
        val held = session.read()?.let(::observe)
        val mine = held?.takeIf { it.sessionId == control.sessionId }
        return when (control.command) {
            Command.START -> when {
                mine != null && !mine.phase.isOver -> mine.state()
                workout.value.running -> State(control.sessionId, Phase.FAILED, clock(), WearLinkWorkoutControl.REASON_BUSY)
                else -> {
                    val sport = WorkoutSport.fromExerciseType(control.exerciseType)
                        ?: return State(control.sessionId, Phase.FAILED, clock(), WearLinkWorkoutControl.REASON_UNSUPPORTED)
                    controls.start(sport)
                    keep(Held(control.sessionId, Phase.RECORDING, clock())).state()
                }
            }
            Command.PAUSE -> steer(mine, control, Phase.RECORDING, Phase.PAUSED, controls::pause)
            Command.RESUME -> steer(mine, control, Phase.PAUSED, Phase.RECORDING, controls::resume)
            Command.STOP -> when {
                mine == null -> State(control.sessionId, Phase.NONE, clock())
                mine.phase.isOver -> mine.state()
                else -> {
                    val started = mine.workoutStartMillis ?: workout.value.startMillis.takeIf { workout.value.running }
                    controls.stop()
                    keep(
                        mine.copy(phase = Phase.STOPPED, sinceMillis = clock(), reason = WearLinkWorkoutControl.REASON_STOPPED_BY_CLIENT, workoutStartMillis = started),
                    ).state()
                }
            }
            Command.STATE -> mine?.state() ?: State(control.sessionId, Phase.NONE, clock())
        }
    }

    private fun steer(mine: Held?, control: WearLinkWorkoutControl.Control, from: Phase, to: Phase, act: () -> Unit): State {
        if (mine == null) return State(control.sessionId, Phase.NONE, clock())
        if (mine.phase != from) return mine.state()
        act()
        return keep(mine.copy(phase = to, sinceMillis = clock())).state()
    }

    /** The phase the workout is in now: a press on the watch, or a stop there, counts from when it is seen. */
    private fun observe(seen: Held): Held {
        val now = workout.value
        // The workout's start, once it runs: what its summary is read by after the stop.
        val held = if (seen.workoutStartMillis == null && !seen.phase.isOver && now.running && now.startMillis != null) {
            keep(seen.copy(workoutStartMillis = now.startMillis))
        } else {
            seen
        }
        // The service takes a command a moment after it was handed over: until then the workout shows the phase before.
        if (held.phase.isOver || clock() - held.sinceMillis < SETTLE_MILLIS) return held
        val phase = when {
            !now.running -> Phase.STOPPED
            now.snapshot?.paused == true -> Phase.PAUSED
            else -> Phase.RECORDING
        }
        if (phase == held.phase) return held
        val reason = if (phase == Phase.STOPPED) WearLinkWorkoutControl.REASON_STOPPED_ON_DEVICE else null
        return keep(held.copy(phase = phase, sinceMillis = clock(), reason = reason))
    }

    private fun keep(held: Held): Held = held.also(session::write)

    /** A stopped workout carries its summary; it is read again at each ask, so a recovery measured after the stop joins it. */
    private fun Held.state() = State(
        sessionId, phase, sinceMillis, reason,
        summary = if (phase == Phase.STOPPED) workoutStartMillis?.let(summaryOf) else null,
    )

    private companion object {
        const val SETTLE_MILLIS = 5_000L
    }

    /**
     * The session in the watch's store, one state row: `<session> <phase> <since> <reason|-> <workoutStart|->`,
     * the session as base64url of its UTF-8, as the link carries it, so it holds no space.
     */
    class Stored(private val store: MetricStore) : Store {
        override fun read(): Held? {
            val fields = store.state(KEY)?.split(' ') ?: return null
            if (fields.size != 5) return null
            val id = runCatching { String(Base64.getUrlDecoder().decode(fields[0]), Charsets.UTF_8) }.getOrNull() ?: return null
            val phase = Phase.fromWord(fields[1]) ?: return null
            val since = fields[2].toLongOrNull() ?: return null
            return Held(id, phase, since, fields[3].takeIf { it != NO_REASON }, fields[4].toLongOrNull())
        }

        override fun write(held: Held?) {
            if (held == null) {
                store.clearState(KEY)
                return
            }
            val id = Base64.getUrlEncoder().withoutPadding().encodeToString(held.sessionId.toByteArray(Charsets.UTF_8))
            store.setState(KEY, "$id ${held.phase.word} ${held.sinceMillis} ${held.reason ?: NO_REASON} ${held.workoutStartMillis ?: NO_REASON}")
        }

        private companion object {
            const val KEY = "remote_workout"
            const val NO_REASON = "-"
        }
    }
}
