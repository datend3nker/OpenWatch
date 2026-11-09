package tech.mmarca.openwatch.devices.wearos

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.ExerciseLap
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt
import tech.mmarca.openwatch.wearlink.WearLinkWorkout
import tech.mmarca.openwatch.wearlink.WorkoutMetrics

/**
 * What a workout's [WorkoutMetrics.Summary] adds in Health Connect: the
 * kilometre splits as the session's laps, a line of the session's notes
 * with the heart rate figures, the energy above rest (or, without a height
 * for the resting rate, the whole energy), and a VO2max estimate. The extra
 * records are optional: each needs its own grant, and a missing one leaves
 * only that record out. Ids follow the workout's start, so a re-pull upserts.
 *
 * Pure, so it is testable without a device.
 */
object WearOsWorkoutMetrics {

    const val ENERGY_PREFIX = "wearos_workout_energy_"
    const val VO2_PREFIX = "wearos_workout_vo2_"

    val activeEnergyPermission: String = HealthPermission.getWritePermission(ActiveCaloriesBurnedRecord::class)
    val totalEnergyPermission: String = HealthPermission.getWritePermission(TotalCaloriesBurnedRecord::class)
    val vo2Permission: String = HealthPermission.getWritePermission(Vo2MaxRecord::class)

    /** The workout as the shared metrics read it. */
    fun linkWorkout(workout: WearOsWorkout): WearLinkWorkout = WearLinkWorkout(
        startMillis = workout.start.toEpochMilli(),
        endMillis = workout.end.toEpochMilli(),
        exerciseType = workout.exerciseType,
        steps = workout.steps,
        distanceMetres = workout.distanceMetres,
        ascentDecimetres = workout.ascentMetres?.let { (it * 10).roundToInt() },
        pauses = workout.pauses.map { WearLinkWorkout.Pause(it.start.toEpochMilli(), it.endInclusive.toEpochMilli()) },
        route = workout.route.map {
            WearLinkWorkout.RoutePoint(
                it.time.toEpochMilli(),
                (it.latitude * 1e7).roundToInt(),
                (it.longitude * 1e7).roundToInt(),
                null,
                null,
            )
        },
    )

    /** The splits as laps, each inside the session as Health Connect requires. */
    fun laps(workout: WearOsWorkout, summary: WorkoutMetrics.Summary): List<ExerciseLap> = summary.splits
        .map { ExerciseLap(Instant.ofEpochMilli(it.startMillis), Instant.ofEpochMilli(it.endMillis), androidx.health.connect.client.units.Length.meters(1000.0)) }
        .filter { !it.startTime.isBefore(workout.start) && !it.endTime.isAfter(workout.end) && it.startTime.isBefore(it.endTime) }

    /** One line of plain figures for the session's notes; null with nothing to say. */
    fun notes(summary: WorkoutMetrics.Summary, recoveryBpm: Int? = null): String? {
        val parts = buildList {
            if (summary.averageBpm != null && summary.maxBpm != null) add("Heart rate avg ${summary.averageBpm}, max ${summary.maxBpm} bpm")
            if (summary.zoneSeconds.any { it > 0 }) add("Zones (min) " + summary.zoneSeconds.joinToString("/") { (it / 60).toString() })
            summary.trimp?.let { add("TRIMP ${it.roundToInt()}") }
            recoveryBpm?.let { add("Recovery 1 min $it bpm") }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(". ")
    }

    /** The energy and VO2max records [granted] allows. */
    fun records(workout: WearOsWorkout, summary: WorkoutMetrics.Summary, zone: ZoneId, granted: Set<String>): List<Record> {
        val startOffset = zone.rules.getOffset(workout.start)
        val endOffset = zone.rules.getOffset(workout.end)
        val key = workout.start.toEpochMilli()
        val energy = when {
            summary.activeKcal != null && activeEnergyPermission in granted -> ActiveCaloriesBurnedRecord(
                startTime = workout.start, startZoneOffset = startOffset, endTime = workout.end, endZoneOffset = endOffset,
                energy = Energy.kilocalories(summary.activeKcal!!), metadata = metadata("$ENERGY_PREFIX$key"),
            )
            summary.activeKcal == null && summary.totalKcal != null && totalEnergyPermission in granted -> TotalCaloriesBurnedRecord(
                startTime = workout.start, startZoneOffset = startOffset, endTime = workout.end, endZoneOffset = endOffset,
                energy = Energy.kilocalories(summary.totalKcal!!), metadata = metadata("$ENERGY_PREFIX$key"),
            )
            else -> null
        }
        val vo2 = summary.vo2Max?.takeIf { vo2Permission in granted && it in MIN_VO2..MAX_VO2 }?.let {
            Vo2MaxRecord(
                time = workout.end, zoneOffset = endOffset, vo2MillilitersPerMinuteKilogram = it,
                measurementMethod = Vo2MaxRecord.MEASUREMENT_METHOD_OTHER, metadata = metadata("$VO2_PREFIX$key"),
            )
        }
        return listOfNotNull(energy, vo2)
    }

    private fun metadata(id: String) = Metadata.autoRecorded(device = Device(type = Device.TYPE_WATCH), clientRecordId = id)

    /** Health Connect's own range for VO2max. */
    private const val MIN_VO2 = 0.0
    private const val MAX_VO2 = 100.0
}
