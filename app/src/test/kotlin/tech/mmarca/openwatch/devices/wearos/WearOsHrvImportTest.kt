package tech.mmarca.openwatch.devices.wearos

import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WearOsHrvImportTest {

    private val start = Instant.parse("2026-11-03T02:10:00Z")
    private val zone = ZoneOffset.ofHours(1)

    @Test
    fun `a window is one RMSSD record at its start, keyed on the start`() {
        val record = WearOsHrvImport.record(WearOsHrvWindow(start, 45.2, 280), zone)!!

        assertEquals(start, record.time)
        assertEquals(ZoneOffset.ofHours(1), record.zoneOffset)
        assertEquals(45.2, record.heartRateVariabilityMillis, 1e-9)
        assertEquals("wearos_hrv_${start.toEpochMilli()}", record.metadata.clientRecordId)
    }

    @Test
    fun `the same window gives the same id`() {
        val a = WearOsHrvImport.record(WearOsHrvWindow(start, 45.2, 280), zone)!!
        val b = WearOsHrvImport.record(WearOsHrvWindow(start, 45.2, 280), zone)!!
        assertEquals(a.metadata.clientRecordId, b.metadata.clientRecordId)
    }

    @Test
    fun `a value outside Health Connect's range gives no record`() {
        assertNull(WearOsHrvImport.record(WearOsHrvWindow(start, 250.0, 280), zone))
        assertNull(WearOsHrvImport.record(WearOsHrvWindow(start, 0.0, 280), zone))
        assertNotNull(WearOsHrvImport.record(WearOsHrvWindow(start, 200.0, 280), zone))
    }
}
