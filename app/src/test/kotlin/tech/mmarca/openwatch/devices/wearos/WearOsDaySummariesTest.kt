package tech.mmarca.openwatch.devices.wearos

import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.metadata.Metadata
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WearOsDaySummariesTest {

    private val zone = ZoneOffset.UTC
    private val day = LocalDate.of(2026, 11, 9)

    private fun at(text: String) = Instant.parse(text)

    private fun stage(from: String, to: String, type: Int) = SleepSessionRecord.Stage(at(from), at(to), type)

    private fun session(from: String, to: String, stages: List<SleepSessionRecord.Stage> = emptyList()) = SleepSessionRecord(
        startTime = at(from), startZoneOffset = zone, endTime = at(to), endZoneOffset = zone,
        stages = stages, metadata = Metadata.manualEntry(),
    )

    @Test
    fun `the longest night ending on the day gives its sleep without awake stages`() {
        val night = session(
            "2026-11-08T23:00:00Z", "2026-11-09T06:00:00Z",
            listOf(
                stage("2026-11-08T23:00:00Z", "2026-11-08T23:20:00Z", SleepSessionRecord.STAGE_TYPE_AWAKE),
                stage("2026-11-08T23:20:00Z", "2026-11-09T02:00:00Z", SleepSessionRecord.STAGE_TYPE_LIGHT),
                stage("2026-11-09T02:00:00Z", "2026-11-09T03:00:00Z", SleepSessionRecord.STAGE_TYPE_DEEP),
                stage("2026-11-09T03:00:00Z", "2026-11-09T06:00:00Z", SleepSessionRecord.STAGE_TYPE_REM),
            ),
        )
        val nap = session("2026-11-09T13:00:00Z", "2026-11-09T13:30:00Z")
        val summary = WearOsDaySummaries.summaries(listOf(day), zone, listOf(night, nap), emptyList(), emptyMap()).single()
        assertEquals(400, summary.sleepMinutes)
        assertEquals(20, summary.awakeMinutes)
        assertEquals(160, summary.lightMinutes)
        assertEquals(60, summary.deepMinutes)
        assertEquals(180, summary.remMinutes)
    }

    @Test
    fun `a night without stages counts whole, and missing figures stay null`() {
        val summary = WearOsDaySummaries.summaries(
            listOf(day), zone, listOf(session("2026-11-08T23:00:00Z", "2026-11-09T06:00:00Z")), emptyList(), emptyMap(),
        ).single()
        assertEquals(420, summary.sleepMinutes)
        assertNull(summary.remMinutes)
        assertNull(summary.restingBpm)
        assertNull(summary.activeKcal)
    }

    @Test
    fun `the day takes its latest resting rate and its totals`() {
        val resting = listOf(
            RestingHeartRateRecord(at("2026-11-09T07:00:00Z"), zone, 58, Metadata.manualEntry()),
            RestingHeartRateRecord(at("2026-11-09T09:00:00Z"), zone, 54, Metadata.manualEntry()),
        )
        val summary = WearOsDaySummaries.summaries(
            listOf(day), zone, emptyList(), resting, mapOf(day to WearOsDaySummaries.Totals(312.4, 4021.6)),
        ).single()
        assertEquals(54, summary.restingBpm)
        assertEquals(312, summary.activeKcal)
        assertEquals(4022, summary.distanceMetres)
        assertEquals(day.toEpochDay(), summary.epochDay)
    }

    @Test
    fun `the last days run oldest first to today`() {
        assertEquals(
            listOf(LocalDate.of(2026, 11, 7), LocalDate.of(2026, 11, 8), day),
            WearOsDaySummaries.lastDays(at("2026-11-09T12:00:00Z"), zone, 3),
        )
    }
}
