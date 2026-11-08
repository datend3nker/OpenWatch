package tech.mmarca.openwatch.devices.wearos

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Temperature
import androidx.health.connect.client.units.TemperatureDelta
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * Turns the watch's skin temperature minutes (the Samsung platform's, recorded
 * while sleep is held) into Health Connect records: one `SkinTemperatureRecord`
 * per night run, wrist, keyed `wearos_skin_<run start millis>`.
 *
 * Health Connect stores a skin temperature as deltas from a baseline, which is
 * how the measure is meant to be read: a night warmer or cooler than the
 * wearer's own usual. The baseline is the median of the earlier nights'
 * medians once there are [MIN_BASELINE_NIGHTS] of them, otherwise the run's
 * own median, so a first night is flat around zero rather than against a
 * guess.
 *
 * Pure, so it is testable without a device.
 */
object WearOsSkinTemperatureImport {

    const val CLIENT_ID_PREFIX = "wearos_skin_"

    /** A gap longer than this starts a new run: the watch came off, or the night ended. */
    val MAX_GAP: Duration = Duration.ofMinutes(30)

    /** A run shorter than this is a nap or a test, not a night's temperature. */
    val MIN_RUN: Duration = Duration.ofMinutes(30)

    /** A run whose last minute is this old is finished; a younger one may still grow. */
    val SETTLE: Duration = Duration.ofMinutes(30)

    const val MIN_BASELINE_NIGHTS = 3

    /** The nights the baseline remembers. */
    const val MAX_BASELINE_NIGHTS = 30

    val writePermission: String = HealthPermission.getWritePermission(SkinTemperatureRecord::class)

    /** One night's minutes, in time order. */
    data class Run(val minutes: List<WearOsSkinMinute>) {
        val start: Instant get() = minutes.first().time
        val end: Instant get() = minutes.last().time
        val median: Double get() = median(minutes.map { it.celsius })
    }

    /** The minutes cut into runs at every gap over [MAX_GAP]. */
    fun runs(minutes: List<WearOsSkinMinute>): List<Run> {
        val sorted = minutes.sortedBy { it.time }
        val runs = ArrayList<Run>()
        var current = ArrayList<WearOsSkinMinute>()
        for (minute in sorted) {
            val last = current.lastOrNull()
            if (last != null && Duration.between(last.time, minute.time) > MAX_GAP) {
                runs += Run(current)
                current = ArrayList()
            }
            current += minute
        }
        if (current.isNotEmpty()) runs += Run(current)
        return runs
    }

    /** The runs that are over at [now] and long enough to count. */
    fun finished(runs: List<Run>, now: Instant): List<Run> =
        runs.filter { Duration.between(it.end, now) >= SETTLE && Duration.between(it.start, it.end) >= MIN_RUN }

    /** The baseline for a run: the median of [earlierMedians] once there are enough, else the run's own. */
    fun baseline(run: Run, earlierMedians: List<Double>): Double =
        if (earlierMedians.size >= MIN_BASELINE_NIGHTS) median(earlierMedians) else run.median

    /** The record for [run] against [baselineCelsius], or null when Health Connect would refuse it. */
    fun record(run: Run, baselineCelsius: Double, zone: ZoneId, version: Long): SkinTemperatureRecord? = try {
        SkinTemperatureRecord(
            startTime = run.start,
            startZoneOffset = zone.rules.getOffset(run.start),
            // The last minute's row stands for that whole minute.
            endTime = run.end.plusSeconds(60),
            endZoneOffset = zone.rules.getOffset(run.end),
            metadata = Metadata.autoRecorded(
                device = Device(type = Device.TYPE_WATCH),
                clientRecordId = clientRecordId(run),
                clientRecordVersion = version,
            ),
            deltas = run.minutes.map {
                SkinTemperatureRecord.Delta(it.time, TemperatureDelta.celsius(it.celsius - baselineCelsius))
            },
            baseline = Temperature.celsius(baselineCelsius),
            measurementLocation = SkinTemperatureRecord.MEASUREMENT_LOCATION_WRIST,
        )
    } catch (_: IllegalArgumentException) {
        null
    }

    fun clientRecordId(run: Run): String = "$CLIENT_ID_PREFIX${run.start.toEpochMilli()}"

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
    }
}
