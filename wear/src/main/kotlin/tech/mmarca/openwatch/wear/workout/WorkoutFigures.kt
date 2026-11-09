package tech.mmarca.openwatch.wear.workout

import android.content.Context
import java.time.LocalDate
import tech.mmarca.openwatch.wear.MetricStore
import tech.mmarca.openwatch.wear.WearMetrics
import tech.mmarca.openwatch.wear.WearSettings
import tech.mmarca.openwatch.wear.features.metric.ZoneBasis
import tech.mmarca.openwatch.wearlink.WorkoutMetrics
import tech.mmarca.openwatch.wearlink.WorkoutSport

/**
 * A finished workout's figures on the watch, by the same [WorkoutMetrics] the
 * phone writes to Health Connect: the saved workout with its pauses and route,
 * the heart rate the watch kept, and the profile and zones the phone sent.
 * Reads the store: call it off the main thread.
 */
internal object WorkoutFigures {

    fun latest(context: Context, zones: ZoneBasis): WorkoutMetrics.Summary? {
        val metricStore = MetricStore(context)
        val workout = WorkoutStore(MetricStoreWorkoutRows(metricStore))
            .workoutsSince(System.currentTimeMillis() - LOOKBACK_MILLIS, MAX_WORKOUTS).items.lastOrNull() ?: return null
        val beats = metricStore.since(WearMetrics.HEART_RATE, workout.startMillis - 1, MAX_READINGS, workout.endMillis + 1)
            .map { WorkoutMetrics.Beat(it.epochMillis, it.bpm) }
        val settings = WearSettings.read(context)
        val body = WorkoutMetrics.Body(
            weightKg = settings.weightHectograms?.div(10.0),
            heightCm = settings.heightCm?.toDouble(),
            ageYears = settings.birthYear?.let { LocalDate.now().year - it },
            female = settings.female,
        )
        return WorkoutMetrics.summarize(workout, beats, zones, body, WorkoutSport.fromExerciseType(workout.exerciseType))
    }

    private const val LOOKBACK_MILLIS = 2L * 24 * 60 * 60 * 1000
    private const val MAX_WORKOUTS = 50

    /** A day at one reading a second. */
    private const val MAX_READINGS = 90_000
}
