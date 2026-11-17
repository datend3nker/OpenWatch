package tech.mmarca.openwatch.healthconnect

import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.CyclingPedalingCadenceRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseLap
import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.records.ExerciseSegment
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsCadenceRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WheelchairPushesRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Power
import androidx.health.connect.client.units.Velocity
import androidx.health.connect.client.units.kilocalories
import androidx.health.connect.client.units.meters
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tech.mmarca.healthcalculations.model.ExerciseRoutePoint
import tech.mmarca.openwatch.domain.model.ActivityExerciseSegmentWrite
import tech.mmarca.openwatch.domain.model.ActivityRecordSource
import tech.mmarca.openwatch.domain.model.ActivityWriteLimits
import tech.mmarca.openwatch.domain.model.ActivityWriteRequest
import tech.mmarca.openwatch.domain.model.BleRecordingSampleBuffer
import tech.mmarca.openwatch.domain.model.importSessionClientRecordId

/**
 * Writes an activity and the records beside it, as OpenVitals' manual entry
 * and file import do: the same client ids, metadata and validation, so a
 * watch file imported here and one imported there are the same records.
 * Only the write half lives here; OpenWatch reads no activity back.
 */
class ActivityRecordWriter(
    /** OpenWatch: told what was written once an insert went through. */
    private val onInserted: (List<Record>) -> Unit = {},
    private val client: () -> HealthConnectClient,
) {
    suspend fun writeActivityEntry(request: ActivityWriteRequest): String = withContext(Dispatchers.IO) {
        validateActivityWriteRequest(request)

        val zone = ZoneId.systemDefault()
        val sessionClientRecordId = request.sessionClientRecordId()
        val sessionMetadata = request.recordMetadata(sessionClientRecordId)
        val exerciseSegments = request.toExerciseSegments()
        val exerciseLaps = request.toExerciseLaps()
        val session = request.toExerciseSessionRecord(sessionMetadata, exerciseSegments, zone)
        val extraRecords = request.toManualActivityMetricRecords(zone)

        Log.d(
            TAG,
            "Writing activity entry type=${request.exerciseType} " +
                "hasRoute=${request.routePoints.isNotEmpty()} pauses=${request.pauseIntervals.size} " +
                "segments=${exerciseSegments.size} laps=${exerciseLaps.size} " +
                "extras=${extraRecords.size}",
        )
        insertShrinkingRoutes(listOf(session) + extraRecords)
            .firstOrNull()
            ?: sessionClientRecordId
    }

    /**
     * Writes many activities in one call. Health Connect rate-limits per
     * call, so one file at a time exhausted the daily allowance. The insert
     * is atomic, hence the importer's file-by-file retry.
     */
    suspend fun writeActivityEntries(requests: List<ActivityWriteRequest>): List<String> = withContext(Dispatchers.IO) {
        if (requests.isEmpty()) return@withContext emptyList()
        requests.forEach { validateActivityWriteRequest(it) }

        val zone = ZoneId.systemDefault()
        val records = ArrayList<androidx.health.connect.client.records.Record>(requests.size * 2)
        val clientRecordIds = ArrayList<String>(requests.size)
        for (request in requests) {
            val sessionClientRecordId = request.sessionClientRecordId()
            val sessionMetadata = request.recordMetadata(sessionClientRecordId)
            clientRecordIds.add(sessionClientRecordId)
            records.add(request.toExerciseSessionRecord(sessionMetadata, request.toExerciseSegments(), zone))
            records.addAll(request.toManualActivityMetricRecords(zone))
        }

        Log.d(
            TAG,
            "Writing ${requests.size} activity entries in one call " +
                "records=${records.size}",
        )
        insertShrinkingRoutes(records)
        clientRecordIds
    }


    private suspend fun insertShrinkingRoutes(records: List<Record>): List<String> =
        withShrunkRoutes(records) { written -> client().insertRecords(written).recordIdsList.also { onInserted(written) } }

    private suspend fun <T> withShrunkRoutes(
        records: List<Record>,
        write: suspend (List<Record>) -> T,
    ): T {
        var current = records
        var attempts = 0
        while (true) {
            try {
                return write(current)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                val (limit, was) = OversizedRouteShrinker.recordSizeOverrun(e) ?: throw e
                val shrunk = OversizedRouteShrinker.shrink(current, limit, was) ?: throw e
                attempts += 1
                if (attempts > MaxRouteShrinkAttempts) throw e
                Log.w(
                    TAG,
                    "Exercise route over the record size limit ($was > $limit bytes); " +
                        "decimating and retrying (attempt $attempts)",
                )
                current = shrunk
            }
        }
    }

    private fun ActivityWriteRequest.toExerciseSessionRecord(
        metadata: Metadata,
        exerciseSegments: List<ExerciseSegment>,
        zone: ZoneId,
    ): ExerciseSessionRecord =
        ExerciseSessionRecord(
            startTime = startTime,
            startZoneOffset = zone.rules.getOffset(startTime),
            endTime = endTime,
            endZoneOffset = zone.rules.getOffset(endTime),
            metadata = metadata,
            exerciseType = exerciseType,
            title = title?.trim()?.takeIf { it.isNotBlank() },
            notes = notes?.trim()?.takeIf { it.isNotBlank() },
            segments = exerciseSegments,
            laps = toExerciseLaps(),
            exerciseRoute = routePoints.toExerciseRouteOrNull(),
            plannedExerciseSessionId = plannedExerciseSessionId,
        )

    private fun ActivityWriteRequest.toManualActivityMetricRecords(zone: ZoneId): List<Record> {
        val startOffset = zone.rules.getOffset(startTime)
        val endOffset = zone.rules.getOffset(endTime)
        return buildList {
            distanceMeters?.let { meters ->
                add(
                    DistanceRecord(
                        startTime = startTime,
                        startZoneOffset = startOffset,
                        endTime = endTime,
                        endZoneOffset = endOffset,
                        distance = meters.meters,
                        metadata = metricMetadata("distance", startTime),
                    )
                )
            }
            elevationGainedMeters?.let { meters ->
                add(
                    ElevationGainedRecord(
                        startTime = startTime,
                        startZoneOffset = startOffset,
                        endTime = endTime,
                        endZoneOffset = endOffset,
                        elevation = meters.meters,
                        metadata = metricMetadata("elevation", startTime),
                    )
                )
            }
            activeCaloriesKcal?.let { kcal ->
                add(
                    ActiveCaloriesBurnedRecord(
                        startTime = startTime,
                        startZoneOffset = startOffset,
                        endTime = endTime,
                        endZoneOffset = endOffset,
                        energy = kcal.kilocalories,
                        metadata = metricMetadata("active_calories", startTime),
                    )
                )
            }
            totalCaloriesKcal?.let { kcal ->
                add(
                    TotalCaloriesBurnedRecord(
                        startTime = startTime,
                        startZoneOffset = startOffset,
                        endTime = endTime,
                        endZoneOffset = endOffset,
                        energy = kcal.kilocalories,
                        metadata = metricMetadata("total_calories", startTime),
                    )
                )
            }
            stepsCount?.let { steps ->
                add(
                    StepsRecord(
                        startTime = startTime,
                        startZoneOffset = startOffset,
                        endTime = endTime,
                        endZoneOffset = endOffset,
                        count = steps,
                        metadata = metricMetadata("steps", startTime),
                    )
                )
            }
            floorsClimbed?.let { floors ->
                add(
                    FloorsClimbedRecord(
                        startTime = startTime,
                        startZoneOffset = startOffset,
                        endTime = endTime,
                        endZoneOffset = endOffset,
                        floors = floors,
                        metadata = metricMetadata("floors", startTime),
                    )
                )
            }
            wheelchairPushes?.let { pushes ->
                add(
                    WheelchairPushesRecord(
                        startTime = startTime,
                        startZoneOffset = startOffset,
                        endTime = endTime,
                        endZoneOffset = endOffset,
                        count = pushes,
                        metadata = metricMetadata("wheelchair_pushes", startTime),
                    )
                )
            }
            addAll(
                bleSamples.toManualActivitySensorRecords(startTime, endTime, zone) { kind, start ->
                    metricMetadata(kind, start)
                },
            )
        }
    }

    private fun BleRecordingSampleBuffer.toManualActivitySensorRecords(
        startTime: Instant,
        endTime: Instant,
        zone: ZoneId,
        metricMetadata: (kind: String, startTime: Instant) -> Metadata,
    ): List<Record> {
        if (isEmpty()) return emptyList()
        val startOffset = zone.rules.getOffset(startTime)
        val endOffset = zone.rules.getOffset(endTime)
        return buildList {
            if (heartRateSamples.isNotEmpty()) {
                add(
                    HeartRateRecord(
                        startTime = startTime,
                        startZoneOffset = startOffset,
                        endTime = endTime,
                        endZoneOffset = endOffset,
                        samples = heartRateSamples.map {
                            HeartRateRecord.Sample(
                                time = it.time.coerceInSession(startTime, endTime),
                                beatsPerMinute = it.beatsPerMinute,
                            )
                        },
                        metadata = metricMetadata("heart_rate", startTime),
                    )
                )
            }
            if (powerSamples.isNotEmpty()) {
                add(
                    PowerRecord(
                        startTime = startTime,
                        startZoneOffset = startOffset,
                        endTime = endTime,
                        endZoneOffset = endOffset,
                        samples = powerSamples.map { sample ->
                            PowerRecord.Sample(
                                time = sample.time.coerceInSession(startTime, endTime),
                                power = Power.watts(sample.watts),
                            )
                        },
                        metadata = metricMetadata("power", startTime),
                    )
                )
            }
            if (cyclingCadenceSamples.isNotEmpty()) {
                add(
                    CyclingPedalingCadenceRecord(
                        startTime = startTime,
                        startZoneOffset = startOffset,
                        endTime = endTime,
                        endZoneOffset = endOffset,
                        samples = cyclingCadenceSamples.map {
                            CyclingPedalingCadenceRecord.Sample(
                                time = it.time.coerceInSession(startTime, endTime),
                                revolutionsPerMinute = it.rpm.toDouble(),
                            )
                        },
                        metadata = metricMetadata("cycling_cadence", startTime),
                    )
                )
            }
            val cyclingSpeedSamples = speedSamples.filterNot { it.isRunning }
            if (cyclingSpeedSamples.isNotEmpty()) {
                add(
                    SpeedRecord(
                        startTime = startTime,
                        startZoneOffset = startOffset,
                        endTime = endTime,
                        endZoneOffset = endOffset,
                        samples = cyclingSpeedSamples.map { sample ->
                            SpeedRecord.Sample(
                                time = sample.time.coerceInSession(startTime, endTime),
                                speed = Velocity.metersPerSecond(sample.metersPerSecond),
                            )
                        },
                        metadata = metricMetadata("speed", startTime),
                    )
                )
            }
            val runningSpeedSamples = speedSamples.filter { it.isRunning }
            if (runningSpeedSamples.isNotEmpty()) {
                add(
                    SpeedRecord(
                        startTime = startTime,
                        startZoneOffset = startOffset,
                        endTime = endTime,
                        endZoneOffset = endOffset,
                        samples = runningSpeedSamples.map { sample ->
                            SpeedRecord.Sample(
                                time = sample.time.coerceInSession(startTime, endTime),
                                speed = Velocity.metersPerSecond(sample.metersPerSecond),
                            )
                        },
                        metadata = metricMetadata("running_speed", startTime),
                    )
                )
            }
            if (stepsCadenceSamples.isNotEmpty()) {
                add(
                    StepsCadenceRecord(
                        startTime = startTime,
                        startZoneOffset = startOffset,
                        endTime = endTime,
                        endZoneOffset = endOffset,
                        samples = stepsCadenceSamples.map {
                            StepsCadenceRecord.Sample(
                                time = it.time.coerceInSession(startTime, endTime),
                                rate = it.stepsPerMinute.toDouble(),
                            )
                        },
                        metadata = metricMetadata("steps_cadence", startTime),
                    )
                )
            }
        }
    }

    private fun Instant.coerceInSession(startTime: Instant, endTime: Instant): Instant =
        when {
            isBefore(startTime) -> startTime
            isAfter(endTime) -> endTime
            else -> this
        }

    /**
     * Deletes the metric records of the session that starts at [start]. A range read matches
     * on start time, so another activity that starts inside this window matches too. The
     * session start in each client id is what tells them apart.
     */

    private fun manualActivityMetricIdPrefix(kind: String, sessionStart: Instant): String =
        "openvitals_activity_${kind}_${sessionStart.toEpochMilli()}_"

    /** Random for an entry, a function of the file for an import: see [ActivityWriteRequest.importKey]. */
    private fun ActivityWriteRequest.sessionClientRecordId(): String =
        importSessionClientRecordId() ?: "openvitals_activity_${startTime.toEpochMilli()}_${UUID.randomUUID()}"

    private fun ActivityWriteRequest.metricMetadata(kind: String, startTime: Instant): Metadata =
        recordMetadata(manualActivityMetricIdPrefix(kind, startTime) + (importKey ?: UUID.randomUUID()))

    /** A watch file is not a manual phone entry, and storing it as one misled every reader. */
    private fun ActivityWriteRequest.recordMetadata(clientRecordId: String): Metadata = when (source) {
        ActivityRecordSource.MANUAL_ENTRY ->
            Metadata.manualEntry(clientRecordId = clientRecordId, device = Device(type = Device.TYPE_PHONE))
        ActivityRecordSource.WATCH ->
            Metadata.activelyRecorded(device = Device(type = Device.TYPE_WATCH), clientRecordId = clientRecordId)
        ActivityRecordSource.FILE ->
            Metadata.unknownRecordingMethod(clientRecordId = clientRecordId, device = null)
    }

    private fun validateActivityWriteRequest(request: ActivityWriteRequest) {
        require(request.startTime.isBefore(request.endTime)) { "Activity start must be before end." }
        request.distanceMeters?.let {
            require(it > 0.0 && it <= ActivityWriteLimits.MaxDistanceMeters) { "Distance must be greater than 0 m." }
        }
        request.elevationGainedMeters?.let {
            require(it >= 0.0 && it <= ActivityWriteLimits.MaxElevationMeters) { "Elevation gain is out of range." }
        }
        request.activeCaloriesKcal?.let {
            require(it > 0.0 && it <= ActivityWriteLimits.MaxCaloriesKcal) { "Active calories are out of range." }
        }
        request.totalCaloriesKcal?.let {
            require(it > 0.0 && it <= ActivityWriteLimits.MaxCaloriesKcal) { "Total calories are out of range." }
        }
        request.stepsCount?.let { require(it > 0L && it <= ActivityWriteLimits.MaxSteps) { "Steps are out of range." } }
        request.floorsClimbed?.let {
            require(it > 0.0 && it <= ActivityWriteLimits.MaxFloors) { "Floors climbed are out of range." }
        }
        request.wheelchairPushes?.let {
            require(it > 0L && it <= ActivityWriteLimits.MaxWheelchairPushes) { "Wheelchair pushes are out of range." }
        }
        val sortedPauses = request.pauseIntervals.sortedBy { it.startTime }
        sortedPauses.forEach { interval ->
            require(interval.startTime.isBefore(interval.endTime)) { "Pause start must be before pause end." }
            require(!interval.startTime.isBefore(request.startTime) && !interval.endTime.isAfter(request.endTime)) {
                "Pause intervals must be inside the activity time range."
            }
        }
        sortedPauses.zipWithNext { previous, next ->
            require(!previous.endTime.isAfter(next.startTime)) { "Pause intervals must not overlap." }
        }
        require(request.routePoints.isEmpty() || request.routePoints.size >= MinRoutePointCount) {
            "Route must contain at least $MinRoutePointCount points."
        }
        request.routePoints.forEach { point ->
            require(!point.time.isBefore(request.startTime) && point.time.isBefore(request.endTime)) {
                "Route points must be inside the activity time range."
            }
        }
        val sortedSegments = request.exerciseSegments.sortedBy { it.startTime }
        sortedSegments.forEach { segment ->
            require(segment.startTime.isBefore(segment.endTime)) { "Segment start must be before segment end." }
            require(!segment.startTime.isBefore(request.startTime) && !segment.endTime.isAfter(request.endTime)) {
                "Exercise segments must be inside the activity time range."
            }
            require(segment.repetitions >= 0) { "Segment repetitions must not be negative." }
            require((segment.setIndex ?: 0) >= 0) { "Segment set index must not be negative." }
        }
        sortedSegments.zipWithNext { previous, next ->
            require(!previous.endTime.isAfter(next.startTime)) { "Exercise segments must not overlap." }
        }
        val sortedLaps = request.laps.sortedBy { it.startTime }
        sortedLaps.forEach { lap ->
            require(lap.startTime.isBefore(lap.endTime)) { "Lap start must be before lap end." }
            require(!lap.startTime.isBefore(request.startTime) && !lap.endTime.isAfter(request.endTime)) {
                "Exercise laps must be inside the activity time range."
            }
            lap.lengthMeters?.let { meters ->
                require(meters >= 0.0 && meters <= ActivityWriteLimits.MaxDistanceMeters) { "Lap length is out of range." }
            }
        }
        sortedLaps.zipWithNext { previous, next ->
            require(!previous.endTime.isAfter(next.startTime)) { "Exercise laps must not overlap." }
        }
    }

    private fun List<ExerciseRoutePoint>.toExerciseRouteOrNull(): ExerciseRoute? {
        if (isEmpty()) return null
        val route = sortedBy { it.time }.map { point ->
            ExerciseRoute.Location(
                time = point.time,
                latitude = point.latitude,
                longitude = point.longitude,
                horizontalAccuracy = point.horizontalAccuracyMeters?.meters,
                verticalAccuracy = point.verticalAccuracyMeters?.meters,
                altitude = point.altitudeMeters?.meters,
            )
        }
        return ExerciseRoute(route)
    }

    /** One kind of metric record an activity entry writes beside its session. */

    private companion object {
        private const val TAG = "ActivityRecordWriter"
        private const val MinRoutePointCount = 2
        private const val MaxRouteShrinkAttempts = 3
    }
}

