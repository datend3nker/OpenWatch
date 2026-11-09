package tech.mmarca.openwatch.wear

import kotlin.math.roundToInt
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import tech.mmarca.openwatch.wearlink.AscentCredit
import tech.mmarca.openwatch.wearlink.DaySummary
import tech.mmarca.openwatch.wearlink.WearLinkProtocol.AscentMinute
import tech.mmarca.openwatch.wearlink.WearLinkProtocol.HeartRateSample
import tech.mmarca.openwatch.wearlink.WearLinkProtocol.StepMinute

/**
 * One local day of what the watch recorded, cut down to what the screens
 * show. A day that is over does not change, so it is made once and kept;
 * only today is read again.
 */
internal data class DayReadings(
    val date: LocalDate,
    /** Steps per hour of the day, 24 values. */
    val stepsByHour: List<Double>,
    /** Metres of height that count, per hour, 24 values. See [AscentCredit]. */
    val ascentByHour: List<Double>,
    /** Mean heart rate per five minutes with a sample, oldest first. */
    val heartRate: List<Double>,
    val heartRateMean: Double?,
    val latestHeartRate: HeartRateSample?,
    val hasSteps: Boolean,
    val hasAscent: Boolean,
    /** The night values of the day, oldest first, each in its tile's base unit. Empty when there are none. */
    val night: Map<MetricTile, List<Double>> = emptyMap(),
)

/** The Today tile's figures. */
internal data class TodayTotals(val steps: Int?, val heartRate: Int?)

/** Turns the watch's own rows into the values the dashboard and the detail screens read. */
internal object WearReadings {

    private const val HOURS = 24

    /** The tiles the phone's figures fill, each in the tile's base unit. */
    private val PHONE_METRICS: List<Pair<MetricTile, (DaySummary) -> Double?>> = listOf(
        MetricTile.SLEEP to { it.sleepMinutes?.toDouble() },
        MetricTile.RESTING_HEART_RATE to { it.restingBpm?.toDouble() },
        MetricTile.ACTIVE_CALORIES to { it.activeKcal?.toDouble() },
        MetricTile.DISTANCE to { it.distanceMetres?.toDouble() },
    )

    /** The stages of the latest night the phone reported, for the sleep detail screen. */
    fun sleepStages(phone: Map<Long, DaySummary>): Map<SleepStage, Double> {
        val night = phone.values.filter { it.sleepMinutes != null }.maxByOrNull { it.epochDay } ?: return emptyMap()
        return listOf(
            SleepStage.AWAKE to night.awakeMinutes,
            SleepStage.REM to night.remMinutes,
            SleepStage.LIGHT to night.lightMinutes,
            SleepStage.DEEP to night.deepMinutes,
        ).mapNotNull { (stage, minutes) -> minutes?.let { stage to it.toDouble() } }.toMap()
    }

    /** The tiles a day's [DayReadings.night] fills. */
    val NIGHT_METRICS = listOf(MetricTile.HRV, MetricTile.BLOOD_OXYGEN, MetricTile.SKIN_TEMPERATURE, MetricTile.RESPIRATORY_RATE)
    private const val DECIMETRES_PER_METRE = 10.0
    private const val MINUTE_MILLIS = 60_000L
    private const val HEART_RATE_BUCKET_MILLIS = 5 * MINUTE_MILLIS

    /** The rows of [date] only; the caller reads them from the store. */
    fun day(
        date: LocalDate,
        zone: ZoneId,
        heartRate: List<HeartRateSample>,
        steps: List<StepMinute>,
        ascent: List<AscentMinute>,
        night: Map<MetricTile, List<Double>> = emptyMap(),
    ): DayReadings {
        val stepsAt = steps.associate { it.epochMillis to it.steps }
        val stepsByHour = DoubleArray(HOURS)
        for (minute in steps) stepsByHour[hourOf(minute.epochMillis, zone)] += minute.steps.toDouble()

        val ascentByHour = DoubleArray(HOURS)
        for (minute in ascent) {
            val walked = (stepsAt[minute.epochMillis - MINUTE_MILLIS] ?: 0) + (stepsAt[minute.epochMillis] ?: 0)
            ascentByHour[hourOf(minute.epochMillis, zone)] +=
                AscentCredit.creditedMetres(minute.decimetres / DECIMETRES_PER_METRE, walked)
        }

        return DayReadings(
            date = date,
            stepsByHour = stepsByHour.toList(),
            ascentByHour = ascentByHour.toList(),
            heartRate = heartRate.groupBy { it.epochMillis / HEART_RATE_BUCKET_MILLIS }
                .toSortedMap()
                .values
                .map { bucket -> bucket.map { it.bpm }.average() },
            heartRateMean = heartRate.takeIf { it.isNotEmpty() }?.map { it.bpm }?.average(),
            latestHeartRate = heartRate.maxByOrNull { it.epochMillis },
            hasSteps = steps.isNotEmpty(),
            hasAscent = ascent.isNotEmpty(),
            night = night.filterValues { it.isNotEmpty() },
        )
    }

