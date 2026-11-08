package tech.mmarca.openwatch.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.mmarca.openwatch.wearlink.HeartRateInterval
import tech.mmarca.openwatch.wearlink.HeartRateInterval.EVERY_10_SECONDS
import tech.mmarca.openwatch.wearlink.HeartRateInterval.POWER_SAVE
import tech.mmarca.openwatch.wearlink.HeartRateInterval.OFF

class HeartRateScheduleTest {

    @Test
    fun `tracking off means no measurement at any time of day`() {
        for (bedtime in listOf(false, true)) {
            for (sleep in listOf(false, true)) {
                assertEquals(
                    OFF,
                    HeartRateSchedule.interval(heartRate = false, sleepRecording = sleep, bedtime = bedtime, day = POWER_SAVE, night = EVERY_10_SECONDS),
                )
            }
        }
    }

    @Test
    fun `a workout records at the full rate even with tracking off`() {
        assertEquals(
            EVERY_10_SECONDS,
            HeartRateSchedule.interval(heartRate = false, sleepRecording = false, bedtime = false, day = OFF, night = OFF, workout = true),
        )
    }

    @Test
    fun `by day the day setting applies`() {
        assertEquals(
            POWER_SAVE,
            HeartRateSchedule.interval(heartRate = true, sleepRecording = true, bedtime = false, day = POWER_SAVE, night = OFF),
        )
        assertEquals(
            OFF,
            HeartRateSchedule.interval(heartRate = true, sleepRecording = false, bedtime = false, day = OFF, night = POWER_SAVE),
        )
    }

    @Test
    fun `in bedtime mode with sleep recording on the full rate is kept, whatever the night setting says`() {
        for (night in HeartRateInterval.entries) {
            assertEquals(
                EVERY_10_SECONDS,
                HeartRateSchedule.interval(heartRate = true, sleepRecording = true, bedtime = true, day = POWER_SAVE, night = night),
            )
        }
    }

    @Test
    fun `in bedtime mode with sleep recording off the night setting applies`() {
        assertEquals(
            POWER_SAVE,
            HeartRateSchedule.interval(heartRate = true, sleepRecording = false, bedtime = true, day = EVERY_10_SECONDS, night = POWER_SAVE),
        )
        assertEquals(
            OFF,
            HeartRateSchedule.interval(heartRate = true, sleepRecording = false, bedtime = true, day = EVERY_10_SECONDS, night = OFF),
        )
    }

    @Test
    fun `the full rate is the ten second interval, the one the sleep pipeline reads`() {
        assertEquals(EVERY_10_SECONDS, HeartRateSchedule.FULL_RATE)
    }

    @Test
    fun `the heart rate capability is offered while tracking is on and something can use the sensor`() {
        assertTrue(HeartRateSchedule.offered(heartRate = true, sleepRecording = true, day = OFF, night = OFF))
        assertTrue(HeartRateSchedule.offered(heartRate = true, sleepRecording = false, day = POWER_SAVE, night = OFF))
        assertTrue(HeartRateSchedule.offered(heartRate = true, sleepRecording = false, day = OFF, night = EVERY_10_SECONDS))
    }

    @Test
    fun `the heart rate capability is withdrawn with tracking off, or with nothing left to measure`() {
        assertFalse(HeartRateSchedule.offered(heartRate = false, sleepRecording = true, day = EVERY_10_SECONDS, night = EVERY_10_SECONDS))
        assertFalse(HeartRateSchedule.offered(heartRate = true, sleepRecording = false, day = OFF, night = OFF))
    }
}
