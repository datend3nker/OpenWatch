package tech.mmarca.openwatch.devices.wearos

import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import java.time.Duration
import java.time.ZoneId
import tech.mmarca.openwatch.domain.insights.HeartRateZoneBasis
import tech.mmarca.openwatch.domain.preferences.BiologicalSex
import tech.mmarca.openwatch.domain.preferences.BodyProfile
import tech.mmarca.openwatch.healthconnect.HealthConnectGateway
import tech.mmarca.openwatch.wearlink.HeartRateZones
import tech.mmarca.openwatch.wearlink.WorkoutMetrics
import tech.mmarca.openwatch.wearlink.WorkoutSport

/**
 * Works out a workout's [WorkoutMetrics.Summary] on the phone: the heart rate
 * Health Connect holds for the session (the sync writes the watch's readings
 * before the workouts), the body profile, and the zones' maximum and resting
 * rate as [HeartRateZoneBasis] chooses them.
 */
internal class WearOsWorkoutFigures(
    private val healthConnect: HealthConnectGateway,
    private val profile: suspend () -> BodyProfile,
    private val zone: () -> ZoneId,
) {

    suspend fun summarize(workout: WearOsWorkout): WorkoutMetrics.Summary? {
        val beats = ArrayList<WorkoutMetrics.Beat>()
        healthConnect.forEachSyncRecordPage(HeartRateRecord::class, workout.start, workout.end.plus(RECOVERY_SPAN)) { page ->
            for (record in page.filterIsInstance<HeartRateRecord>()) {
                for (sample in record.samples) beats += WorkoutMetrics.Beat(sample.time.toEpochMilli(), sample.beatsPerMinute.toInt())
            }
        }
        if (beats.isEmpty()) return null
        val body = profile()
        val day = workout.start.atZone(zone()).toLocalDate()
        val resting = latestResting(workout)
        val observed = healthConnect.readMaxHeartRate(workout.end.minus(Duration.ofDays(365)), workout.end.plusSeconds(1))?.toInt()
        val basis = HeartRateZoneBasis.of(body, day, observed, resting)
        val zones = basis.maxBpm?.let { HeartRateZones.Basis(it, basis.restingBpm ?: resting) }
        val metricsBody = WorkoutMetrics.Body(
            weightKg = body.weightKg,
            heightCm = body.heightCm,
            ageYears = body.ageYears(day),
            female = body.sex?.let { it == BiologicalSex.FEMALE },
        )
        val end = workout.end.toEpochMilli()
        val after = beats.count { it.epochMillis in (end + 1)..(end + RECOVERY_SPAN.toMillis()) }
        val recovery = if (after >= WorkoutMetrics.MIN_RECOVERY_READINGS) WorkoutMetrics.recovery(beats, end).first else null
        return WorkoutMetrics.summarize(
            WearOsWorkoutMetrics.linkWorkout(workout), beats, zones, metricsBody, WorkoutSport.fromExerciseType(workout.exerciseType),
        ).copy(recoveryBpm = recovery)
    }

    /** The latest night's resting heart rate before the workout, from any app. */
    private suspend fun latestResting(workout: WearOsWorkout): Int? {
        var latest: RestingHeartRateRecord? = null
        healthConnect.forEachSyncRecordPage(RestingHeartRateRecord::class, workout.start.minus(RESTING_LOOKBACK), workout.end) { page ->
            for (record in page.filterIsInstance<RestingHeartRateRecord>()) if (latest == null || record.time > latest!!.time) latest = record
        }
        return latest?.beatsPerMinute?.toInt()
    }

    private companion object {
        val RESTING_LOOKBACK: Duration = Duration.ofDays(7)

        /** The watch measures a recovery for two minutes after the stop. */
        val RECOVERY_SPAN: Duration = Duration.ofSeconds(130)
    }
}
