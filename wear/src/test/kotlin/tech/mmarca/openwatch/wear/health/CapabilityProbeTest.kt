package tech.mmarca.openwatch.wear.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.mmarca.openwatch.wear.MetricSource
import tech.mmarca.openwatch.wear.MetricTile
import tech.mmarca.openwatch.wear.features.measure.Measurement
import tech.mmarca.openwatch.wear.features.recording.ActivityType
import tech.mmarca.openwatch.wear.features.recording.WorkoutField

class CapabilityProbeTest {

    private val everything = WatchSensors(
        heartRate = true,
        heartBeat = true,
        stepCounter = true,
        barometer = true,
        gps = true,
        vendorSpo2 = true,
    )
    private val nothing = WatchSensors(
        heartRate = false,
        heartBeat = false,
        stepCounter = false,
        barometer = false,
        gps = false,
        vendorSpo2 = false,
    )

    @Test
    fun `a full watch offers every sensor metric except VO2 max`() {
        val capabilities = capabilitiesOf(everything)
        assertTrue(capabilities.probed)
        val sensorTiles = MetricTile.entries.filter { it.source == MetricSource.SENSOR }.toSet()
        assertEquals(sensorTiles - MetricTile.VO2_MAX, capabilities.sensorMetrics)
        assertEquals(Measurement.entries.toSet(), capabilities.measurements)
    }

    @Test
    fun `a bare watch offers no sensor metric, measurement or workout`() {
        val capabilities = capabilitiesOf(nothing)
        assertTrue(capabilities.sensorMetrics.isEmpty())
        assertTrue(capabilities.measurements.isEmpty())
        assertTrue(capabilities.workouts.isEmpty())
        // Phone and logged metrics stay on offer.
        assertTrue(MetricTile.SLEEP in capabilities.availableMetrics())
    }

    @Test
    fun `workout fields follow the activity and the sensors`() {
        val workouts = capabilitiesOf(everything).workouts
        assertEquals(
            setOf(WorkoutField.HEART_RATE, WorkoutField.DISTANCE, WorkoutField.PACE, WorkoutField.ELEVATION, WorkoutField.STEPS),
            workouts[ActivityType.RUN],
        )
        assertEquals(
            setOf(WorkoutField.HEART_RATE, WorkoutField.DISTANCE, WorkoutField.SPEED, WorkoutField.ELEVATION),
            workouts[ActivityType.CYCLE],
        )
        // Indoors: no position, no height.
        assertEquals(setOf(WorkoutField.HEART_RATE), workouts[ActivityType.WORKOUT])
        assertFalse(workouts.values.any { WorkoutField.CALORIES in it })
    }

    @Test
    fun `without GPS a run still has heart rate, steps and the barometer's height`() {
        val run = capabilitiesOf(everything.copy(gps = false)).workouts[ActivityType.RUN]
        assertEquals(setOf(WorkoutField.HEART_RATE, WorkoutField.ELEVATION, WorkoutField.STEPS), run)
    }

    @Test
    fun `a night metric is offered once the watch holds a reading`() {
        val capabilities = capabilitiesOf(nothing)
        assertTrue(MetricTile.SKIN_TEMPERATURE !in capabilities.availableMetrics())
        assertTrue(MetricTile.SKIN_TEMPERATURE in capabilities.availableMetrics(setOf(MetricTile.SKIN_TEMPERATURE)))
        assertTrue(MetricTile.BLOOD_OXYGEN in capabilities.availableMetrics(setOf(MetricTile.BLOOD_OXYGEN)))
    }

    @Test
    fun `HRV is offered with single beats or with the raw pulse signal, not with neither`() {
        val noBeats = everything.copy(heartBeat = false)
        assertTrue(Measurement.HRV in capabilitiesOf(noBeats.copy(rawPpg = true)).measurements)
        assertTrue(Measurement.HRV in capabilitiesOf(noBeats.copy(heartBeat = true)).measurements)
        assertFalse(Measurement.HRV in capabilitiesOf(noBeats).measurements)
    }
}
