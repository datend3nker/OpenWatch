package tech.mmarca.openwatch.devices.wearos

import androidx.health.connect.client.records.SleepSessionRecord
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import tech.mmarca.openwatch.devices.core.sync.estimatedSleepSessionRecord
import tech.mmarca.openwatch.domain.insights.SleepStageEstimator
import tech.mmarca.openwatch.domain.insights.WearNap
import tech.mmarca.openwatch.domain.insights.WearNaps
import tech.mmarca.openwatch.domain.insights.WearNight
import tech.mmarca.openwatch.domain.insights.WearSleepEstimator
import tech.mmarca.openwatch.domain.insights.sleepNightWindow
import tech.mmarca.openwatch.domain.model.WearSleepMinute

/**
 * Turns the per-minute rows the watch recorded into one estimated sleep
 * session per night, through `WearSleepEstimator`, and one per nap, through
 * `WearNaps`. The watch is the store: the phone keeps no minutes, only a cursor,
 * and re-pulls a whole night's window to estimate it again as the night
 * fills in. One id per night, so the later estimate replaces the earlier.
 *
 * Pure: no Android, so it is testable without a device.
 */
object WearOsSleepImport {

    const val CLIENT_ID_PREFIX = "wearos_sleep_est_"

    const val NAP_ID_PREFIX = "wearos_nap_"

    fun clientRecordId(night: LocalDate): String = "$CLIENT_ID_PREFIX$night"

    /** One id per nap: its onset minute, which re-estimating the day keeps. */
    fun napClientRecordId(nap: WearNap): String = "$NAP_ID_PREFIX${nap.onset.toEpochMilli()}"

    /** True for a session this import wrote, night or nap. Any other session in the window wins over a new estimate. */
    fun isOwnRecordId(clientRecordId: String?): Boolean =
        clientRecordId?.startsWith(CLIENT_ID_PREFIX) == true || isOwnNapId(clientRecordId)

    fun isOwnNapId(clientRecordId: String?): Boolean = clientRecordId?.startsWith(NAP_ID_PREFIX) == true

    /**
     * Where a pull starts so the night the cursor sits in can be estimated
     * whole, and so can the day's naps: the earlier of that night's window
     * start and 18:00 the evening before the cursor's day, which gives
     * the waking heart rate a day to be read from. Never before the epoch.
     */
    fun pullStart(cursor: Instant, offset: ZoneOffset): Instant {
        if (cursor == Instant.EPOCH) return cursor
        val dayStart = cursor.atOffset(offset).toLocalDate().minusDays(1).atTime(NIGHT_WINDOW_START_HOUR, 0).toInstant(offset)
        val night = SleepStageEstimator.nightDateOf(cursor, offset)
        val windowStart = night?.let { sleepNightWindow(it, offset).first } ?: dayStart
        // The watch answers "newer than", so one millisecond before the window includes its first minute.
        return minOf(cursor, windowStart, dayStart).let { if (it == cursor) it else it.minusMillis(1) }
    }

    /**
     * The naps in [minutes] that touch the minutes newer than [cursor], with
     * the offset their onset had. A nap that ended long before the cursor is
     * not written again.
     */
    fun naps(minutes: List<WearSleepMinute>, cursor: Instant): List<Pair<WearNap, ZoneOffset>> {
        val newest = minutes.maxByOrNull { it.time } ?: return emptyList()
        return WearNaps.detectAll(minutes, newest.zoneOffset)
            .filter { it.end.isAfter(cursor.minus(NAP_REWRITE_LOOKBACK)) }
            .map { nap -> nap to (minutes.firstOrNull { it.time == nap.onset }?.zoneOffset ?: newest.zoneOffset) }
    }

    /** The record for a nap, or null when nothing counted as sleep. */
    fun napRecord(nap: WearNap, offset: ZoneOffset, version: Long): SleepSessionRecord? =
        estimatedSleepSessionRecord(
            session = nap.session,
            offset = offset,
            clientRecordId = napClientRecordId(nap),
            version = version,
            notes = NOTES,
        )

    /**
     * The nights that minutes newer than [cursor] belong to, each with the
     * offset of its last such minute: a DST change mid-night shifts the
     * window by an hour at most.
     */
    fun touchedNights(minutes: List<WearSleepMinute>, cursor: Instant): Map<LocalDate, ZoneOffset> {
        val offsets = LinkedHashMap<LocalDate, ZoneOffset>()
        for (minute in minutes.filter { it.time.isAfter(cursor) }.sortedBy { it.time }) {
            val night = SleepStageEstimator.nightDateOf(minute.time, minute.zoneOffset) ?: continue
            offsets[night] = minute.zoneOffset
        }
        return offsets
    }

    /** The pipeline's input for [night]: the rows inside its window. */
    fun minutesOfNight(minutes: List<WearSleepMinute>, night: LocalDate, offset: ZoneOffset): List<WearSleepMinute> =
        WearNight.minutesOf(minutes, night, offset)

    /** The estimated night for [night], or null when the minutes hold no night. */
    fun night(minutes: List<WearSleepMinute>, night: LocalDate, offset: ZoneOffset): WearSleepEstimator.Night? =
        WearNight.estimate(minutes, night, offset)

    /** The record for an estimated night, or null when nothing counted as sleep. */
    fun record(night: WearSleepEstimator.Night, date: LocalDate, offset: ZoneOffset, version: Long): SleepSessionRecord? =
        estimatedSleepSessionRecord(
            session = night.session,
            offset = offset,
            clientRecordId = clientRecordId(date),
            version = version,
            notes = NOTES,
        )

    /** Both steps, for callers that do not log the night. */
    fun record(minutes: List<WearSleepMinute>, date: LocalDate, offset: ZoneOffset, version: Long): SleepSessionRecord? =
        night(minutes, date, offset)?.let { record(it, date, offset, version) }

    private const val NIGHT_WINDOW_START_HOUR = 18

    /** A nap that ended less than this before the cursor is still re-estimated: its end may have moved. */
    private val NAP_REWRITE_LOOKBACK: java.time.Duration = java.time.Duration.ofHours(1)

    private const val NOTES =
        "Sleep stages estimated by OpenWatch from the heart rate and movement the OpenWatch watch app " +
            "recorded, using published actigraphy and sleep-onset methods (see docs/engineering/" +
            "sleep-minute-features.md); the exact thresholds are OpenWatch' own and not fitted to a " +
            "polysomnography or chest-strap reference."
}
