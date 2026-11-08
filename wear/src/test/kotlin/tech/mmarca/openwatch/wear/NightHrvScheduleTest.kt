package tech.mmarca.openwatch.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.mmarca.openwatch.wearlink.WearLinkProtocol
import tech.mmarca.openwatch.wearlink.WearLinkProtocol.MinuteKind
import tech.mmarca.openwatch.wearlink.WearLinkProtocol.SleepMinute

class NightHrvScheduleTest {

    private val start = 1_791_568_800_000L

    private fun minute(index: Int, movement10: Int = 0, flags: Int = 0, kind: MinuteKind = MinuteKind.RAW) = SleepMinute(
        epochMillis = start + index * 60_000L,
        kind = kind,
        offsetSeconds = 3600,
        flags = flags,
        sampleCount = 300,
        movement10 = movement10,
        bpm = 55,
        heartRateSd10 = 10,
        heartRateSamples = 6,
        meanMilliG = intArrayOf(0, 0, 1000),
        sdMilliG = intArrayOf(1, 1, 1),
        zAngleMin = 80,
        zAngleMax = 80,
        zAngleDelta10 = 0,
    )

    private fun still(count: Int) = List(count) { minute(it) }

    /** Just after the minute [index] closed. */
    private fun at(index: Int) = start + index * 60_000L + 150_000L

    @Test
    fun `a window may start after ten still minutes`() {
        assertTrue(NightHrvSchedule.mayStart(still(10), at(9), nextAllowedMillis = 0))
    }

    @Test
    fun `nine still minutes are not enough`() {
        assertFalse(NightHrvSchedule.mayStart(still(9), at(8), 0))
    }

    @Test
    fun `any movement, a charger, the screen or the wrist off in the last ten minutes holds it back`() {
        for (spoiler in listOf(minute(5, movement10 = 10), minute(5, flags = WearLinkProtocol.FLAG_CHARGING), minute(5, flags = WearLinkProtocol.FLAG_SCREEN_ON), minute(5, flags = WearLinkProtocol.FLAG_OFF_BODY), minute(5, kind = MinuteKind.AWAKE))) {
            val rows = still(10).toMutableList().also { it[5] = spoiler }
            assertFalse(NightHrvSchedule.mayStart(rows, at(9), 0))
        }
    }

    @Test
    fun `a missing minute breaks the run`() {
        val rows = still(11).filterIndexed { index, _ -> index != 6 }
        assertFalse(NightHrvSchedule.mayStart(rows, at(10), 0))
    }

    @Test
    fun `stale rows do not start a window`() {
        assertFalse(NightHrvSchedule.mayStart(still(10), at(9) + 10 * 60_000L, 0))
    }

    @Test
    fun `the gap since the last window is kept, shorter after a spoiled one`() {
        val last = at(9)
        val rows = still(40)
        assertFalse(NightHrvSchedule.mayStart(rows, last + 29 * 60_000L, NightHrvSchedule.nextStartAfter(last, spoiled = false)))
        assertTrue(NightHrvSchedule.mayStart(rows, at(39), NightHrvSchedule.nextStartAfter(last, spoiled = false)))
        assertEquals(last + 10 * 60_000L, NightHrvSchedule.nextStartAfter(last, spoiled = true))
        assertTrue(NightHrvSchedule.mayStart(rows, at(25), NightHrvSchedule.nextStartAfter(last, spoiled = true)))
    }

    @Test
    fun `a window is still when each minute it touches is recorded and calm`() {
        val rows = still(12)
        assertTrue(NightHrvSchedule.wasStill(rows, start + 30_000, start + 5 * 60_000 + 30_000))
    }

    @Test
    fun `a window is not still with a restless or a missing minute`() {
        val from = start + 30_000
        val to = start + 5 * 60_000 + 30_000
        val restless = still(12).toMutableList().also { it[3] = minute(3, movement10 = 40) }
        assertFalse(NightHrvSchedule.wasStill(restless, from, to))
        assertFalse(NightHrvSchedule.wasStill(still(12).filterIndexed { i, _ -> i != 2 }, from, to))
        // A slight stir within the allowance is accepted.
        val slight = still(12).toMutableList().also { it[3] = minute(3, movement10 = 8) }
        assertTrue(NightHrvSchedule.wasStill(slight, from, to))
    }

    @Test
    fun `the rows cover a window once the minute it ended in is there`() {
        val end = start + 5 * 60_000 + 30_000
        assertFalse(NightHrvSchedule.covers(still(5), end))
        assertTrue(NightHrvSchedule.covers(still(6), end))
    }
}
