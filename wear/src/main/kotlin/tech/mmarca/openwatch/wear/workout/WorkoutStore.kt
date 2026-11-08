package tech.mmarca.openwatch.wear.workout

import tech.mmarca.openwatch.wearlink.WorkoutSport
import tech.mmarca.openwatch.wear.MetricStore
import tech.mmarca.openwatch.wear.WearMetrics
import tech.mmarca.openwatch.wearlink.Page
import tech.mmarca.openwatch.wearlink.WearLinkWorkout
import tech.mmarca.openwatch.wearlink.WearLinkWorkoutLines

/** The rows a workout is kept in. [MetricStore] holds them on the watch; a test holds them in memory. */
interface WorkoutRows {
    fun putPoint(point: WearLinkWorkout.RoutePoint)
    fun points(fromMillis: Long, toMillis: Long): List<WearLinkWorkout.RoutePoint>
    fun deletePoints(fromMillis: Long, toMillis: Long)
    fun putPauses(pauses: List<WearLinkWorkout.Pause>)
    fun pauses(fromMillis: Long, toMillis: Long): List<WearLinkWorkout.Pause>
    fun putHeader(workout: WearLinkWorkout)

    /** Headers started after [sinceMillis], oldest first, at most [limit]. */
    fun headers(sinceMillis: Long, limit: Int): List<WearLinkWorkout>
    fun checkpoint(): String?
    fun setCheckpoint(text: String)
    fun clearCheckpoint()
    fun prune(nowMillis: Long)
}

/**
 * Keeps workouts: route points as they arrive, a checkpoint while one runs,
 * and the header and pauses once it is finished. A workout whose app was
 * killed is finished from its checkpoint by [recover].
 */
class WorkoutStore(private val rows: WorkoutRows) {

    fun savePoint(point: WearLinkWorkout.RoutePoint) = rows.putPoint(point)

    fun saveCheckpoint(checkpoint: WorkoutCheckpoint) = rows.setCheckpoint(checkpoint.encode())

    /**
     * Saves [workout], or drops its route when it is shorter than [MIN_SAVED_MILLIS]: a workout
     * started by a slip is not worth a record. Returns whether it was kept.
     */
    fun finish(workout: WearLinkWorkout, nowMillis: Long): Boolean {
        val kept = workout.endMillis - workout.startMillis >= MIN_SAVED_MILLIS
        if (kept) {
            rows.putPauses(workout.pauses)
            rows.putHeader(workout.copy(pauses = emptyList(), route = emptyList()))
        } else {
            rows.deletePoints(workout.startMillis, workout.endMillis)
        }
        rows.clearCheckpoint()
        rows.prune(nowMillis)
        return kept
    }

    /**
     * Saves the workout a killed app left, ending where its last checkpoint was written, and
     * returns it; null when none was running or its checkpoint cannot be read.
     */
    fun recover(nowMillis: Long): WearLinkWorkout? {
        val checkpoint = WorkoutCheckpoint.decode(rows.checkpoint())
        if (checkpoint == null) {
            rows.clearCheckpoint()
            return null
        }
        val sport = WorkoutSport.fromId(checkpoint.sportId)
        val end = WorkoutTracker.limitedEnd(checkpoint.startMillis, checkpoint.savedMillis)
        val open = checkpoint.pausedSince?.takeIf { it < end }
        val workout = WearLinkWorkout(
            startMillis = checkpoint.startMillis,
            endMillis = end,
            exerciseType = (sport ?: WorkoutSport.OTHER_WORKOUT).exerciseType,
            steps = checkpoint.steps,
            distanceMetres = checkpoint.distanceMetres,
            ascentDecimetres = checkpoint.ascentDecimetres,
            pauses = WorkoutTracker.clipPauses(
                (checkpoint.pauses + listOfNotNull(open?.let { WearLinkWorkout.Pause(it, end) })).take(WearLinkWorkoutLines.MAX_PAUSES),
                end,
            ),
            route = rows.points(checkpoint.startMillis, end),
        )
        return workout.takeIf { finish(it, nowMillis) }
    }

    /** The points stored for the workout that began at [startMillis], for a tracker taking over after a restart. */
    fun pointsSince(startMillis: Long, untilMillis: Long): List<WearLinkWorkout.RoutePoint> = rows.points(startMillis, untilMillis)

    /** What `WO_SINCE` answers: whole workouts, each with its pauses and route. */
    fun workoutsSince(sinceMillis: Long, limit: Int): Page<WearLinkWorkout> {
        val headers = rows.headers(sinceMillis, limit + 1)
        val page = headers.take(limit).map { header ->
            header.copy(
                pauses = rows.pauses(header.startMillis, header.endMillis),
                route = rows.points(header.startMillis, header.endMillis),
            )
        }
        return Page(page, headers.size > limit)
    }

    companion object {
        /** A workout shorter than this is dropped on stopping. */
        const val MIN_SAVED_MILLIS: Long = 30_000
    }
}

/** [WorkoutRows] on the watch's [MetricStore]. */
class MetricStoreWorkoutRows(private val store: MetricStore) : WorkoutRows {
    override fun putPoint(point: WearLinkWorkout.RoutePoint) = store.put(WearMetrics.WORKOUT_POINTS, point)

    override fun points(fromMillis: Long, toMillis: Long): List<WearLinkWorkout.RoutePoint> =
        store.since(WearMetrics.WORKOUT_POINTS, fromMillis - 1, WearLinkWorkoutLines.MAX_POINTS, toMillis + 1)

    override fun deletePoints(fromMillis: Long, toMillis: Long) =
        store.deleteRange(WearMetrics.WORKOUT_POINTS, fromMillis, toMillis)

    override fun putPauses(pauses: List<WearLinkWorkout.Pause>) = store.putAll(WearMetrics.WORKOUT_PAUSES, pauses)

    override fun pauses(fromMillis: Long, toMillis: Long): List<WearLinkWorkout.Pause> =
        store.since(WearMetrics.WORKOUT_PAUSES, fromMillis - 1, WearLinkWorkoutLines.MAX_PAUSES, toMillis + 1)

    override fun putHeader(workout: WearLinkWorkout) = store.put(WearMetrics.WORKOUTS, workout)

    override fun headers(sinceMillis: Long, limit: Int): List<WearLinkWorkout> = store.since(WearMetrics.WORKOUTS, sinceMillis, limit)

    override fun checkpoint(): String? = store.state(CHECKPOINT_KEY)

    override fun setCheckpoint(text: String) = store.setState(CHECKPOINT_KEY, text)

    override fun clearCheckpoint() = store.clearState(CHECKPOINT_KEY)

    override fun prune(nowMillis: Long) {
        store.prune(WearMetrics.WORKOUTS, nowMillis)
        store.prune(WearMetrics.WORKOUT_PAUSES, nowMillis)
        store.prune(WearMetrics.WORKOUT_POINTS, nowMillis)
    }

    private companion object {
        const val CHECKPOINT_KEY = "workout"
    }
}
