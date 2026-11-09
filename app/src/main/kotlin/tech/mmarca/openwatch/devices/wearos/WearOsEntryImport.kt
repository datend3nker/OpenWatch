package tech.mmarca.openwatch.devices.wearos

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.MindfulnessSessionRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.BloodGlucose
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Percentage
import androidx.health.connect.client.units.Pressure
import androidx.health.connect.client.units.Temperature
import androidx.health.connect.client.units.Volume
import java.time.Instant
import java.time.ZoneId
import tech.mmarca.openwatch.wearlink.WatchEntry
import tech.mmarca.openwatch.wearlink.WearLinkDaily

/**
 * Maps the entries made on the watch (quick logs and spot measurements) to
 * Health Connect: water, weight, blood pressure, body fat, blood oxygen,
 * breathing rate, body temperature, blood glucose, height, a breathing session, and
 * a spot heart rate or HRV, each keyed `wearos_entry_<type>_<millis>`, so a
 * re-pull is the same record. A logged value is a manual entry, a spot
 * measurement an actively recorded one.
 *
 * Pure, so it is testable without a device.
 */
object WearOsEntryImport {

    const val CLIENT_ID_PREFIX = "wearos_entry_"

    /** The write grant each entry type needs. */
    val writePermissions: Map<String, String> = mapOf(
        WearLinkDaily.WATER to HealthPermission.getWritePermission(HydrationRecord::class),
        WearLinkDaily.WEIGHT to HealthPermission.getWritePermission(WeightRecord::class),
        WearLinkDaily.BLOOD_PRESSURE to HealthPermission.getWritePermission(BloodPressureRecord::class),
        WearLinkDaily.MINDFULNESS to HealthPermission.getWritePermission(MindfulnessSessionRecord::class),
        WearLinkDaily.HEART_RATE to HealthPermission.getWritePermission(HeartRateRecord::class),
        WearLinkDaily.HRV to HealthPermission.getWritePermission(HeartRateVariabilityRmssdRecord::class),
        WearLinkDaily.BODY_FAT to HealthPermission.getWritePermission(BodyFatRecord::class),
        WearLinkDaily.BLOOD_OXYGEN to HealthPermission.getWritePermission(OxygenSaturationRecord::class),
        WearLinkDaily.RESPIRATORY_RATE to HealthPermission.getWritePermission(RespiratoryRateRecord::class),
        WearLinkDaily.BODY_TEMPERATURE to HealthPermission.getWritePermission(BodyTemperatureRecord::class),
        WearLinkDaily.BLOOD_GLUCOSE to HealthPermission.getWritePermission(BloodGlucoseRecord::class),
        WearLinkDaily.HEIGHT to HealthPermission.getWritePermission(HeightRecord::class),
    )

    /** The record of [entry], or null when Health Connect would refuse the value. */
    fun record(entry: WatchEntry, zone: ZoneId): Record? = try {
        val at = Instant.ofEpochMilli(entry.epochMillis)
        val offset = zone.rules.getOffset(at)
        val id = clientRecordId(entry)
        when (entry.type) {
            WearLinkDaily.WATER -> HydrationRecord(
                startTime = at, startZoneOffset = offset, endTime = at.plusSeconds(1), endZoneOffset = offset,
                volume = Volume.milliliters(entry.value), metadata = manual(id),
            )
            WearLinkDaily.WEIGHT -> WeightRecord(time = at, zoneOffset = offset, weight = Mass.kilograms(entry.value), metadata = manual(id))
            WearLinkDaily.BLOOD_PRESSURE -> BloodPressureRecord(
                time = at, zoneOffset = offset,
                systolic = Pressure.millimetersOfMercury(entry.value),
                diastolic = Pressure.millimetersOfMercury(entry.value2 ?: return null),
                metadata = manual(id),
            )
            WearLinkDaily.MINDFULNESS -> {
                // The watch logs a breathing session when it ends, with its length in minutes.
                val start = at.minusSeconds((entry.value * SECONDS_PER_MINUTE).toLong().coerceAtLeast(1))
                MindfulnessSessionRecord(
                    startTime = start, startZoneOffset = zone.rules.getOffset(start), endTime = at, endZoneOffset = offset,
                    mindfulnessSessionType = MindfulnessSessionRecord.MINDFULNESS_SESSION_TYPE_BREATHING,
                    metadata = manual(id),
                )
            }
            WearLinkDaily.HEART_RATE -> HeartRateRecord(
                startTime = at, startZoneOffset = offset, endTime = at.plusSeconds(1), endZoneOffset = offset,
                samples = listOf(HeartRateRecord.Sample(at, Math.round(entry.value))),
                metadata = measured(id),
            )
            WearLinkDaily.HRV -> HeartRateVariabilityRmssdRecord(
                time = at, zoneOffset = offset, heartRateVariabilityMillis = entry.value, metadata = measured(id),
            )
            WearLinkDaily.BODY_FAT -> BodyFatRecord(time = at, zoneOffset = offset, percentage = Percentage(entry.value), metadata = manual(id))
            WearLinkDaily.BLOOD_OXYGEN -> OxygenSaturationRecord(time = at, zoneOffset = offset, percentage = Percentage(entry.value), metadata = manual(id))
            WearLinkDaily.RESPIRATORY_RATE -> RespiratoryRateRecord(time = at, zoneOffset = offset, rate = entry.value, metadata = manual(id))
            WearLinkDaily.BODY_TEMPERATURE -> BodyTemperatureRecord(
                time = at, zoneOffset = offset, temperature = Temperature.celsius(entry.value), metadata = manual(id),
            )
            WearLinkDaily.BLOOD_GLUCOSE -> BloodGlucoseRecord(
                time = at, zoneOffset = offset, level = BloodGlucose.millimolesPerLiter(entry.value), metadata = manual(id),
            )
            WearLinkDaily.HEIGHT -> HeightRecord(time = at, zoneOffset = offset, height = Length.meters(entry.value / CM_PER_METRE), metadata = manual(id))
            else -> null
        }
    } catch (_: IllegalArgumentException) {
        null
    }

    fun clientRecordId(entry: WatchEntry): String = "$CLIENT_ID_PREFIX${entry.type}_${entry.epochMillis}"

    private fun manual(id: String) = Metadata.manualEntry(device = Device(type = Device.TYPE_WATCH), clientRecordId = id)

    private fun measured(id: String) = Metadata.activelyRecorded(device = Device(type = Device.TYPE_WATCH), clientRecordId = id)

    private const val SECONDS_PER_MINUTE = 60
    private const val CM_PER_METRE = 100.0
}
