package tech.mmarca.openwatch.wear.workout

import android.content.Context
import kotlinx.coroutines.flow.StateFlow
import tech.mmarca.openwatch.wearlink.WearLinkWorkoutControl
import tech.mmarca.openwatch.wearlink.WearLinkWorkoutControl.Command
import tech.mmarca.openwatch.wearlink.WearLinkWorkoutControl.Phase
import tech.mmarca.openwatch.wearlink.WearLinkWorkoutControl.State
import tech.mmarca.openwatch.wearlink.WorkoutSport

/**
 * The workout the phone has the watch record for an app ([WearLinkWorkoutControl]): which session
 * it belongs to, and its phase as the workout's own state shows it. The workout is the ordinary one,
 * so the watch's buttons pause and stop it too; the phone learns of that when it asks for the state.
 * The session is kept in preferences, so a restarted service still answers for it.
 */
internal class RemoteWorkout(
    private val session: Store,
    private val controls: Controls,
    private val workout: StateFlow<WorkoutUiState> = WorkoutState.state,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** What starts and steers the watch's workout; each hands its command to the service and returns. */
    interface Controls {
        fun start(sport: WorkoutSport)
        fun pause()
        fun resume()
        fun stop()
    }

    /** The session of the workout the phone started, with the phase last told and since when. */
    data class Held(val sessionId: String, val phase: Phase, val sinceMillis: Long, val reason: String? = null)

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
                    controls.stop()
                    keep(mine.copy(phase = Phase.STOPPED, sinceMillis = clock(), reason = WearLinkWorkoutControl.REASON_STOPPED_BY_CLIENT)).state()
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
    private fun observe(held: Held): Held {
        // The service takes a command a moment after it was handed over: until then the workout shows the phase before.
        if (held.phase.isOver || clock() - held.sinceMillis < SETTLE_MILLIS) return held
        val now = workout.value
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

    private fun Held.state() = State(sessionId, phase, sinceMillis, reason)

    private companion object {
        const val SETTLE_MILLIS = 5_000L
    }

    /** The session in the watch's preferences. */
    class Prefs(context: Context) : Store {
        private val prefs = context.getSharedPreferences("remote_workout", Context.MODE_PRIVATE)

        override fun read(): Held? {
            val id = prefs.getString(KEY_SESSION, null) ?: return null
            val phase = Phase.fromWord(prefs.getString(KEY_PHASE, null).orEmpty()) ?: return null
            return Held(id, phase, prefs.getLong(KEY_SINCE, 0L), prefs.getString(KEY_REASON, null))
        }

        override fun write(held: Held?) {
            prefs.edit().apply {
                if (held == null) clear() else {
                    putString(KEY_SESSION, held.sessionId)
                    putString(KEY_PHASE, held.phase.word)
                    putLong(KEY_SINCE, held.sinceMillis)
                    putString(KEY_REASON, held.reason)
                }
            }.apply()
        }

        private companion object {
            const val KEY_SESSION = "session"
            const val KEY_PHASE = "phase"
            const val KEY_SINCE = "since"
            const val KEY_REASON = "reason"
        }
    }
}
