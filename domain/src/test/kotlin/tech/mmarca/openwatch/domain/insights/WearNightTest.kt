package tech.mmarca.openwatch.domain.insights

import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WearNightTest {

    private val offset = ZoneOffset.ofHours(1)
    private val night = LocalDate.of(2026, 11, 3)

    /** 22:30 the evening before the night's date. */
    private val start = night.minusDays(1).atTime(22, 30).toInstant(offset)

    @Test
    fun `the window runs from 18_00 the evening before to 14_00 on the night's date`() {
        val (from, to) = sleepNightWindow(night, offset)

        assertEquals(night.minusDays(1).atTime(18, 0).toInstant(offset), from)
        assertEquals(night.atTime(14, 0).toInstant(offset), to)
    }

    @Test
    fun `minutes outside the window are not part of the night`() {
        val (from, to) = sleepNightWindow(night, offset)
        val rows = WearSleepFixtures.minutes(from.minusSeconds(120), 4) { _, time -> WearSleepFixtures.table(time) } +
            WearSleepFixtures.minutes(to.minusSeconds(120), 4) { _, time -> WearSleepFixtures.table(time) }

        val kept = WearNight.minutesOf(rows, night, offset)

        // Two before the start and two from the end are dropped; the end itself is outside.
        assertEquals(listOf(from, from.plusSeconds(60), to.minusSeconds(120), to.minusSeconds(60)), kept.map { it.time })
    }

    @Test
    fun `a finished night has its session and its resting heart rate`() {
        val rows = WearSleepFixtures.wornNight(start)

        val estimated = WearNight.estimate(rows, night, offset)

        assertNotNull(estimated)
        assertEquals(WearSleepEstimator.estimate(rows), estimated)
        val bpm = WearNight.restingHeartRate(rows, estimated!!)
        // The fixture's pulse dips to about 48 every 90 minutes.
        assertTrue("bpm $bpm", bpm != null && bpm in 45..55)
    }

    @Test
    fun `a night still running has no resting heart rate`() {
        // Sleep up to the last minute: the estimator cannot know the night has ended.
        val rows = WearSleepFixtures.minutes(start, 420) { index, time ->
            if (index < 30) {
                WearSleepFixtures.worn(time, movement = 8f, heartRate = 64f)
            } else {
                WearSleepFixtures.worn(time, movement = 0f, heartRate = WearSleepFixtures.nightHeartRate(index))
            }
        }

        val estimated = WearNight.estimate(rows, night, offset)

        assertNotNull(estimated)
        assertTrue(estimated!!.session.inProgress)
        assertNull(WearNight.restingHeartRate(rows, estimated))
    }

    @Test
    fun `the reasons repeat the numbers of the estimated night`() {
        val estimated = WearNight.estimate(WearSleepFixtures.wornNight(start), night, offset)!!

        val reasons = WearNight.reasons(estimated).toMap()

        assertEquals(estimated.session.onset.toEpochMilli(), reasons["onset"])
        assertEquals(estimated.session.end.toEpochMilli(), reasons["end"])
        assertEquals(estimated.session.sleepMinutes, reasons["sleepMin"])
        assertEquals(estimated.window.length, reasons["windowMin"])
        assertEquals(estimated.window.source, reasons["windowSource"])
        assertEquals(estimated.wornMinutes, reasons["wornMin"])
    }
}
