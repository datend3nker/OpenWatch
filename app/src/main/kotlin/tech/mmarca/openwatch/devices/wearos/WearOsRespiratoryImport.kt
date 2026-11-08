package tech.mmarca.openwatch.devices.wearos

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import java.time.ZoneId

/**
 * Maps the breathing rate of the watch's night-time windows to Health
 * Connect: one `RespiratoryRateRecord` per window, at the window's start,
 * keyed `wearos_rr_<start millis>`. Written only when the user switched it
 * on: the rate is not yet checked against a reference.
 *
 * Pure, so it is testable without a device.
 */
object WearOsRespiratoryImport {

    const val CLIENT_ID_PREFIX = "wearos_rr_"

    val writePermission: String = HealthPermission.getWritePermission(RespiratoryRateRecord::class)

    /** The record of [window], or null when Health Connect would refuse the value. */
    fun record(window: WearOsRespiratoryWindow, zone: ZoneId): RespiratoryRateRecord? = try {
        RespiratoryRateRecord(
            time = window.start,
            zoneOffset = zone.rules.getOffset(window.start),
            rate = window.breathsPerMinute,
            metadata = Metadata.autoRecorded(
                device = Device(type = Device.TYPE_WATCH),
                clientRecordId = clientRecordId(window),
            ),
        )
    } catch (_: IllegalArgumentException) {
        null
    }

    fun clientRecordId(window: WearOsRespiratoryWindow): String = "$CLIENT_ID_PREFIX${window.start.toEpochMilli()}"
}
