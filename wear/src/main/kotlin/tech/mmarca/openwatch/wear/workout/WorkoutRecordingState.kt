package tech.mmarca.openwatch.wear.workout

import tech.mmarca.openwatch.wear.RecordingUiState
import tech.mmarca.openwatch.wear.features.recording.ActivityType
import tech.mmarca.openwatch.wear.features.recording.WorkoutField
import tech.mmarca.openwatch.wearlink.WorkoutSport

/**
 * The recorder's workout as the workout screens draw it: the icon of the
 * nearest [ActivityType], the sport's own name, and the values it reports.
 */
internal fun WorkoutSnapshot.toRecordingState(sport: WorkoutSport, title: Int): RecordingUiState = RecordingUiState(
    activityType = activityTypeOf(sport),
    title = title,
    elapsedSeconds = activeSeconds,
    heartRateBpm = heartRateBpm,
    distanceMeters = distanceMetres,
    elevationGainMeters = ascentMetres,
    speedMetersPerSecond = speedMetresPerSecond ?: averageSpeedMetresPerSecond,
    averageHeartRateBpm = averageHeartRateBpm,
    paused = paused,
    waitingForFix = waitingForFix,
)

/** The fields the recorder fills for [sport]; a workout's calories have no source without Health Services. */
internal fun workoutFieldsOf(sport: WorkoutSport, gpsGranted: Boolean): Set<WorkoutField> = buildSet {
    add(WorkoutField.HEART_RATE)
    if (sport.gps && gpsGranted) {
        add(WorkoutField.DISTANCE)
        add(if (sport == WorkoutSport.BIKING) WorkoutField.SPEED else WorkoutField.PACE)
    }
    if (sport.elevation) add(WorkoutField.ELEVATION)
    if (sport.steps) add(WorkoutField.STEPS)
}

internal fun activityTypeOf(sport: WorkoutSport): ActivityType = when (sport) {
    WorkoutSport.WALKING -> ActivityType.WALK
    WorkoutSport.RUNNING, WorkoutSport.TREADMILL -> ActivityType.RUN
    WorkoutSport.BIKING, WorkoutSport.STATIONARY_BIKE -> ActivityType.CYCLE
    WorkoutSport.HIKING -> ActivityType.HIKE
    else -> ActivityType.WORKOUT
}
