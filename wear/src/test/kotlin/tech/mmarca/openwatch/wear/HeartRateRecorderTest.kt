package tech.mmarca.openwatch.wear

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [HeartRateRecorder] itself needs a real `Context`, `SensorManager` and
 * `AlarmManager`, so it is not instantiated here. This covers the one piece
 * of the power-save window that is pure: how long to wait before the next
 * window, the number [AlarmManager.setAndAllowWhileIdle] is scheduled with.
 */
class HeartRateRecorderTest {

    @Test
    fun `a window that closes immediately waits a full interval`() {
        assertEquals(600_000L, nextWindowDelayMillis(elapsedSinceOpenMillis = 0L, intervalSeconds = 600))
    }

    @Test
    fun `a window that used part of its time waits the remainder`() {
        assertEquals(595_000L, nextWindowDelayMillis(elapsedSinceOpenMillis = 5_000L, intervalSeconds = 600))
    }

    @Test
    fun `a window that overran its interval waits zero, not a negative number`() {
        assertEquals(0L, nextWindowDelayMillis(elapsedSinceOpenMillis = 700_000L, intervalSeconds = 600))
    }

    @Test
    fun `the off interval waits zero too, not a negative number`() {
        assertEquals(0L, nextWindowDelayMillis(elapsedSinceOpenMillis = 1_000L, intervalSeconds = 0))
    }
}