    /**
     * The tiles the watch's own recording can fill. [days] are the last
     * seven, oldest first, today last. A tile with no row in any of them is
     * left out, so the screen says "no reading yet" instead of showing zero.
     */
    fun metrics(
        days: List<DayReadings>,
        now: Instant,
        zone: ZoneId,
        /** The phone's figures by day number ([DaySummary.epochDay]). */
        phone: Map<Long, DaySummary> = emptyMap(),
    ): Map<MetricTile, MetricUiState> {
        val today = days.lastOrNull() ?: return emptyMap()
        val thisHour = hourOf(now.toEpochMilli(), zone)
        return buildMap {
            if (days.any { it.hasSteps }) {
                put(MetricTile.STEPS, cumulative(days, thisHour) { it.stepsByHour })
            }
            if (days.any { it.hasAscent }) {
                put(MetricTile.ELEVATION, cumulative(days, thisHour) { it.ascentByHour })
                put(
                    MetricTile.FLOORS,
                    cumulative(days, thisHour) { day -> day.ascentByHour.map { it / AscentCredit.METRES_PER_FLOOR } },
                )
            }
            for ((metric, value) in PHONE_METRICS) {
                val week = days.map { day -> phone[day.date.toEpochDay()]?.let(value) }
                if (week.all { it == null }) continue
                put(
                    metric,
                    MetricUiState(
                        // A total for the day is today's; a resting rate or a night carries over until the next.
                        current = if (metric.kind == MetricKind.CUMULATIVE && metric != MetricTile.SLEEP) week.last() else week.lastOrNull { it != null },
                        week = week.dropWhile { it == null }.map { it ?: 0.0 },
                    ),
                )
            }
            for (metric in NIGHT_METRICS) {
                val latest = days.lastNotNullOfOrNull { it.night[metric]?.lastOrNull() } ?: continue
                put(
                    metric,
                    MetricUiState(
                        current = latest,
                        today = today.night[metric].orEmpty(),
                        // A day without a reading stays a zero bar, as for the heart rate.
                        week = days.dropWhile { it.night[metric] == null }.map { day -> day.night[metric]?.average() ?: 0.0 },
                    ),
                )
            }
            val latest = days.lastNotNullOfOrNull { it.latestHeartRate }
            if (latest != null) {
                put(
                    MetricTile.HEART_RATE,
                    MetricUiState(
                        current = latest.bpm.toDouble(),
                        today = today.heartRate,
                        // The chart labels its bars as the last days, today last: a gap stays a zero bar.
                        week = days.dropWhile { it.heartRateMean == null }.map { it.heartRateMean ?: 0.0 },
                    ),
                )
            }
        }
    }

    /** What the Today tile shows: the day's steps and the latest heart rate, each null when there is none. */
    fun totals(day: DayReadings): TodayTotals = TodayTotals(
        steps = day.stepsByHour.sum().roundToInt().takeIf { day.hasSteps },
        heartRate = day.latestHeartRate?.bpm,
    )

    private fun cumulative(
        days: List<DayReadings>,
        thisHour: Int,
        hours: (DayReadings) -> List<Double>,
    ): MetricUiState {
        val today = hours(days.last())
        return MetricUiState(
            current = today.sum(),
            today = today.take(thisHour + 1),
            week = days.map { hours(it).sum() },
        )
    }

    private fun hourOf(epochMillis: Long, zone: ZoneId): Int =
        Instant.ofEpochMilli(epochMillis).atZone(zone).hour

    private fun <T, R : Any> List<T>.lastNotNullOfOrNull(selector: (T) -> R?): R? =
        asReversed().firstNotNullOfOrNull(selector)
}
