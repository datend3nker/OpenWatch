package tech.mmarca.openwatch.devices.wearos

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import java.time.ZoneId

/**
 * Maps the watch's night-time HRV windows to Health Connect: one
 * `HeartRateVariabilityRmssdRecord` per window, at the window's start,
 * keyed `wearos_hrv_<start millis>`. A window is final, so a re-pull of the
 * same one is the same record and is skipped as present.
 *
 * Pure, so it is testable without a device.
 */
object WearOsHrvImport {

    const val CLIENT_ID_PREFIX = "wearos_hrv_"

    val writePermission: String = HealthPermission.getWritePermission(HeartRateVariabilityRmssdRecord::class)

    /**
     * The record of [window], or null when Health Connect would refuse the
     * value (it takes 1 to 200 ms). A null is dropped, never retried: the
     * same window would be refused again.
     */
    fun record(window: WearOsHrvWindow, zone: ZoneId): HeartRateVariabilityRmssdRecord? = try {
        HeartRateVariabilityRmssdRecord(
            time = window.start,
            zoneOffset = zone.rules.getOffset(window.start),
            heartRateVariabilityMillis = window.rmssdMillis,
            metadata = Metadata.autoRecorded(
                device = Device(type = Device.TYPE_WATCH),
                clientRecordId = clientRecordId(window),
            ),
        )
    } catch (_: IllegalArgumentException) {
        null
    }

    fun clientRecordId(window: WearOsHrvWindow): String = "$CLIENT_ID_PREFIX${window.start.toEpochMilli()}"
}
