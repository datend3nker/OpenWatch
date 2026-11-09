package tech.mmarca.openwatch.features.watches

import java.time.LocalTime
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchAutoSyncQuietHoursTest {

    @Test
    fun `the night from 23 to 7 is quiet, the day is not`() {
        assertTrue(WatchAutoSyncQuietHours.isQuiet(LocalTime.of(23, 0)))
        assertTrue(WatchAutoSyncQuietHours.isQuiet(LocalTime.of(3, 30)))
        assertTrue(WatchAutoSyncQuietHours.isQuiet(LocalTime.of(6, 59)))
        assertFalse(WatchAutoSyncQuietHours.isQuiet(LocalTime.of(7, 0)))
        assertFalse(WatchAutoSyncQuietHours.isQuiet(LocalTime.of(22, 59)))
    }
}
