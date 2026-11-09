package tech.mmarca.openwatch.wear.health

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import tech.mmarca.openwatch.wear.MetricTile
import tech.mmarca.openwatch.wear.features.measure.Measurement
import tech.mmarca.openwatch.wear.features.recording.ActivityType
import tech.mmarca.openwatch.wear.features.recording.WorkoutField

/** The sensors that decide what the watch offers. */
data class WatchSensors(
    val heartRate: Boolean,
    /** Single beats (`TYPE_HEART_BEAT`), for the HRV measurement. */
    val heartBeat: Boolean,
    val stepCounter: Boolean,
    val barometer: Boolean,
    val gps: Boolean,
    /** A vendor sensor whose type names SpO2. */
    val vendorSpo2: Boolean,
)

/**
 * Reads the sensor list and the GPS feature, nothing else: the watch app
 * stays on AOSP APIs, without Health Services.
 */
class CapabilityProbe(private val context: Context) {

    fun probe(): WearCapabilities {
        val sensors = context.getSystemService(SensorManager::class.java)
        val has = { type: Int -> sensors?.getDefaultSensor(type) != null }
        return capabilitiesOf(
            WatchSensors(
                heartRate = has(Sensor.TYPE_HEART_RATE),
                heartBeat = has(Sensor.TYPE_HEART_BEAT),
                stepCounter = has(Sensor.TYPE_STEP_COUNTER),
                barometer = has(Sensor.TYPE_PRESSURE),
                gps = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS),
                vendorSpo2 = sensors?.getSensorList(Sensor.TYPE_ALL).orEmpty()
                    .any { it.stringType.contains("spo2", ignoreCase = true) },
            ),
        )
    }
}

/**
 * What the watch offers, from its sensors. Distance and active calories come
 * from the step counter: the phone derives them from the step minutes.
 * VO2 max and a workout's calories have no source without Health Services,
 * so they are never offered.
 */
fun capabilitiesOf(sensors: WatchSensors): WearCapabilities {
    val sensorMetrics = buildSet {
        if (sensors.stepCounter) addAll(listOf(MetricTile.STEPS, MetricTile.DISTANCE, MetricTile.ACTIVE_CALORIES))
        if (sensors.heartRate) add(MetricTile.HEART_RATE)
        if (sensors.barometer) addAll(listOf(MetricTile.FLOORS, MetricTile.ELEVATION))
        if (sensors.vendorSpo2) add(MetricTile.BLOOD_OXYGEN)
    }
    val measurements = buildSet {
        if (sensors.heartRate) add(Measurement.HEART_RATE)
        if (sensors.heartBeat) add(Measurement.HRV)
        if (sensors.barometer) add(Measurement.AIR_PRESSURE)
    }
    val workouts = ActivityType.entries
        .associateWith { workoutFields(it, sensors) }
        .filterValues { it.isNotEmpty() }
    return WearCapabilities(probed = true, sensorMetrics = sensorMetrics, measurements = measurements, workouts = workouts)
}

private fun workoutFields(activity: ActivityType, sensors: WatchSensors): Set<WorkoutField> = buildSet {
    val outdoor = activity != ActivityType.WORKOUT
    val onFoot = activity in setOf(ActivityType.WALK, ActivityType.RUN, ActivityType.HIKE)
    if (sensors.heartRate) add(WorkoutField.HEART_RATE)
    if (outdoor && sensors.gps) {
        add(WorkoutField.DISTANCE)
        add(if (activity == ActivityType.CYCLE) WorkoutField.SPEED else WorkoutField.PACE)
    }
    if (outdoor && (sensors.barometer || sensors.gps)) add(WorkoutField.ELEVATION)
    if (onFoot && sensors.stepCounter) add(WorkoutField.STEPS)
}
