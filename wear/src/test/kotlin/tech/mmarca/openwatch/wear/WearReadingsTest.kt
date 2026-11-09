package tech.mmarca.openwatch.wear

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.mmarca.openwatch.wearlink.WearLinkProtocol.AscentMinute
import tech.mmarca.openwatch.wearlink.WearLinkProtocol.HeartRateSample
import tech.mmarca.openwatch.wearlink.WearLinkProtocol.StepMinute

class WearReadingsTest {

    private val zone = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 11, 2)
    private val now = Instant.parse("2026-11-02T10:30:00Z")

    private fun at(date: LocalDate, hour: Int, minute: Int = 0): Long =
        date.atTime(hour, minute).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun day(
        date: LocalDate,
        heartRate: List<HeartRateSample> = emptyList(),
        steps: List<StepMinute> = emptyList(),
        ascent: List<AscentMinute> = emptyList(),
    ) = WearReadings.day(date, zone, heartRate, steps, ascent)

    /** Seven days, today last; [today] carries the rows, the earlier ones are empty unless given. */
    private fun week(todayReadings: DayReadings, earlier: Map<Int, DayReadings> = emptyMap()): List<DayReadings> =
        (6 downTo 1).map { back -> earlier[back] ?: day(today.minusDays(back.toLong())) } + todayReadings

    @Test
    fun stepsAddUpPerHourAndPerDay() {
        val readings = week(
            day(today, steps = listOf(StepMinute(at(today, 8, 0), 40), StepMinute(at(today, 8, 1), 60), StepMinute(at(today, 10, 5), 25))),
            earlier = mapOf(1 to day(today.minusDays(1), steps = listOf(StepMinute(at(today.minusDays(1), 12), 500)))),
        )

        val steps = WearReadings.metrics(readings, now, zone).getValue(MetricTile.STEPS)

        assertEquals(125.0, steps.current!!, 0.0)
        assertEquals(40.0 + 60.0, steps.today[8], 0.0)
        assertEquals(25.0, steps.today[10], 0.0)
        assertEquals(listOf(0.0, 0.0, 0.0, 0.0, 0.0, 500.0, 125.0), steps.week)
    }

    @Test
    fun todayStopsAtTheCurrentHour() {
        val readings = week(day(today, steps = listOf(StepMinute(at(today, 3), 10))))

        val steps = WearReadings.metrics(readings, now, zone).getValue(MetricTile.STEPS)

        assertEquals(11, steps.today.size)
    }

    @Test
    fun aMetricWithNoRowInTheWeekIsLeftOut() {
        val metrics = WearReadings.metrics(week(day(today)), now, zone)

        assertTrue(metrics.isEmpty())
    }

    @Test
    fun aQuietTodayStillShowsZeroWhenAnEarlierDayHadSteps() {
        val readings = week(
            day(today),
            earlier = mapOf(2 to day(today.minusDays(2), steps = listOf(StepMinute(at(today.minusDays(2), 9), 300)))),
        )

        assertEquals(0.0, WearReadings.metrics(readings, now, zone).getValue(MetricTile.STEPS).current!!, 0.0)
    }

    @Test
    fun heightCountsOnlyWithStepsAroundIt() {
        val climbed = AscentMinute(at(today, 9, 10), 60) // 6 m
        val walkedNothing = week(day(today, ascent = listOf(climbed)))
        val walked = week(
            day(today, ascent = listOf(climbed), steps = listOf(StepMinute(at(today, 9, 10), 20), StepMinute(at(today, 9, 9), 20))),
        )

        val without = WearReadings.metrics(walkedNothing, now, zone).getValue(MetricTile.ELEVATION)
        val with = WearReadings.metrics(walked, now, zone).getValue(MetricTile.ELEVATION)

        assertEquals(0.0, without.current!!, 0.0)
        assertEquals(6.0, with.current!!, 1e-9) // 40 steps carry 12 m, the 6 m climbed all count
    }

    @Test
    fun floorsAreCreditedMetresOverThreeMetresPerFloor() {
        val readings = week(
            day(today, ascent = listOf(AscentMinute(at(today, 9, 10), 90)), steps = listOf(StepMinute(at(today, 9, 10), 60))),
        )

        val floors = WearReadings.metrics(readings, now, zone).getValue(MetricTile.FLOORS)

        assertEquals(3.0, floors.current!!, 1e-9) // 9 m climbed, 60 steps carry 18 m
    }

    @Test
    fun heartRateShowsTheLatestSampleAndAFiveMinuteLine() {
        val samples = listOf(
            HeartRateSample(at(today, 9, 0), 60),
            HeartRateSample(at(today, 9, 2), 70),
            HeartRateSample(at(today, 9, 7), 90),
        )

        val heartRate = WearReadings.metrics(week(day(today, heartRate = samples)), now, zone).getValue(MetricTile.HEART_RATE)

        assertEquals(90.0, heartRate.current!!, 0.0)
        assertEquals(listOf(65.0, 90.0), heartRate.today)
    }

    @Test
    fun heartRateWeekRunsFromTheFirstDayWithSamplesAndKeepsGapsAsZero() {
        val readings = week(
            day(today, heartRate = listOf(HeartRateSample(at(today, 8), 80))),
            earlier = mapOf(3 to day(today.minusDays(3), heartRate = listOf(HeartRateSample(at(today.minusDays(3), 8), 60), HeartRateSample(at(today.minusDays(3), 9), 70)))),
        )

        val heartRate = WearReadings.metrics(readings, now, zone).getValue(MetricTile.HEART_RATE)

        // Four bars: three days ago, two empty days, today. The chart labels the last four days.
        assertEquals(listOf(65.0, 0.0, 0.0, 80.0), heartRate.week)
    }

    @Test
    fun heartRateKeepsTheLastSampleWhenTodayHasNone() {
        val readings = week(
            day(today),
            earlier = mapOf(1 to day(today.minusDays(1), heartRate = listOf(HeartRateSample(at(today.minusDays(1), 23, 50), 58)))),
        )

        val heartRate = WearReadings.metrics(readings, now, zone).getValue(MetricTile.HEART_RATE)

        assertEquals(58.0, heartRate.current!!, 0.0)
        assertTrue(heartRate.today.isEmpty())
    }

    @Test
    fun noDaysGiveNoTiles() {
        assertTrue(WearReadings.metrics(emptyList(), now, zone).isEmpty())
        assertNull(WearReadings.metrics(week(day(today)), now, zone)[MetricTile.HEART_RATE])
        assertFalse(WearReadings.metrics(week(day(today)), now, zone).containsKey(MetricTile.STEPS))
    }
}
