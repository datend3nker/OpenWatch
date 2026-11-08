package tech.mmarca.openwatch.domain.insights

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import tech.mmarca.openwatch.domain.model.WearSleepMinute

/** One daytime sleep period. Its identity is its onset minute, which re-estimation keeps. */
data class WearNap(val session: EstimatedSleepSession) {
    val onset: Instant get() = session.onset
    val end: Instant get() = session.end
}

/**
 * Naps and other sleep outside the main night, from the same minutes the
 * night is estimated from. Pure and deterministic.
 *
 * A nap is a run of minutes that are all of: worn, scored as sleep by
 * [SleepWakeScorer] (still arm, quiet pulse), and with a 5-minute median
 * heart rate clearly below the wearer's waking level. The waking level is
 * the lower quarter ([Config.levelQuantile]) of the heart rate over the worn
 * minutes between 06:00 and 22:00 outside the night sessions. The median
 * was used first: after a busy day it was so high that resting on a sofa
 * (2026-11-04, 52 minutes at 57 to 61 bpm) counted as a nap. Sitting still
 * on a sofa lowers the pulse by a few beats at most, so by day the drop must be at least
 * [Config.dayDropBpm] and [Config.dayDropShare] of the level. These
 * thresholds are judgement, not fitted to data: no nap was recorded
 * against a reference yet.
 *
 * A run counts when it holds at least [Config.minNapMinutes] of sleep and
 * lasts at most [Config.maxNapMinutes]; a longer one is a night the night
 * estimator has not found yet. Short gaps join two runs. A run within
 * [Config.nightGuardMinutes] of a night session is part of that night.
 * Between 22:00 and 06:00 a run counts only when a night session exists
 * in the minutes, with the smaller drop of [Config.nightDropBpm], so the
 * first hours of a night nobody estimated yet are not written as a nap.
 *
 * [Config.minNapMinutes] (20) is the one number here with a literature match:
 * a study comparing 5, 10, 20 and 30-minute naps found the clearest benefit
 * at 10 and 20 minutes (reported e.g. in Brooks & Lack, "A brief afternoon
 * nap following nocturnal sleep restriction", Sleep 29(6), 2006). That study
 * picks a *best* nap length, not a *minimum detectable* one, so the match is
 * loose. Every other number in [Config] is this app's own choice.
 */
object WearNaps {

    data class Config(
        val minNapMinutes: Int = 20,
        val maxNapMinutes: Int = 240,
        /** Worn waking minutes needed to know the wearer's waking level. */
        val minBaselineMinutes: Int = 90,
        /**
         * Where in the day's sorted pulses the waking level sits: 0.5 is the median, 0.25 the lower
         * quarter. The median of a busy day is high, and a resting pulse on the sofa then looks like
         * sleep. The lower quarter is the wearer's own rest.
         */
        val levelQuantile: Float = 0.25f,
        val dayDropBpm: Float = 10f,
        val dayDropShare: Float = 0.12f,
        val nightDropBpm: Float = 6f,
        val nightDropShare: Float = 0.08f,
        val dayStartHour: Int = 6,
        val dayEndHour: Int = 22,
        val heartRateMedianHalf: Int = 2,
        val gapMergeMinutes: Int = 5,
        val nightGuardMinutes: Int = 30,
        /** How far the onset may move back over still minutes with the pulse under the waking level. */
        val onsetLookbackMinutes: Int = 15,
        val onsetLookbackDropBpm: Float = 3f,
        val scorer: SleepWakeScorer.Config = SleepWakeScorer.Config(),
        val wear: WearStateDetector.Config = WearStateDetector.Config(),
    )

    /** The naps in [minutes], oldest first, outside the nights the same minutes hold. */
    fun detectAll(minutes: List<WearSleepMinute>, offset: ZoneOffset, config: Config = Config()): List<WearNap> =
        detectAllWithReasons(minutes, offset, config).naps.map { it.nap }

    /** Like [detectAll], with the numbers behind each nap. */
    fun detectAllWithReasons(minutes: List<WearSleepMinute>, offset: ZoneOffset, config: Config = Config()): Report {
        if (minutes.isEmpty()) return Report(null, 0, emptyList())
        val first = minutes.minOf { it.time }.atOffset(offset).toLocalDate()
        val last = minutes.maxOf { it.time }.atOffset(offset).toLocalDate()
        val nights = generateSequence(first) { it.plusDays(1) }.takeWhile { !it.isAfter(last.plusDays(1)) }
            .mapNotNull { WearNight.estimate(minutes, it, offset)?.session }
            .toList()
        return detectWithReasons(minutes, nights, config)
    }

