package tech.mmarca.openwatch.devices.wearos

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Percentage
import java.time.ZoneId

/**
 * Maps the watch's SpO2 spot measurements (the Samsung platform's own
 * reading) to Health Connect: one `OxygenSaturationRecord` each, keyed
 * `wearos_spo2_<millis>`. A measurement is final, so a re-pull is the same
 * record.
 *
 * Pure, so it is testable without a device.
 */
object WearOsSpo2Import {

    const val CLIENT_ID_PREFIX = "wearos_spo2_"

    val writePermission: String = HealthPermission.getWritePermission(OxygenSaturationRecord::class)

    /** The record of [measurement], or null when Health Connect would refuse the value. */
    fun record(measurement: WearOsSpo2Measurement, zone: ZoneId): OxygenSaturationRecord? = try {
        OxygenSaturationRecord(
            time = measurement.time,
            zoneOffset = zone.rules.getOffset(measurement.time),
            percentage = Percentage(measurement.percent.toDouble()),
            metadata = Metadata.autoRecorded(
                device = Device(type = Device.TYPE_WATCH),
                clientRecordId = clientRecordId(measurement),
            ),
        )
    } catch (_: IllegalArgumentException) {
        null
    }

    fun clientRecordId(measurement: WearOsSpo2Measurement): String = "$CLIENT_ID_PREFIX${measurement.time.toEpochMilli()}"
}
