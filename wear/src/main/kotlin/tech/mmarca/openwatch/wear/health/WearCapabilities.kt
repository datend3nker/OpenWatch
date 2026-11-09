package tech.mmarca.openwatch.wear.health

import tech.mmarca.openwatch.wear.MetricSource
import tech.mmarca.openwatch.wear.MetricTile
import tech.mmarca.openwatch.wear.features.measure.Measurement
import tech.mmarca.openwatch.wear.features.recording.ActivityType
import tech.mmarca.openwatch.wear.features.recording.WorkoutField

/**
 * What this watch can actually measure, read from its sensor list. Nothing the UI offers as a sensor reading, a measurement
 * or a workout should go beyond this.
 */
data class WearCapabilities(
    /** False until the probe has answered; the UI then offers nothing it would have to take back. */
    val probed: Boolean = false,
    /** Metrics the watch records by itself. Phone and logged metrics are always on offer. */
    val sensorMetrics: Set<MetricTile> = emptySet(),
    /** Measurements a button can start right now. */
    val measurements: Set<Measurement> = emptySet(),
    /** Workouts its sensors support, with the fields each one reports. */
    val workouts: Map<ActivityType, Set<WorkoutField>> = emptyMap(),
) {
    /** The metrics to offer as tiles: every phone and logged metric, sensor metrics if measured here. */
    val availableMetrics: Set<MetricTile>
        get() = MetricTile.entries.filterTo(mutableSetOf()) {
            it.source != MetricSource.SENSOR || it in sensorMetrics
        }
}
