package tech.mmarca.openwatch.devices.wearos

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.WeightRecord
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import tech.mmarca.openwatch.domain.insights.WorkoutEnergyResult
import tech.mmarca.openwatch.domain.preferences.BodyProfile
import tech.mmarca.openwatch.healthconnect.HealthConnectGateway

/**
 * Writes a watch workout's active calories ([WearOsWorkoutImport.activeCalories])
 * once the session is written. The heart rate is the watch's own, which the
 * same sync wrote to Health Connect before it pulled the workouts. Optional like
 * the walking energy: a missing grant or a failed read never fails the sync.
 */
internal class WearOsWorkoutEnergy(
    private val healthConnect: HealthConnectGateway,
    private val profile: suspend () -> BodyProfile,
    private val insert: suspend (List<Record>) -> Unit,
    private val clock: () -> Instant,
    private val zone: () -> ZoneId,
) {

    sealed interface Outcome {
        data object Written : Outcome
        /** Nothing to write: no sourced cost for the activity, or a read failed. */
        data object Skipped : Outcome
        data object PermissionMissing : Outcome
        /** The profile has no weight; [weightReadWithheld] when Health Connect's was out of reach. */
        data class NoWeight(val weightReadWithheld: Boolean) : Outcome
    }

    suspend fun write(workout: WearOsWorkout): Outcome {
        val granted = healthConnect.grantedPermissions()
        if (WearOsWorkoutImport.energyPermission !in granted) {
            WearOsLog.log("workout energy: not written, Health Connect permission missing")
            return Outcome.PermissionMissing
        }
        val body = try {
            profile()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            WearOsLog.log("workout energy: could not read the body profile: $error")
            return Outcome.Skipped
        }
        val (record, result) = WearOsWorkoutImport.activeCalories(
            workout = workout,
            zone = zone(),
            profile = body,
            samples = heartRate(workout),
            today = clock().atZone(zone()).toLocalDate(),
        )
        if (result is WorkoutEnergyResult.MissingInputs) {
            WearOsLog.log("workout energy: no body weight, so no calories")
            return Outcome.NoWeight(weightReadWithheld = WEIGHT_READ_PERMISSION !in granted)
        }
        record ?: return Outcome.Skipped
        insert(listOf(record))
        return Outcome.Written
    }

    /** The heart rate in the workout's span; empty when it could not be read, and the activity's MET is used. */
    private suspend fun heartRate(workout: WearOsWorkout): List<Pair<Instant, Long>> = try {
        val samples = ArrayList<Pair<Instant, Long>>()
        healthConnect.forEachSyncRecordPage(HeartRateRecord::class, workout.start, workout.end) { page ->
            page.filterIsInstance<HeartRateRecord>().forEach { record ->
                record.samples.mapTo(samples) { it.time to it.beatsPerMinute }
            }
        }
        samples
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        WearOsLog.log("workout energy: could not read heart rate, using the activity's MET: $error")
        emptyList()
    }

    private companion object {
        val WEIGHT_READ_PERMISSION = HealthPermission.getReadPermission(WeightRecord::class)
    }
}
