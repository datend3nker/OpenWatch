package tech.mmarca.openwatch.wear.workout

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import tech.mmarca.openwatch.wearlink.WearLinkPlan
import tech.mmarca.openwatch.wearlink.WearPlan

/**
 * The plan of the recorded workout the screens show, from the phone's `PLAN_*` lines. One holder for
 * the process, like [WorkoutState]. A plan belongs to the session of the running workout and ends with
 * it ([clear]); it is never written to disk, and its labels are never logged.
 */
object WearPlanState {
    private var assembler = WearLinkPlan.Assembler()
    private val flow = MutableStateFlow<WearPlan?>(null)

    val plan: StateFlow<WearPlan?> = flow

    /**
     * Takes [line] for the workout of [runningSessionId], null when none runs for a phone's session.
     * A plan for any other session is refused, as the lines of one that did not begin.
     */
    @Synchronized
    fun accept(line: WearLinkPlan.Line, runningSessionId: String?): Boolean {
        if (line is WearLinkPlan.Line.Begin && line.sessionId != runningSessionId) return false
        val kept = assembler.accept(line)
        flow.value = assembler.plan
        return kept
    }

    /** The workout ended: no plan stays. */
    @Synchronized
    fun clear() {
        assembler = WearLinkPlan.Assembler()
        flow.value = null
    }
}
