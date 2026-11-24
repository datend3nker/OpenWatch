package tech.mmarca.openwatch.wearlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WearLinkPlanTest {

    private fun set(id: String, done: Boolean = false) = WearPlanSet(
        id = id, repsMin = 8, repsMax = 10, weightTenths = 600, unit = WearPlanWeightUnit.KG, done = done,
        repsDone = if (done) 9 else null, doneAtMillis = if (done) 1_000L else null, updatedAtMillis = 5L,
    )

    private fun plan(revision: Long = 1, current: String? = "s2") = WearPlan(
        sessionId = "sess-1", revision = revision, currentSetId = current,
        exercises = listOf(
            WearPlanExercise("e1", "Bench press", sets = listOf(set("s1", done = true), set("s2")), segmentType = 70, restMinSeconds = 90, restMaxSeconds = 120),
            WearPlanExercise("e2", "Pull-up", resistance = WearPlanResistance.BODYWEIGHT, supersetWithNext = true, sets = listOf(set("s3"))),
        ),
    )

    private fun feed(assembler: WearLinkPlan.Assembler, lines: List<String>): Boolean =
        lines.all { line -> assembler.accept(WearLinkPlan.parse(line) ?: return false) }

    @Test
    fun `a plan survives its lines`() {
        val assembler = WearLinkPlan.Assembler()
        assertTrue(feed(assembler, WearLinkPlan.lines(plan())))
        assertEquals(plan(), assembler.plan)
    }

    @Test
    fun `every line fits the wire`() {
        val long = plan().copy(exercises = plan().exercises.map { it.copy(label = "x".repeat(200)) })
        for (line in WearLinkPlan.lines(long)) assertTrue(line.length <= WearLinkProtocol.MAX_LINE_LENGTH)
        assertEquals(WearLinkPlan.MAX_LABEL_LENGTH, (WearLinkPlan.parse(WearLinkPlan.lines(long)[1]) as WearLinkPlan.Line.Exercise).exercise.label.length)
    }

    @Test
    fun `a label keeps its spaces and loses control characters`() {
        val line = WearLinkPlan.lines(plan().copy(exercises = listOf(plan().exercises[0].copy(label = "Incline\tdumbbell   press"))))[1]
        assertEquals("Incline dumbbell press", (WearLinkPlan.parse(line) as WearLinkPlan.Line.Exercise).exercise.label)
    }

    @Test
    fun `the requests parse as requests`() {
        for (line in WearLinkPlan.lines(plan())) assertTrue(WearLinkProtocol.parseRequest(line) is WearLinkProtocol.Request.Plan)
        assertEquals(
            WearLinkProtocol.Request.WorkoutStart("sess-1", "70"),
            WearLinkProtocol.parseRequest(WearLinkProtocol.formatRequest(WearLinkProtocol.Request.WorkoutStart("sess-1", "70"))),
        )
        assertEquals(WearLinkProtocol.Request.WorkoutStop("sess-1"), WearLinkProtocol.parseRequest("WO_STOP sess-1"))
        assertNull(WearLinkProtocol.parseRequest("WO_START sess-1"))
        assertNull(WearLinkProtocol.parseRequest("WO_START sess-1 70 extra"))
    }

    @Test
    fun `a malformed line is none`() {
        assertNull(WearLinkPlan.parse("PLAN_BEGIN sess-1 1 - 1"))
        assertNull(WearLinkPlan.parse("PLAN_BEGIN sess-1 1 bad!id 1 1"))
        assertNull(WearLinkPlan.parse("PLAN_BEGIN sess-1 1 - 41 1"))
        assertNull(WearLinkPlan.parse("PLAN_EX e1 w n 0 - 0 0 0"))
        assertNull(WearLinkPlan.parse("PLAN_SET e1 s1 2000 - - - - kg 0 - - - - 0"))
        assertNull(WearLinkPlan.parse("PLAN_SET e1 s1 8 10 - - 600 stone 0 - - - - 0"))
    }

    @Test
    fun `a plan that does not add up is not taken`() {
        val lines = WearLinkPlan.lines(plan())
        val short = WearLinkPlan.Assembler()
        assertFalse(feed(short, lines.filterNot { it.startsWith("PLAN_SET e2 ") }))
        assertNull(short.plan)
        val wrongCount = WearLinkPlan.Assembler()
        assertFalse(feed(wrongCount, listOf(lines[0].replace(" 2 3", " 2 4")) + lines.drop(1)))
        assertNull(wrongCount.plan)
    }

    @Test
    fun `a set must follow its exercise, and ids are unique`() {
        val lines = WearLinkPlan.lines(plan())
        val wrongExercise = WearLinkPlan.Assembler()
        assertFalse(feed(wrongExercise, lines.map { if (it.startsWith("PLAN_SET e2 ")) it.replace("PLAN_SET e2 ", "PLAN_SET e1 ") else it }))
        val twin = WearLinkPlan.Assembler()
        assertFalse(feed(twin, lines.map { it.replace("PLAN_EX e2 ", "PLAN_EX e1 ") }))
    }

    @Test
    fun `a failed plan leaves the shown one`() {
        val assembler = WearLinkPlan.Assembler()
        feed(assembler, WearLinkPlan.lines(plan(revision = 1)))
        assertFalse(feed(assembler, WearLinkPlan.lines(plan(revision = 2)).dropLast(1) + "PLAN_END sess-1 9"))
        assertEquals(1L, assembler.plan?.revision)
    }

    @Test
    fun `an older or equal revision changes nothing, a newer one replaces`() {
        val assembler = WearLinkPlan.Assembler()
        feed(assembler, WearLinkPlan.lines(plan(revision = 5, current = "s2")))
        assertTrue(feed(assembler, WearLinkPlan.lines(plan(revision = 4, current = "s3"))))
        assertEquals("s2", assembler.plan?.currentSetId)
        assertTrue(feed(assembler, WearLinkPlan.lines(plan(revision = 5, current = "s3"))))
        assertEquals("s2", assembler.plan?.currentSetId)
        assertTrue(feed(assembler, WearLinkPlan.lines(plan(revision = 6, current = "s3"))))
        assertEquals("s3", assembler.plan?.currentSetId)
    }

    @Test
    fun `clear ends the plan of its session only`() {
        val assembler = WearLinkPlan.Assembler()
        feed(assembler, WearLinkPlan.lines(plan()))
        assertTrue(assembler.accept(WearLinkPlan.parse(WearLinkPlan.clearLine("other"))!!))
        assertEquals("sess-1", assembler.plan?.sessionId)
        assertTrue(assembler.accept(WearLinkPlan.parse(WearLinkPlan.clearLine("sess-1"))!!))
        assertNull(assembler.plan)
    }

    @Test
    fun `current is the named set, else the first one not done`() {
        val named = plan(current = "s3").current()!!
        assertEquals("Pull-up", named.exercise.label)
        assertEquals(1, named.setNumber)
        assertEquals(1, named.doneSets)
        assertEquals(3, named.totalSets)
        assertEquals("s2", plan(current = null).current()!!.set.id)
        assertEquals("s2", plan(current = "gone").current()!!.set.id)
    }

    @Test
    fun `a plan of one hundred sets goes through in one piece`() {
        val big = WearPlan("big", 1, null, List(10) { e -> WearPlanExercise("e$e", "Ex $e", sets = List(10) { s -> set("e${e}s$s") }) })
        val assembler = WearLinkPlan.Assembler()
        assertTrue(feed(assembler, WearLinkPlan.lines(big)))
        assertEquals(100, assembler.plan?.setCount)
    }

    @Test
    fun `a client's uuid is a session id`() {
        val uuid = "3f2b8c1e-9d4a-4e6b-8a57-0c1d2e3f4a5b"
        val assembler = WearLinkPlan.Assembler()
        assertTrue(feed(assembler, WearLinkPlan.lines(plan().copy(sessionId = uuid))))
        assertEquals(uuid, assembler.plan?.sessionId)
        assertEquals(WearLinkProtocol.Request.WorkoutStart(uuid, "70"), WearLinkProtocol.parseRequest("WO_START $uuid 70"))
        assertEquals(WearLinkProtocol.Request.WorkoutStop(uuid), WearLinkProtocol.parseRequest("WO_STOP $uuid"))
        assertNull(WearLinkProtocol.parseRequest("WO_STOP ${"x".repeat(129)}"))
        assertNull(WearLinkPlan.parse("PLAN_CLEAR with space"))
    }

    @Test
    fun `pause, resume and state are requests of their own`() {
        for (request in listOf(
            WearLinkProtocol.Request.WorkoutPause("s-1"),
            WearLinkProtocol.Request.WorkoutResume("s-1"),
            WearLinkProtocol.Request.WorkoutStatus("s-1"),
        )) {
            assertEquals(request, WearLinkProtocol.parseRequest(WearLinkProtocol.formatRequest(request)))
        }
        assertNull(WearLinkProtocol.parseRequest("WO_STATE"))
        assertEquals(WearWorkoutPhase.PAUSED, WearWorkoutPhase.fromWord("pause"))
        assertNull(WearWorkoutPhase.fromWord("stopped"))
    }
}
