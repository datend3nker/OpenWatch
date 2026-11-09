package tech.mmarca.openwatch.wear

import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tech.mmarca.openwatch.wearlink.WearLinkProtocol.HeartRateSample
import tech.mmarca.openwatch.wearlink.WearLinkProtocol.StepMinute

class TodayTotalsTest {

    private val today = LocalDate.of(2026, 11, 2)
    private val zone = ZoneOffset.UTC

    @Test
    fun stepsAddUpAndTheLatestHeartRateWins() {
        val day = WearReadings.day(
            today,
            zone,
            heartRate = listOf(HeartRateSample(1_000, 60), HeartRateSample(9_000, 75)),
            steps = listOf(StepMinute(0, 40), StepMinute(60_000, 60)),
            ascent = emptyList(),
        )

        assertEquals(TodayTotals(steps = 100, heartRate = 75), WearReadings.totals(day))
    }

    @Test
    fun nothingRecordedIsNull() {
        val day = WearReadings.day(today, zone, emptyList(), emptyList(), emptyList())

        assertEquals(TodayTotals(steps = null, heartRate = null), WearReadings.totals(day))
        assertNull(WearReadings.totals(day).steps)
    }
}
