package tech.mmarca.openwatch.wear.features.metric

import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test
import tech.mmarca.openwatch.wear.MetricTile
import tech.mmarca.openwatch.wear.features.quicklog.EntryType
import tech.mmarca.openwatch.wear.features.quicklog.LoggedEntry

class EntryHistoryTest {

    private val zone = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 11, 5)

    private fun at(date: LocalDate, hour: Int, minute: Int = 0) = date.atTime(hour, minute).toInstant(zone).toEpochMilli()

    @Test
    fun `the newest entries of the metric come first, today without a day`() {
        val entries = listOf(
            LoggedEntry(EntryType.HRV, at(today.minusDays(1), 22), 55.0),
            LoggedEntry(EntryType.HRV, at(today, 1, 36), 78.0),
            LoggedEntry(EntryType.HRV, at(today, 1, 50), 64.0),
            LoggedEntry(EntryType.WEIGHT, at(today, 8), 81.0),
        )

        val recent = recentEntries(entries, MetricTile.HRV, today, zone)

        assertEquals(listOf(64.0, 78.0, 55.0), recent.map { it.value })
        assertEquals(listOf("", "", "4.11."), recent.map { it.dayLabel })
    }

    @Test
    fun `at most five are listed, and a tile with no entry kind lists none`() {
        val entries = (0 until 8).map { LoggedEntry(EntryType.HRV, at(today, it), it.toDouble()) }

        assertEquals(listOf(7.0, 6.0, 5.0, 4.0, 3.0), recentEntries(entries, MetricTile.HRV, today, zone).map { it.value })
        assertEquals(emptyList<RecentEntry>(), recentEntries(entries, MetricTile.STEPS, today, zone))
    }

    @Test
    fun `the heart rate screen lists no entries, the curve of the day is its history`() {
        val entries = listOf(LoggedEntry(EntryType.HEART_RATE, at(today, 9), 61.0))

        assertEquals(emptyList<RecentEntry>(), recentEntries(entries, MetricTile.HEART_RATE, today, zone))
    }
}
