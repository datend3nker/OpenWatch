package tech.mmarca.openwatch.healthconnect

import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Mass
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HealthConnectWritesTest {

    private val start = Instant.parse("2026-11-17T06:00:00Z")

    @Test
    fun `a write names its record types and the time they cover, no value`() {
        val heartRate = HeartRateRecord(
            startTime = start, startZoneOffset = ZoneOffset.UTC,
            endTime = start.plusSeconds(600), endZoneOffset = ZoneOffset.UTC,
            samples = listOf(HeartRateRecord.Sample(start.plusSeconds(60), 62)),
            metadata = Metadata.manualEntry(),
        )
        val weight = WeightRecord(start.minusSeconds(3_600), ZoneOffset.UTC, Mass.kilograms(72.0), Metadata.manualEntry())

        val write = HealthConnectWrite.of(listOf(heartRate, weight))!!

        assertEquals(setOf("HeartRate", "Weight"), write.recordTypes)
        assertEquals(start.minusSeconds(3_600), write.from)
        assertEquals(start.plusSeconds(600), write.to)
    }

    @Test
    fun `nothing written is no write`() {
        assertNull(HealthConnectWrite.of(emptyList()))
    }
}
