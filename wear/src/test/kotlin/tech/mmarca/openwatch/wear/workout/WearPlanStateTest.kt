package tech.mmarca.openwatch.wear.workout

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.mmarca.openwatch.wearlink.WearLinkPlan
import tech.mmarca.openwatch.wearlink.WearPlan
import tech.mmarca.openwatch.wearlink.WearPlanExercise
import tech.mmarca.openwatch.wearlink.WearPlanSet

class WearPlanStateTest {

    private val plan = WearPlan("sess", 1, null, listOf(WearPlanExercise("e1", "Squat", sets = listOf(WearPlanSet("s1", repsMin = 5, repsMax = 5)))))

    @After
    fun reset() = WearPlanState.clear()

    private fun send(running: String?): Boolean =
        WearLinkPlan.lines(plan).all { WearPlanState.accept(WearLinkPlan.parse(it)!!, running) }

    @Test
    fun `a plan for the running session is shown`() {
        assertTrue(send(running = "sess"))
        assertEquals(plan, WearPlanState.plan.value)
    }

    @Test
    fun `a plan with no workout, or for another session, is refused`() {
        assertFalse(send(running = null))
        assertFalse(send(running = "other"))
        assertNull(WearPlanState.plan.value)
    }

    @Test
    fun `the end of the workout clears the plan`() {
        send(running = "sess")
        WearPlanState.clear()
        assertNull(WearPlanState.plan.value)
    }
}
