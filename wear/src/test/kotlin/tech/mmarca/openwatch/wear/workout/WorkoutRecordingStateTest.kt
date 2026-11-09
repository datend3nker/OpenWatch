package tech.mmarca.openwatch.wear.workout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.mmarca.openwatch.wear.features.recording.ActivityType
import tech.mmarca.openwatch.wear.features.recording.WorkoutField
import tech.mmarca.openwatch.wearlink.WorkoutSport

class WorkoutRecordingStateTest {

    private val snapshot = WorkoutSnapshot(
        activeSeconds = 600, paused = true, distanceMetres = 1500, speedMetresPerSecond = null,
        averageSpeedMetresPerSecond = 2.5, steps = 1800, cadenceStepsPerMinute = 170, ascentMetres = 12,
        floors = null, heartRateBpm = 140, averageHeartRateBpm = 132, maxHeartRateBpm = 160, waitingForFix = false,
    )

    @Test
    fun `the recorder's snapshot fills the workout screen`() {
        val state = snapshot.toRecordingState(WorkoutSport.RUNNING, title = 42)
        assertEquals(ActivityType.RUN, state.activityType)
        assertEquals(42, state.title)
        assertEquals(600L, state.elapsedSeconds)
        assertEquals(1500, state.distanceMeters)
        // Without a recent fix the screen shows the average speed.
        assertEquals(2.5, state.speedMetersPerSecond!!, 0.0)
        assertTrue(state.paused)
    }

    @Test
    fun `a route sport shows distance and pace only with the location grant`() {
        assertTrue(WorkoutField.PACE in workoutFieldsOf(WorkoutSport.RUNNING, gpsGranted = true))
        assertFalse(WorkoutField.DISTANCE in workoutFieldsOf(WorkoutSport.RUNNING, gpsGranted = false))
        assertTrue(WorkoutField.SPEED in workoutFieldsOf(WorkoutSport.BIKING, gpsGranted = true))
        assertEquals(setOf(WorkoutField.HEART_RATE), workoutFieldsOf(WorkoutSport.YOGA, gpsGranted = true))
    }
}
