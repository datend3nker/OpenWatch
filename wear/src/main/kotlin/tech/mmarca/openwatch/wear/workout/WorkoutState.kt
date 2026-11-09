package tech.mmarca.openwatch.wear.workout

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import tech.mmarca.openwatch.wearlink.WorkoutSport

/** What the screens know of the workout: running, or the last one finished. */
data class WorkoutUiState(
    val sport: WorkoutSport? = null,
    val running: Boolean = false,
    /** The latest snapshot while running; the final one after stopping. */
    val snapshot: WorkoutSnapshot? = null,
    /** True after a stop that kept the workout, until the summary is closed. */
    val finished: Boolean = false,
    /** The heart rate recovery after the stop, while it is measured and after. */
    val recovery: RecoveryState? = null,
)

/**
 * Two minutes of heart rate after a workout, as Cole et al. measured
 * recovery (`WorkoutMetrics.recovery`): [untilMillis] is when it ends,
 * [oneMinuteBpm] and [twoMinutesBpm] the falls once known.
 */
data class RecoveryState(
    val stopMillis: Long,
    val untilMillis: Long,
    val bpm: Int? = null,
    val oneMinuteBpm: Int? = null,
    val twoMinutesBpm: Int? = null,
) {
    val running: Boolean get() = oneMinuteBpm == null && twoMinutesBpm == null && !done
    val done: Boolean get() = System.currentTimeMillis() >= untilMillis
}

/**
 * The workout's state, written by [WorkoutRecorder] in the service and read
 * by the screens: one holder for the process, like `WearLinkState`.
 */
object WorkoutState {
    private val flow = MutableStateFlow(WorkoutUiState())
    val state: StateFlow<WorkoutUiState> = flow

    fun update(change: (WorkoutUiState) -> WorkoutUiState) = flow.update(change)

    /** The wearer closed the summary. */
    fun clearFinished() = flow.update { if (it.running) it else WorkoutUiState() }
}