internal fun ActivityWriteRequest.toExerciseSegments(): List<ExerciseSegment> {
    if (exerciseSegments.isNotEmpty()) {
        return exerciseSegments
            .sortedBy { it.startTime }
            .map { it.toExerciseSegment() }
    }

    val activeSegmentType = exerciseType.toActiveExerciseSegmentType()
    val sortedPauses = pauseIntervals.sortedBy { it.startTime }
    return buildList {
        var activeStart = startTime
        sortedPauses.forEach { pause ->
            if (activeStart.isBefore(pause.startTime)) {
                add(
                    ExerciseSegment(
                        startTime = activeStart,
                        endTime = pause.startTime,
                        segmentType = activeSegmentType,
                    )
                )
            }
            add(
                ExerciseSegment(
                    startTime = pause.startTime,
                    endTime = pause.endTime,
                    segmentType = ExerciseSegment.EXERCISE_SEGMENT_TYPE_PAUSE,
                )
            )
            if (activeStart.isBefore(pause.endTime)) {
                activeStart = pause.endTime
            }
        }
        if (activeStart.isBefore(endTime)) {
            add(
                ExerciseSegment(
                    startTime = activeStart,
                    endTime = endTime,
                    segmentType = activeSegmentType,
                )
            )
        }
    }
}

