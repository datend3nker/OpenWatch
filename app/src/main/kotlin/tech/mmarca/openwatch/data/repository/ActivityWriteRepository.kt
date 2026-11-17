package tech.mmarca.openwatch.data.repository

import android.util.Log
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.CyclingPedalingCadenceRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsCadenceRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WheelchairPushesRecord
import javax.inject.Inject
import javax.inject.Singleton
import tech.mmarca.openwatch.data.repository.contract.ActivityRepository
import tech.mmarca.openwatch.domain.model.ActivityWriteRequest
import tech.mmarca.openwatch.domain.model.BleRecordingSampleBuffer
import tech.mmarca.openwatch.domain.model.HealthConnectAvailability
import tech.mmarca.openwatch.healthconnect.ActivityRecordWriter
import tech.mmarca.openwatch.healthconnect.HealthConnectGateway

/** The permission gate in front of [ActivityRecordWriter]: a missing grant is a [SecurityException], as a screen expects. */
@Singleton
class ActivityWriteRepository(
    private val hc: HealthConnectGateway,
    private val writer: ActivityRecordWriter,
) : ActivityRepository {

    @Inject
    constructor(hc: HealthConnectGateway) : this(hc, ActivityRecordWriter(hc::reportWritten) { hc.client() })

    private val writeExercisePermission = HealthPermission.getWritePermission(ExerciseSessionRecord::class)
    private val writeDistancePermission = HealthPermission.getWritePermission(DistanceRecord::class)
    private val writeElevationPermission = HealthPermission.getWritePermission(ElevationGainedRecord::class)
    private val writeActiveCaloriesPermission = HealthPermission.getWritePermission(ActiveCaloriesBurnedRecord::class)
    private val writeTotalCaloriesPermission = HealthPermission.getWritePermission(TotalCaloriesBurnedRecord::class)
    private val writeStepsPermission = HealthPermission.getWritePermission(StepsRecord::class)
    private val writeHeartRatePermission = HealthPermission.getWritePermission(HeartRateRecord::class)
    private val writePowerPermission = HealthPermission.getWritePermission(PowerRecord::class)
    private val writeSpeedPermission = HealthPermission.getWritePermission(SpeedRecord::class)
    private val writeStepsCadencePermission = HealthPermission.getWritePermission(StepsCadenceRecord::class)
    private val writeCyclingCadencePermission = HealthPermission.getWritePermission(CyclingPedalingCadenceRecord::class)
    private val writeExerciseRoutePermission = HealthPermission.PERMISSION_WRITE_EXERCISE_ROUTE
    private val writeFloorsPermission = HealthPermission.getWritePermission(FloorsClimbedRecord::class)
    private val writeWheelchairPushesPermission = HealthPermission.getWritePermission(WheelchairPushesRecord::class)

    private suspend fun grantedPermissionsIfAvailable(): Set<String> =
        if (hc.availability() == HealthConnectAvailability.AVAILABLE) hc.grantedPermissions() else emptySet()

    override fun activityWritePermissions(request: ActivityWriteRequest): Set<String> = buildSet {
        add(writeExercisePermission)
        if (request.routePoints.isNotEmpty()) add(writeExerciseRoutePermission)
        if (request.distanceMeters != null) add(writeDistancePermission)
        if (request.elevationGainedMeters != null) add(writeElevationPermission)
        if (request.activeCaloriesKcal != null) add(writeActiveCaloriesPermission)
        if (request.totalCaloriesKcal != null) add(writeTotalCaloriesPermission)
        if (request.stepsCount != null) add(writeStepsPermission)
        addAll(request.bleSamples.writePermissions())
        // Only an import carries these; the form never asks for them.
        if (request.floorsClimbed != null) add(writeFloorsPermission)
        if (request.wheelchairPushes != null) add(writeWheelchairPushesPermission)
    }

    private fun BleRecordingSampleBuffer.writePermissions(): Set<String> = buildSet {
        if (heartRateSamples.isNotEmpty()) add(writeHeartRatePermission)
        if (powerSamples.isNotEmpty()) add(writePowerPermission)
        if (speedSamples.isNotEmpty()) add(writeSpeedPermission)
        if (cyclingCadenceSamples.isNotEmpty()) add(writeCyclingCadencePermission)
        if (stepsCadenceSamples.isNotEmpty()) add(writeStepsCadencePermission)
    }

    override suspend fun hasActivityWritePermission(request: ActivityWriteRequest): Boolean =
        activityWritePermissions(request).all { permission -> permission in grantedPermissionsIfAvailable() }

    override suspend fun writeActivityEntry(request: ActivityWriteRequest): String {
        val missingPermissions = activityWritePermissions(request) - grantedPermissionsIfAvailable()
        if (missingPermissions.isNotEmpty()) {
            Log.w(TAG, "Skipping writeActivityEntry missingCount=${missingPermissions.size}")
            throw SecurityException("Missing Health Connect activity write permission.")
        }
        return writer.writeActivityEntry(request)
    }

    override suspend fun writeActivityEntries(requests: List<ActivityWriteRequest>): List<String> {
        if (requests.isEmpty()) return emptyList()
        val granted = grantedPermissionsIfAvailable()
        val missingPermissions = requests
            .flatMapTo(mutableSetOf()) { activityWritePermissions(it) } - granted
        if (missingPermissions.isNotEmpty()) {
            Log.w(TAG, "Skipping writeActivityEntries missingCount=${missingPermissions.size}")
            throw SecurityException("Missing Health Connect activity write permission.")
        }
        return writer.writeActivityEntries(requests)
    }

    private companion object {
        const val TAG = "ActivityWriteRepository"
    }
}
