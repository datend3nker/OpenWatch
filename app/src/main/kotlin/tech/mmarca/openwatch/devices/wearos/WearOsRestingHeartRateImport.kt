package tech.mmarca.openwatch.devices.wearos

import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import tech.mmarca.openwatch.domain.insights.WearNight
import tech.mmarca.openwatch.domain.insights.WearSleepEstimator
import tech.mmarca.openwatch.domain.model.WearSleepMinute

/**
 * One nocturnal resting heart rate per estimated night, from the minutes the
 * sleep import already pulled; see [WearNight.restingHeartRate]. Timed at the
 * night's end and keyed on the night. The sync time is the version, so a
 * later estimate replaces an earlier one, like the sleep session.
 *
 * Pure, so it is testable without a device.
 */
object WearOsRestingHeartRateImport {

    const val CLIENT_ID_PREFIX = "wearos_rhr_est_"

    fun clientRecordId(night: LocalDate): String = "$CLIENT_ID_PREFIX$night"

    fun isOwnRecordId(clientRecordId: String?): Boolean = clientRecordId?.startsWith(CLIENT_ID_PREFIX) == true

    /**
     * The calendar day the app files a resting heart rate stamped at [end] under, as the
     * half-open range `[from, to)`. The app averages the values of a day.
     */
    fun dayWindow(end: Instant, zone: ZoneId): Pair<Instant, Instant> {
        val day = end.atZone(zone).toLocalDate()
        return day.atStartOfDay(zone).toInstant() to day.plusDays(1).atStartOfDay(zone).toInstant()
    }

    /** The record for [estimated]. Null while the night runs on, and when its sleep has too few heart rate readings. */
    fun record(
        minutes: List<WearSleepMinute>,
        estimated: WearSleepEstimator.Night,
        night: LocalDate,
        offset: ZoneOffset,
        version: Long,
    ): RestingHeartRateRecord? {
        // A part of the night reads high, and the value would stand until a later sync revises it.
        val bpm = WearNight.restingHeartRate(minutes, estimated) ?: return null
        return RestingHeartRateRecord(
            time = estimated.session.end,
            zoneOffset = offset,
            beatsPerMinute = bpm.toLong(),
            metadata = Metadata.manualEntry(
                device = Device(type = Device.TYPE_PHONE),
                clientRecordId = clientRecordId(night),
                clientRecordVersion = version,
            ),
        )
    }
}
