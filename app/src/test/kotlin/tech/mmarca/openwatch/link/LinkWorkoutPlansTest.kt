package tech.mmarca.openwatch.link

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tech.mmarca.openwatch.wearlink.WearPlan

class LinkWorkoutPlansTest {

    private val started = Instant.parse("2026-11-14T10:00:00Z")
    private fun plan(revision: Long) = WearPlan("s", revision, null, emptyList())

    @Test
    fun `a plan is found by its device and the start of its workout`() {
        val plans = LinkWorkoutPlans()
        plans.remember("watch", started.toEpochMilli(), plan(3))

        assertEquals(3L, plans.planFor("watch", started.plusSeconds(20))?.revision)
        assertNull(plans.planFor("other", started))
        assertNull(plans.planFor("watch", started.plusSeconds(3600)))
    }

    @Test
    fun `only the newest sixteen are kept`() {
        val plans = LinkWorkoutPlans()
        for (i in 0 until 20) plans.remember("watch", started.toEpochMilli() + i * 3_600_000L, plan(i.toLong()))

        assertNull(plans.planFor("watch", started))
        assertEquals(19L, plans.planFor("watch", started.plusSeconds(19 * 3600))?.revision)
    }
}
