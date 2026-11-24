package tech.mmarca.openwatch.devices.wearos

import androidx.health.connect.client.records.ExerciseSegment
import androidx.health.connect.client.records.ExerciseSessionRecord
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.mmarca.openwatch.wearlink.WearPlan
import tech.mmarca.openwatch.wearlink.WearPlanExercise
import tech.mmarca.openwatch.wearlink.WearPlanKind
import tech.mmarca.openwatch.wearlink.WearPlanSet

class WearOsPlanSegmentsTest {

    private val start = Instant.parse("2026-11-14T10:00:00Z")
    private val end = start.plusSeconds(3600)

    private fun at(seconds: Long) = start.plusSeconds(seconds).toEpochMilli()

    private fun done(id: String, seconds: Long, reps: Int? = 8) = WearPlanSet(id, done = true, repsDone = reps, doneAtMillis = at(seconds))

    private fun exercise(id: String, vararg sets: WearPlanSet, type: Int? = 65, kind: WearPlanKind = WearPlanKind.WEIGHTED) =
        WearPlanExercise(id, id, kind = kind, segmentType = type, sets = sets.toList())

    private fun plan(vararg exercises: WearPlanExercise) = WearPlan("s", 1, null, exercises.toList())

    private fun segments(plan: WearPlan, pauses: List<ClosedRange<Instant>> = emptyList()) =
        WearOsPlanSegments.segments(plan, start, end, pauses)

    @Test
    fun `one segment per started exercise, from its first to its last done set`() {
        val result = segments(
            plan(
                exercise("bench", done("a", 300, 10), done("b", 480, 8), WearPlanSet("c")),
                exercise("squat", done("d", 900, 5)),
                exercise("rest day", WearPlanSet("e")),
            ),
        )

        assertEquals(2, result.size)
        assertEquals(start.plusSeconds(300), result[0].startTime)
        assertEquals(start.plusSeconds(480), result[0].endTime)
        assertEquals(18, result[0].repetitions)
        assertEquals(65, result[0].segmentType)
        // One set only: no length of its own, a second is given.
        assertEquals(start.plusSeconds(900), result[1].startTime)
        assertEquals(start.plusSeconds(901), result[1].endTime)
    }

    @Test
    fun `cardio counts its done sets, not repetitions`() {
        val result = segments(plan(exercise("row", done("a", 100, null), done("b", 200, null), type = 38, kind = WearPlanKind.CARDIO)))

        assertEquals(2, result.single().repetitions)
        assertEquals(38, result.single().segmentType)
    }

    @Test
    fun `an exercise without a segment type is left out`() {
        assertTrue(segments(plan(exercise("x", done("a", 100), type = null))).isEmpty())
    }

    @Test
    fun `overlapping exercises are trimmed, never refused`() {
        val result = segments(
            plan(
                exercise("a", done("a1", 100), done("a2", 400)),
                exercise("b", done("b1", 300), done("b2", 700)),
                exercise("c", done("c1", 350)),
            ),
        )

        assertEquals(listOf(100L to 400L, 400L to 700L), result.map { (it.startTime.epochSecond - start.epochSecond) to (it.endTime.epochSecond - start.epochSecond) })
    }

    @Test
    fun `segments are cut to the session and skip a pause`() {
        val result = segments(
            plan(
                exercise("early", done("a", -50), done("b", 60)),
                exercise("late", done("c", 3500), done("d", 4000)),
                exercise("paused", done("e", 1000), done("f", 1100)),
            ),
            pauses = listOf(start.plusSeconds(1050)..start.plusSeconds(1090)),
        )

        assertEquals(2, result.size)
        assertEquals(start, result[0].startTime)
        assertEquals(start.plusSeconds(3500), result[1].startTime)
        assertEquals(end, result[1].endTime)
    }

    @Test
    fun `a session takes the segments with its pauses, and falls back to the pauses alone for an unfit one`() {
        val workout = WearOsWorkout(
            start = start, end = end, exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING, steps = null,
            distanceMetres = null, ascentMetres = null, pauses = listOf(start.plusSeconds(200)..start.plusSeconds(260)), route = emptyList(),
        )
        val fits = ExerciseSegment(start.plusSeconds(300), start.plusSeconds(500), ExerciseSegment.EXERCISE_SEGMENT_TYPE_WEIGHTLIFTING, 12)
        val session = WearOsWorkoutImport.records(workout, ZoneOffset.UTC, exerciseSegments = listOf(fits)).filterIsInstance<ExerciseSessionRecord>().single()
        assertEquals(2, session.segments.size)

        // A segment that overlaps the pause: the constructor refuses, the session is still written.
        val overlaps = ExerciseSegment(start.plusSeconds(220), start.plusSeconds(300), ExerciseSegment.EXERCISE_SEGMENT_TYPE_WEIGHTLIFTING, 4)
        val fallback = WearOsWorkoutImport.records(workout, ZoneOffset.UTC, exerciseSegments = listOf(overlaps)).filterIsInstance<ExerciseSessionRecord>().single()
        assertEquals(1, fallback.segments.size)
    }
}