internal fun ActivityWriteRequest.toExerciseLaps(): List<ExerciseLap> =
    laps
        .sortedBy { it.startTime }
        .map { lap ->
            ExerciseLap(
                startTime = lap.startTime,
                endTime = lap.endTime,
                length = lap.lengthMeters?.meters,
            )
        }

private fun ActivityExerciseSegmentWrite.toExerciseSegment(): ExerciseSegment =
    ExerciseSegment(
        startTime = startTime,
        endTime = endTime,
        segmentType = segmentType,
        repetitions = repetitions,
        weight = weightKg?.let(Mass::kilograms),
        setIndex = setIndex,
    )

private fun Int.toActiveExerciseSegmentType(): Int =
    when (this) {
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_BIKING
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_BIKING_STATIONARY
        ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_ELLIPTICAL
        ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING ->
            ExerciseSegment.EXERCISE_SEGMENT_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING
        ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_OTHER_WORKOUT
        ExerciseSessionRecord.EXERCISE_TYPE_GYMNASTICS -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_OTHER_WORKOUT
        ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_OTHER_WORKOUT
        ExerciseSessionRecord.EXERCISE_TYPE_PILATES -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_PILATES
        ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_ROWING_MACHINE
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_RUNNING
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_RUNNING_TREADMILL
        ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_STAIR_CLIMBING
        ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_STAIR_CLIMBING_MACHINE
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_SWIMMING_OPEN_WATER
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_SWIMMING_POOL
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_WALKING
        ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_WEIGHTLIFTING
        ExerciseSessionRecord.EXERCISE_TYPE_WHEELCHAIR -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_WHEELCHAIR
        ExerciseSessionRecord.EXERCISE_TYPE_YOGA -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_YOGA
        else -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_OTHER_WORKOUT
    }
