package tech.mmarca.openwatch.devices.wearos

import androidx.health.connect.client.records.ExerciseSegment
import java.time.Duration
import java.time.Instant
import tech.mmarca.openwatch.wearlink.WearPlan
import tech.mmarca.openwatch.wearlink.WearPlanKind

/** Where the sync finds the plan a recorded workout ran with; the link's host keeps it. */
fun interface WorkoutPlanSource {
    /** The last plan of the workout [deviceId]'s watch started at [start], or null when it ran none. */
    fun planFor(deviceId: String, start: Instant): WearPlan?

    companion object {
        val None = WorkoutPlanSource { _, _ -> null }
    }
}

/**
 * The exercise segments of a recorded workout's session, from the last plan: one per exercise with a
 * done set, from the earliest to the latest time a set of it was done, repetitions the sum of the sets'
 * (a cardio exercise: the done sets), the segment type the client gave. A set has no duration of its
 * own, only the time it was done. Segments are cut to the session, never overlap each other or a
 * pause (a later one is trimmed to start after the earlier, one on a pause is dropped), as Health
 * Connect refuses the whole session otherwise. Pure.
 */
object WearOsPlanSegments {

    private val MIN_LENGTH = Duration.ofSeconds(1)

    fun segments(plan: WearPlan, start: Instant, end: Instant, pauses: List<ClosedRange<Instant>>): List<ExerciseSegment> {
        class Span(val from: Instant, val to: Instant, val type: Int, val repetitions: Int)
        val spans = plan.exercises.mapNotNull { exercise ->
            val type = exercise.segmentType ?: return@mapNotNull null
            val done = exercise.sets.filter { it.done && it.doneAtMillis != null }
            if (done.isEmpty()) return@mapNotNull null
            val times = done.map { Instant.ofEpochMilli(it.doneAtMillis!!) }
            val repetitions = if (exercise.kind == WearPlanKind.CARDIO) done.size else done.sumOf { it.repsDone ?: 0 }
            Span(times.min(), times.max(), type, repetitions)
        }.sortedBy { it.from }
        val out = ArrayList<ExerciseSegment>()
        var cursor = start
        for (span in spans) {
            // Wholly inside an earlier segment: nothing of it is left to write.
            if (span.to <= cursor) continue
            val from = maxOf(span.from, cursor)
            val to = minOf(maxOf(span.to, from.plus(MIN_LENGTH)), end)
            if (from >= to) continue
            if (pauses.any { from < it.endInclusive && it.start < to }) continue
            out += ExerciseSegment(from, to, span.type, span.repetitions)
            cursor = to
        }
        return out
    }
}
