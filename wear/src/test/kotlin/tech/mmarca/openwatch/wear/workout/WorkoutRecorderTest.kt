package tech.mmarca.openwatch.wear.workout

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.mmarca.openwatch.wearlink.WorkoutSport

/** [WorkoutRecorder] needs a real `Context`; this covers the one pure piece, which sports keep the processor awake. */
class WorkoutRecorderTest {

    @Test
    fun `a sport that records the heart rate alone lets the processor sleep`() {
        assertFalse(WorkoutRecorder.holdsProcessor(WorkoutSport.VOLLEYBALL, usesGps = false))
        assertFalse(WorkoutRecorder.holdsProcessor(WorkoutSport.STRENGTH_TRAINING, usesGps = false))
    }

    @Test
    fun `the GPS keeps the processor awake`() {
        assertTrue(WorkoutRecorder.holdsProcessor(WorkoutSport.BIKING, usesGps = true))
    }

    @Test
    fun `a step counter or barometer of its own keeps it awake without the GPS`() {
        assertTrue(WorkoutRecorder.holdsProcessor(WorkoutSport.TREADMILL, usesGps = false))
        assertTrue(WorkoutRecorder.holdsProcessor(WorkoutSport.STAIR_CLIMBING, usesGps = false))
        // A route sport whose wearer declined location still counts steps.
        assertTrue(WorkoutRecorder.holdsProcessor(WorkoutSport.RUNNING, usesGps = false))
    }
}