    /** The naps on the calendar day [date] of [offset], from minutes that cover the day. */
    fun ofDay(minutes: List<WearSleepMinute>, date: LocalDate, offset: ZoneOffset, config: Config = Config()): List<WearNap> =
        ofDayWithReasons(minutes, date, offset, config).naps.map { it.nap }

    /** Like [ofDay], with the numbers behind each nap. The waking level is the one the whole minute list gave. */
    fun ofDayWithReasons(minutes: List<WearSleepMinute>, date: LocalDate, offset: ZoneOffset, config: Config = Config()): Report {
        val from = date.atStartOfDay().toInstant(offset)
        val to = date.plusDays(1).atStartOfDay().toInstant(offset)
        val all = detectAllWithReasons(minutes, offset, config)
        return all.copy(naps = all.naps.filter { !it.nap.onset.isBefore(from) && it.nap.onset.isBefore(to) })
    }

    fun detect(minutes: List<WearSleepMinute>, nights: List<EstimatedSleepSession>, config: Config = Config()): List<WearNap> =
        detectWithReasons(minutes, nights, config).naps.map { it.nap }

    /** Why a nap was found: the numbers [detect] compared. */
    data class NapReason(
        val nap: WearNap,
        /** The waking level the pulse was compared with, in beats per minute. */
        val wakingLevel: Float,
        /** The drop under the waking level that the run needed. */
        val requiredDrop: Float,
        /** True when the run started in the waking hours, so the larger day drop applied. */
        val daytime: Boolean,
        /** Median of the smoothed pulse over the nap, or null when it holds no pulse. */
        val medianHeartRate: Float?,
        /** The share of the nap's minutes labelled sleep, 0 to 1. */
        val sleepShare: Float,
        /** Mean movement count over the nap's minutes. */
        val meanMovement: Float,
        /** Minutes the onset moved back over still minutes with a low pulse. */
        val onsetLookbackMinutes: Int,
    )

    /** The waking level and the naps with their reasons. [wakingLevel] is null when too few minutes made one. */
    data class Report(
        val wakingLevel: Float?,
        /** Worn waking-hour minutes with a pulse outside the nights: the baseline's input. */
        val baselineMinutes: Int,
        val naps: List<NapReason>,
    )

    /** The one calculation behind [detect]: the same naps, each with the numbers that decided it. */
    fun detectWithReasons(minutes: List<WearSleepMinute>, nights: List<EstimatedSleepSession>, config: Config = Config()): Report {
        val grid = WearMinuteGrid.of(minutes) ?: return Report(null, 0, emptyList())
        val wear = WearStateDetector.detect(grid, config.wear)
        val inNight = BooleanArray(grid.size)
        val guarded = BooleanArray(grid.size)
        for (night in nights) {
            val onset = grid.indexOf(night.onset) ?: if (night.onset.isBefore(grid.timeAt(0))) 0 else continue
            val end = grid.indexOf(night.end) ?: grid.size
            for (index in onset until end) inNight[index] = true
            for (index in maxOf(0, onset - config.nightGuardMinutes) until minOf(grid.size, end + config.nightGuardMinutes)) {
                guarded[index] = true
            }
        }
        val (baselineOrNull, baselineMinutes) = wakingLevel(grid, wear, inNight, config)
        val baseline = baselineOrNull ?: return Report(null, baselineMinutes, emptyList())
        val labels = SleepWakeScorer.score(grid, wear, config.scorer)
        val rate = FloatArray(grid.size) { if (wear.isWorn(it)) grid.heartRate[it] else Float.NaN }
        val smooth = SptWindowDetector.rollingMedian(rate, config.heartRateMedianHalf)
        val hasNight = nights.isNotEmpty()
        val quiet = BooleanArray(grid.size) { index ->
            val daytime = isDaytime(grid, index, config)
            (daytime || hasNight) && !guarded[index] && wear.isWorn(index) && labels[index] == SleepLabels.Sleep &&
                !smooth[index].isNaN() && smooth[index] <= baseline - drop(baseline, daytime, config)
        }
        val naps = runs(quiet, wear, config).mapNotNull { run ->
            val widened = earlier(grid, wear, smooth, baseline, labels, run, config)
            val nap = napOf(grid, labels, widened, config) ?: return@mapNotNull null
            val daytime = isDaytime(grid, run.first, config)
            val pulses = widened.map { smooth[it] }.filter { !it.isNaN() }.sorted()
            NapReason(
                nap = nap,
                wakingLevel = baseline,
                requiredDrop = drop(baseline, daytime, config),
                daytime = daytime,
                medianHeartRate = pulses.getOrNull(pulses.size / 2),
                sleepShare = widened.count { labels[it] == SleepLabels.Sleep }.toFloat() / (widened.last - widened.first + 1),
                meanMovement = widened.map { grid.movement[it] }.average().toFloat(),
                onsetLookbackMinutes = run.first - widened.first,
            )
        }
        return Report(baseline, baselineMinutes, naps)
    }

