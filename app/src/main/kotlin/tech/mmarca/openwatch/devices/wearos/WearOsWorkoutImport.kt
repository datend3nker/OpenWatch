package tech.mmarca.openwatch.devices.wearos

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.records.ExerciseSegment
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Length
import java.time.Instant
import java.time.ZoneId

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

    /** Health Connect's own ceiling for one distance record. */
    private const val MAX_DISTANCE_METRES = 1_000_000.0

    val sessionPermission: String = HealthPermission.getWritePermission(ExerciseSessionRecord::class)
    val distancePermission: String = HealthPermission.getWritePermission(DistanceRecord::class)
    const val routePermission: String = HealthPermission.PERMISSION_WRITE_EXERCISE_ROUTE

    /** The grants [workout]'s records need. */
    fun permissions(workout: WearOsWorkout): Set<String> = buildSet {
        add(sessionPermission)
        if (route(workout).isNotEmpty()) add(routePermission)
        if (distance(workout) != null) add(distancePermission)
    }

    fun records(workout: WearOsWorkout, zone: ZoneId): List<Record> {
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
