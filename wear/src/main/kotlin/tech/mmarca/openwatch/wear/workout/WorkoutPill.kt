package tech.mmarca.openwatch.wear.workout

import tech.mmarca.openwatch.wearlink.WorkoutSport

/**
 * What the watch face pill shows. It changes only when a workout starts,
 * pauses, resumes or ends; the stopwatch counts by itself in between.
 */
data class WorkoutPillKey(val sport: WorkoutSport, val paused: Boolean)

/** Null when no workout is running: then there is no pill. */
fun WorkoutUiState.pillKey(): WorkoutPillKey? {
    val sport = sport ?: return null
    if (!running) return null
    return WorkoutPillKey(sport, paused = snapshot?.paused == true)
}

/**
 * The time the pill's stopwatch counts from, on the `elapsedRealtime` clock.
 * Null while paused: the pill then says so in text and the clock stands still.
 * [activeSeconds] is the time without pauses, so a resume continues from it.
 */
fun stopwatchBaseMillis(key: WorkoutPillKey, activeSeconds: Long, elapsedRealtimeMillis: Long): Long? =
    if (key.paused) null else elapsedRealtimeMillis - activeSeconds * 1000