    /**
     * The scorer's rescoring calls the first minutes after wakefulness wake,
     * and the pulse needs minutes to fall: both put the onset late. Walk it
     * back over still, worn minutes whose pulse is already under the waking
     * level, and score them as sleep.
     */
    private fun earlier(
        grid: WearMinuteGrid,
        wear: WearStates,
        smooth: FloatArray,
        baseline: Float,
        labels: CharArray,
        run: IntRange,
        config: Config,
    ): IntRange {
        var start = run.first
        while (start > 0 && run.first - start < config.onsetLookbackMinutes) {
            val at = start - 1
            val still = wear.isWorn(at) && grid.movement[at] <= 0f && !smooth[at].isNaN() && smooth[at] <= baseline - config.onsetLookbackDropBpm
            if (!still) break
            start = at
        }
        for (index in start until run.first) labels[index] = SleepLabels.Sleep
        return start..run.last
    }

    private fun isDaytime(grid: WearMinuteGrid, index: Int, config: Config): Boolean {
        val hour = grid.timeAt(index).atOffset(grid.zoneOffsetAt(index)).hour
        return hour in config.dayStartHour until config.dayEndHour
    }

    private fun drop(baseline: Float, daytime: Boolean, config: Config): Float =
        if (daytime) maxOf(config.dayDropBpm, config.dayDropShare * baseline) else maxOf(config.nightDropBpm, config.nightDropShare * baseline)

    /**
     * The [Config.levelQuantile] of the heart rate of worn waking-hour minutes outside the nights (null
     * with too few), and how many minutes it held.
     */
    private fun wakingLevel(grid: WearMinuteGrid, wear: WearStates, inNight: BooleanArray, config: Config): Pair<Float?, Int> {
        val values = ArrayList<Float>()
        for (index in 0 until grid.size) {
            if (inNight[index] || !wear.isWorn(index) || !isDaytime(grid, index, config) || !grid.hasPulse(index)) continue
            values += grid.heartRate[index]
        }
        if (values.size < config.minBaselineMinutes) return null to values.size
        values.sort()
        return values[(values.size * config.levelQuantile).toInt().coerceIn(0, values.size - 1)] to values.size
    }

    /** Runs of quiet minutes, a gap of at most the merge length joining two when it stays worn. */
    private fun runs(quiet: BooleanArray, wear: WearStates, config: Config): List<IntRange> {
        val merged = ArrayList<IntRange>()
        var start = -1
        for (index in 0..quiet.size) {
            val on = index < quiet.size && quiet[index]
            if (on && start < 0) start = index
            if (!on && start >= 0) {
                val previous = merged.lastOrNull()
                if (previous != null && start - previous.last - 1 <= config.gapMergeMinutes &&
                    (previous.last + 1 until start).all { wear.isWorn(it) }
                ) {
                    merged[merged.size - 1] = previous.first until index
                } else {
                    merged += start until index
                }
                start = -1
            }
        }
        return merged
    }

    private fun napOf(grid: WearMinuteGrid, labels: CharArray, run: IntRange, config: Config): WearNap? {
        val length = run.last - run.first + 1
        val asleep = run.count { labels[it] == SleepLabels.Sleep }
        if (length > config.maxNapMinutes || asleep < config.minNapMinutes) return null
        val spans = ArrayList<EstimatedStageSpan>()
        var from = run.first
        for (index in run.first + 1..run.last + 1) {
            if (index > run.last || stageOf(labels[index]) != stageOf(labels[from])) {
                spans += EstimatedStageSpan(grid.timeAt(from), grid.timeAt(index), stageOf(labels[from]))
                from = index
            }
        }
        val awake = run.count { labels[it] == SleepLabels.Wake }
        val session = EstimatedSleepSession(
            onset = grid.timeAt(run.first),
            end = grid.timeAt(run.last + 1),
            inProgress = grid.size - (run.last + 1) < IN_PROGRESS_MINUTES,
            stages = spans,
            sleepMinutes = asleep,
            awakeMinutes = awake,
            unknownMinutes = length - asleep - awake,
            deepThreshold = 0.6f,
            remThreshold = 0.6f,
        )
        return WearNap(session)
    }

    /** A nap is too short for deep and REM: sleep is light, the rest awake or unknown. */
    private fun stageOf(label: Char): EstimatedStage = when (label) {
        SleepLabels.Sleep -> EstimatedStage.LIGHT
        SleepLabels.Wake -> EstimatedStage.AWAKE
        else -> EstimatedStage.UNKNOWN
    }

    private const val IN_PROGRESS_MINUTES = 20
}
