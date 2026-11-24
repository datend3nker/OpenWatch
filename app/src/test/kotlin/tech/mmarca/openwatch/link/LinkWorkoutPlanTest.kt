package tech.mmarca.openwatch.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tech.mmarca.openlink.OpenLinkExerciseKind
import tech.mmarca.openlink.OpenLinkResistance
import tech.mmarca.openlink.OpenLinkWeightUnit
import tech.mmarca.openlink.OpenLinkWorkoutExercise
import tech.mmarca.openlink.OpenLinkWorkoutPlan
import tech.mmarca.openlink.OpenLinkWorkoutSet
import tech.mmarca.openwatch.wearlink.WearLinkPlan
import tech.mmarca.openwatch.wearlink.WearPlanKind
import tech.mmarca.openwatch.wearlink.WearPlanResistance
import tech.mmarca.openwatch.wearlink.WearPlanWeightUnit

class LinkWorkoutPlanTest {

    private val plan = OpenLinkWorkoutPlan(
        sessionId = "s-1",
        revision = 7L,
        currentSetId = "b",
        exercises = listOf(
            OpenLinkWorkoutExercise(
                exerciseId = "e1", label = "Bench press", kind = OpenLinkExerciseKind.WEIGHTED, resistance = OpenLinkResistance.EXTERNAL,
                segmentType = 70, restMinSeconds = 90,
                sets = listOf(
                    OpenLinkWorkoutSet("a", repsMin = 8, repsMax = 10, weight = 62.5, weightUnit = OpenLinkWeightUnit.KG, done = true, repsDone = 9, doneAtMillis = 1_000L, updatedAtMillis = 2_000L),
                    OpenLinkWorkoutSet("b", targetSeconds = 45, weight = 135.0, weightUnit = OpenLinkWeightUnit.LB),
                ),
            ),
            OpenLinkWorkoutExercise(exerciseId = "e2", label = "Row", kind = OpenLinkExerciseKind.CARDIO, sets = listOf(OpenLinkWorkoutSet("c", targetMeters = 500))),
        ),
    )

    @Test
    fun `a plan keeps its shape on the way to the watch`() {
        val wear = plan.toWearPlan()

        assertEquals("s-1", wear.sessionId)
        assertEquals(7L, wear.revision)
        assertEquals("b", wear.currentSetId)
        val bench = wear.exercises[0]
        assertEquals(WearPlanResistance.EXTERNAL, bench.resistance)
        assertEquals(70, bench.segmentType)
        assertEquals(90, bench.restMinSeconds)
        assertEquals(0, bench.restMaxSeconds)
        assertEquals(WearPlanKind.CARDIO, wear.exercises[1].kind)
        val first = bench.sets[0]
        assertEquals(625, first.weightTenths)
        assertEquals(WearPlanWeightUnit.KG, first.unit)
        assertEquals(1_000L, first.doneAtMillis)
        assertEquals(1350, bench.sets[1].weightTenths)
        assertEquals(WearPlanWeightUnit.LB, bench.sets[1].unit)
        assertNull(bench.sets[1].doneAtMillis)
    }

    @Test
    fun `the mapped plan is one the watch takes`() {
        val assembler = WearLinkPlan.Assembler()

        val kept = WearLinkPlan.lines(plan.toWearPlan()).all { assembler.accept(WearLinkPlan.parse(it)!!) }

        assertEquals(true, kept)
        assertEquals(plan.toWearPlan(), assembler.plan)
    }
}
