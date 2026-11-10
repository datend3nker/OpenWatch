package tech.mmarca.openwatch.devices.wearos

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseLap
import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.records.ExerciseSegment
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import tech.mmarca.openwatch.domain.insights.WorkoutEnergyResult
import tech.mmarca.openwatch.domain.insights.estimateWorkoutEnergy
import tech.mmarca.openwatch.domain.preferences.BodyProfile

/**
 * Maps a workout the watch recorded to Health Connect: an
 * `ExerciseSessionRecord` with its route and its pauses as pause segments,
 * and, for a workout that measured one, a `DistanceRecord` over the same
 * time. As in OpenVitals, steps, climbed height and heart rate are not
 * written again: the watch's all-day series already carry them minute by
 * minute, and a second copy over the same minutes would count twice.
 *
 * Both ids are functions of the workout's start, so a pull that repeats
 * rewrites the session instead of adding a second one.
 *
 * Pure, so it is testable without a device.
 */
object WearOsWorkoutImport {

    const val KEY_PREFIX = "wearos_workout_"
    const val DISTANCE_PREFIX = "wearos_workout_distance_"
    const val ENERGY_PREFIX = "wearos_workout_energy_"

    /** Health Connect's own ceiling for one distance record. */
    private const val MAX_DISTANCE_METRES = 1_000_000.0

    val sessionPermission: String = HealthPermission.getWritePermission(ExerciseSessionRecord::class)
    val distancePermission: String = HealthPermission.getWritePermission(DistanceRecord::class)
    val energyPermission: String = HealthPermission.getWritePermission(ActiveCaloriesBurnedRecord::class)
    const val routePermission: String = HealthPermission.PERMISSION_WRITE_EXERCISE_ROUTE

    /** The grants [workout]'s records need. */
    fun permissions(workout: WearOsWorkout): Set<String> = buildSet {
        add(sessionPermission)
        if (route(workout).isNotEmpty()) add(routePermission)
        if (distance(workout) != null) add(distancePermission)
    }

    fun records(workout: WearOsWorkout, zone: ZoneId, laps: List<ExerciseLap> = emptyList(), notes: String? = null): List<Record> {
        val startOffset = zone.rules.getOffset(workout.start)
        val endOffset = zone.rules.getOffset(workout.end)
        val route = route(workout)
        val session = ExerciseSessionRecord(
            startTime = workout.start,
            startZoneOffset = startOffset,
            endTime = workout.end,
            endZoneOffset = endOffset,
            metadata = metadata(importKey(workout.start)),
            exerciseType = workout.exerciseType,
            segments = workout.pauses
                .filter { it.start >= workout.start && it.endInclusive <= workout.end && it.start < it.endInclusive }
                .map { ExerciseSegment(it.start, it.endInclusive, ExerciseSegment.EXERCISE_SEGMENT_TYPE_PAUSE) },
            exerciseRoute = route.takeIf { it.isNotEmpty() }?.let(::ExerciseRoute),
            laps = laps,
            notes = notes,
        )
        val distance = distance(workout)?.let {
            DistanceRecord(
                startTime = workout.start,
                startZoneOffset = startOffset,
                endTime = workout.end,
                endZoneOffset = endOffset,
                distance = Length.meters(it),
                metadata = metadata("$DISTANCE_PREFIX${workout.start.toEpochMilli()}"),
            )
        }
        return listOfNotNull(session, distance)
    }

    fun importKey(start: Instant): String = "$KEY_PREFIX${start.toEpochMilli()}"

    /**
     * The workout's active calories, estimated, as an `ActiveCaloriesBurnedRecord`
     * over the session: [estimateWorkoutEnergy] from the mean of the watch's
     * heart rate [samples] outside the pauses, the walk or run's distance, or the
     * activity's Compendium MET, with the body [profile]. The watch measures no
     * calories itself; minutes inside the session get no walking energy
     * ([WearOsStepsImport.outsideExerciseSessions]), so nothing counts twice.
     *
     * [WorkoutEnergyResult.MissingInputs] when the profile has no weight, so the
     * caller can say so; the id is a function of the start, so a repeat rewrites it.
     */
    fun activeCalories(
        workout: WearOsWorkout,
        zone: ZoneId,
        profile: BodyProfile,
        samples: List<Pair<Instant, Long>>,
        today: LocalDate,
    ): Pair<ActiveCaloriesBurnedRecord?, WorkoutEnergyResult> {
        val pauses = workout.pauses.filter { it.start < it.endInclusive }
        val moving = Duration.between(workout.start, workout.end) - pauses.fold(Duration.ZERO) { sum, pause ->
            val from = maxOf(pause.start, workout.start)
            val to = minOf(pause.endInclusive, workout.end)
            if (from < to) sum + Duration.between(from, to) else sum
        }
        val beats = samples
            .filter { (time, _) -> !time.isBefore(workout.start) && time.isBefore(workout.end) }
            .filter { (time, _) -> pauses.none { time >= it.start && time < it.endInclusive } }
            .map { (_, bpm) -> bpm }
        val result = estimateWorkoutEnergy(
            exerciseType = workout.exerciseType,
            durationMinutes = moving.seconds / 60.0,
            meanHeartRateBpm = beats.takeIf { it.isNotEmpty() }?.average(),
            profile = profile,
            today = today,
            distanceMeters = distance(workout),
        )
        val estimate = result as? WorkoutEnergyResult.Estimate ?: return null to result
        val record = ActiveCaloriesBurnedRecord(
            startTime = workout.start,
            startZoneOffset = zone.rules.getOffset(workout.start),
            endTime = workout.end,
            endZoneOffset = zone.rules.getOffset(workout.end),
            energy = Energy.kilocalories(estimate.activeKcal),
            metadata = metadata("$ENERGY_PREFIX${workout.start.toEpochMilli()}"),
        )
        return record to result
    }

    // Every point must lie inside the session, or Health Connect refuses the route.
    private fun route(workout: WearOsWorkout): List<ExerciseRoute.Location> = workout.route
        .filter { !it.time.isBefore(workout.start) && it.time.isBefore(workout.end) }
        .map {
            ExerciseRoute.Location(
                time = it.time,
                latitude = it.latitude,
                longitude = it.longitude,
                horizontalAccuracy = it.accuracyMetres?.let(Length::meters),
                altitude = it.altitudeMetres?.let(Length::meters),
            )
        }

    private fun distance(workout: WearOsWorkout): Double? =
        workout.distanceMetres?.toDouble()?.takeIf { it > 0.0 && it <= MAX_DISTANCE_METRES }

    private fun metadata(id: String) = Metadata.autoRecorded(
        device = Device(type = Device.TYPE_WATCH),
        clientRecordId = id,
    )
}
