package tech.mmarca.openwatch.devices.wearos

import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.MindfulnessSessionRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.mmarca.openwatch.wearlink.WearLinkEntry
import tech.mmarca.openwatch.wearlink.WearLinkEntry.Type

class WearOsEntryImportTest {

    private val zone = ZoneOffset.UTC
    private val at = 1_794_000_000_000L

    @Test
    fun `water, weight and blood pressure become their records, entered by hand, under a salted stable id`() {
        val water = WearOsEntryImport.record(WearLinkEntry.of(at, Type.WATER, 250.0), zone, salt = "s1") as HydrationRecord
        assertEquals(250.0, water.volume.inMilliliters, 0.001)
        assertEquals("wearos_entry_water_$at@s1", water.metadata.clientRecordId)
        assertEquals(Metadata.RECORDING_METHOD_MANUAL_ENTRY, water.metadata.recordingMethod)
        assertEquals(Device.TYPE_WATCH, water.metadata.device?.type)
        val weight = WearOsEntryImport.record(WearLinkEntry.of(at, Type.WEIGHT, 72.5), zone) as WeightRecord
        assertEquals(72.5, weight.weight.inKilograms, 0.001)
        assertEquals("wearos_entry_weight_$at", weight.metadata.clientRecordId)
        val pressure = WearOsEntryImport.record(WearLinkEntry.of(at, Type.BLOOD_PRESSURE, 120.0, 80.0), zone) as BloodPressureRecord
        assertEquals(80.0, pressure.diastolic.inMillimetersOfMercury, 0.001)
    }

    @Test
    fun `a spot measurement is actively recorded`() {
        val pulse = WearOsEntryImport.record(WearLinkEntry.of(at, Type.HEART_RATE, 61.0, measured = true), zone) as HeartRateRecord
        assertEquals(61L, pulse.samples.single().beatsPerMinute)
        assertEquals(Metadata.RECORDING_METHOD_ACTIVELY_RECORDED, pulse.metadata.recordingMethod)
        val hrv = WearOsEntryImport.record(WearLinkEntry.of(at, Type.HRV, 42.5, measured = true), zone) as HeartRateVariabilityRmssdRecord
        assertEquals(42.5, hrv.heartRateVariabilityMillis, 0.001)
    }

    @Test
    fun `a breathing session ends when it was logged`() {
        val session = WearOsEntryImport.record(WearLinkEntry.of(at, Type.MINDFULNESS, 1.0), zone) as MindfulnessSessionRecord
        assertEquals(Instant.ofEpochMilli(at), session.endTime)
        assertEquals(Duration.ofMinutes(1), Duration.between(session.startTime, session.endTime))
        assertEquals(MindfulnessSessionRecord.MINDFULNESS_SESSION_TYPE_BREATHING, session.mindfulnessSessionType)
    }

    @Test
    fun `body fat, blood oxygen, breathing rate, temperature and glucose become their records in their units`() {
        assertEquals(21.5, (WearOsEntryImport.record(WearLinkEntry.of(at, Type.BODY_FAT, 21.5), zone) as BodyFatRecord).percentage.value, 0.001)
        assertEquals(97.0, (WearOsEntryImport.record(WearLinkEntry.of(at, Type.BLOOD_OXYGEN, 97.0), zone) as OxygenSaturationRecord).percentage.value, 0.001)
        assertEquals(14.0, (WearOsEntryImport.record(WearLinkEntry.of(at, Type.RESPIRATORY_RATE, 14.0), zone) as RespiratoryRateRecord).rate, 0.001)
        val temperature = WearOsEntryImport.record(WearLinkEntry.of(at, Type.BODY_TEMPERATURE, 36.8), zone) as BodyTemperatureRecord
        assertEquals(36.8, temperature.temperature.inCelsius, 0.001)
        val glucose = WearOsEntryImport.record(WearLinkEntry.of(at, Type.BLOOD_GLUCOSE, 5.4), zone) as BloodGlucoseRecord
        assertEquals(5.4, glucose.level.inMillimolesPerLiter, 0.001)
        assertEquals("wearos_entry_glucose_$at", glucose.metadata.clientRecordId)
        assertEquals(Metadata.RECORDING_METHOD_MANUAL_ENTRY, glucose.metadata.recordingMethod)
    }

    @Test
    fun `a height in centimetres becomes metres`() {
        val height = WearOsEntryImport.record(WearLinkEntry.of(at, Type.HEIGHT, 178.0), zone) as HeightRecord
        assertEquals(1.78, height.height.inMeters, 1e-9)
        assertEquals("wearos_entry_height_$at", height.metadata.clientRecordId)
    }

    @Test
    fun `a body composition's lean mass and body water are kilograms, measured, under ids of their own`() {
        val lean = WearOsEntryImport.record(WearLinkEntry.of(at, Type.LEAN_BODY_MASS, 61.25, measured = true), zone) as LeanBodyMassRecord
        val water = WearOsEntryImport.record(WearLinkEntry.of(at, Type.BODY_WATER, 44.5, measured = true), zone) as BodyWaterMassRecord
        assertEquals(61.25, lean.mass.inKilograms, 1e-9)
        assertEquals(44.5, water.mass.inKilograms, 1e-9)
        assertEquals(Metadata.RECORDING_METHOD_ACTIVELY_RECORDED, lean.metadata.recordingMethod)
        assertEquals("wearos_entry_lean_$at", lean.metadata.clientRecordId)
        assertEquals("wearos_entry_bodywater_$at", water.metadata.clientRecordId)
        assertEquals("android.permission.health.WRITE_LEAN_BODY_MASS", WearOsEntryImport.writePermission(Type.LEAN_BODY_MASS))
        assertEquals("android.permission.health.WRITE_BODY_WATER_MASS", WearOsEntryImport.writePermission(Type.BODY_WATER))
    }

    @Test
    fun `a value Health Connect refuses is dropped`() {
        assertNull(WearOsEntryImport.record(WearLinkEntry.of(at, Type.WEIGHT, 5000.0), zone))
        assertNull(WearOsEntryImport.record(WearLinkEntry.of(at, Type.HRV, 500.0, measured = true), zone))
    }

    @Test
    fun `a breathing session is kept only where Health Connect stores mindfulness, every other type always`() {
        assertFalse(WearOsEntryImport.isStorable(Type.MINDFULNESS, mindfulness = false))
        assertTrue(WearOsEntryImport.isStorable(Type.MINDFULNESS, mindfulness = true))
        for (type in Type.entries - Type.MINDFULNESS) assertTrue(type.name, WearOsEntryImport.isStorable(type, mindfulness = false))
    }

    @Test
    fun `every entry type has its record's write grant`() {
        assertEquals("android.permission.health.WRITE_HYDRATION", WearOsEntryImport.writePermission(Type.WATER))
        assertEquals("android.permission.health.WRITE_MINDFULNESS", WearOsEntryImport.writePermission(Type.MINDFULNESS))
        for (type in Type.entries) assertTrue(WearOsEntryImport.writePermission(type).startsWith("android.permission.health.WRITE_"))
    }
}
