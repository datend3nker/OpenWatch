package tech.mmarca.openwatch.devices.wearos

import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.MindfulnessSessionRecord
import androidx.health.connect.client.records.WeightRecord
import java.time.Duration
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.mmarca.openwatch.wearlink.WatchEntry
import tech.mmarca.openwatch.wearlink.WearLinkDaily

class WearOsEntryImportTest {

    private val zone = ZoneOffset.UTC
    private val at = 1_794_000_000_000L

    @Test
    fun `water, weight and blood pressure become their records under a stable id`() {
        val water = WearOsEntryImport.record(WatchEntry(at, WearLinkDaily.WATER, 250.0), zone) as HydrationRecord
        assertEquals(250.0, water.volume.inMilliliters, 0.001)
        assertEquals("wearos_entry_water_$at", water.metadata.clientRecordId)
        val weight = WearOsEntryImport.record(WatchEntry(at, WearLinkDaily.WEIGHT, 72.5), zone) as WeightRecord
        assertEquals(72.5, weight.weight.inKilograms, 0.001)
        val pressure = WearOsEntryImport.record(WatchEntry(at, WearLinkDaily.BLOOD_PRESSURE, 120.0, 80.0), zone) as BloodPressureRecord
        assertEquals(80.0, pressure.diastolic.inMillimetersOfMercury, 0.001)
    }

    @Test
    fun `a breathing session ends when it was logged`() {
        val session = WearOsEntryImport.record(WatchEntry(at, WearLinkDaily.MINDFULNESS, 1.0), zone) as MindfulnessSessionRecord
        assertEquals(Duration.ofMinutes(1), Duration.between(session.startTime, session.endTime))
    }

    @Test
    fun `a value Health Connect refuses or a pressure without its second value is dropped`() {
        assertNull(WearOsEntryImport.record(WatchEntry(at, WearLinkDaily.BLOOD_PRESSURE, 120.0), zone))
        assertNull(WearOsEntryImport.record(WatchEntry(at, WearLinkDaily.WEIGHT, 5000.0), zone))
    }

    @Test
    fun `every entry type has a write grant`() {
        assertTrue(WearOsEntryImport.writePermissions.keys.containsAll(WearLinkDaily.ENTRY_TYPES))
    }
}
