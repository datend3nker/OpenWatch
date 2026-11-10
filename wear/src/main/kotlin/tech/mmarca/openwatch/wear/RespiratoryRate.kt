package tech.mmarca.openwatch.wear

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Breaths per minute from one still window of the watch's raw PPG, by two routes that must agree,
 * or one route with a clear peak when the other finds none.
 *
 * Breathing leaves two marks on a pulse signal. It speeds and slows the heart (respiratory sinus
 * arrhythmia), so the beat-to-beat intervals rise and fall once per breath; and it moves the
 * signal's baseline and amplitude (respiratory-induced intensity variation). Reviews of PPG
 * respiration (e.g. Charlton et al., "Breathing rate estimation from the electrocardiogram and
 * photoplethysmogram: a review", IEEE Reviews in Biomedical Engineering 11, 2018,
 * doi:10.1109/RBME.2017.2763681) describe both, and say that fusing several estimates and
 * discarding windows where they disagree gives fewer large errors than either route alone.
 *
 * Each route resamples its series to [SERIES_HZ], removes the slow trend, and reads the strongest
 * frequency between [MIN_BREATHS_PER_MINUTE] and [MAX_BREATHS_PER_MINUTE] in a Hann-windowed
 * spectrum. The estimate counts only if that peak holds [MIN_PEAK_SHARE] of the power in the band.
 * The limits and shares are this app's own choice, not taken from the review, and the result is
 * not validated against a reference device. The offline replay of recorded nights is for that.
 */
object RespiratoryRate {

    const val MIN_BREATHS_PER_MINUTE = 6.0
    const val MAX_BREATHS_PER_MINUTE = 30.0

    /** The resampled series' rate: far above the 0.5 Hz upper edge of breathing, far below the pulse. */
    const val SERIES_HZ = 4.0

    /** Shorter series have too few breaths for a peak (6 breaths per minute is one in 10 seconds). */
    const val MIN_SERIES_SECONDS = 120.0

    /** The share of the band's power that the peak and its two neighbours must hold. */
    const val MIN_PEAK_SHARE = 0.30

    /** The peak share one route alone must hold to count without the other: a clear rhythm. */
    const val MIN_SINGLE_ROUTE_SHARE = 0.50

    /** The two routes agree when they differ by less than this. */
    const val MAX_ROUTE_DIFFERENCE = 3.0

    /** Seconds of the moving average that is taken off as the trend. */
    private const val TREND_SECONDS = 20.0

    private const val FREQUENCY_STEP_HZ = 0.0025
    private const val PEAK_HALF_WIDTH_HZ = 0.02
    private const val MIN_BEATS = 100

    /** One route's reading and how clean its peak was. */
    data class Estimate(val breathsPerMinute: Double, val peakShare: Double)

    /**
     * Both routes of one window. [value] is their mean when they agree, null when they disagree,
     * and a single route's reading when only that one gave a peak at least [MIN_SINGLE_ROUTE_SHARE]
     * strong: on the Galaxy Watch7 the signal route almost never finds one at night (one window in
     * eight on 2026-11-09/10), so requiring both left every night without a value. Charlton et al.
     * (Physiol Meas 2016;37(4):610-626, doi:10.1088/0967-3334/37/4/610) found the interval route
     * among the better single methods, with a signal-quality check; the share is this app's check.
     */
    data class Window(val fromIntervals: Estimate?, val fromSignal: Estimate?) {
        val value: Double?
            get() {
                val a = fromIntervals
                val b = fromSignal
                if (a != null && b != null) {
                    if (abs(a.breathsPerMinute - b.breathsPerMinute) >= MAX_ROUTE_DIFFERENCE) return null
                    return (a.breathsPerMinute + b.breathsPerMinute) / 2
                }
                return (a ?: b)?.takeIf { it.peakShare >= MIN_SINGLE_ROUTE_SHARE }?.breathsPerMinute
            }

        /** How many routes [value] rests on: 2 when both agreed, 1 for a single clean route, 0 without a value. */
        val routes: Int
            get() = when {
                value == null -> 0
                fromIntervals != null && fromSignal != null -> 2
                else -> 1
            }
    }

    fun of(beatTimesMillis: DoubleArray?, signalTimesMillis: DoubleArray, signalValues: DoubleArray): Window =
        Window(beatTimesMillis?.let(::fromIntervals), fromSignal(signalTimesMillis, signalValues))

    /** From the intervals between beats; intervals far from the local rhythm are left out. */
    fun fromIntervals(beatTimesMillis: DoubleArray): Estimate? {
        if (beatTimesMillis.size < MIN_BEATS) return null
        val times = ArrayList<Double>()
        val values = ArrayList<Double>()
        for (i in 1 until beatTimesMillis.size) {
            val interval = beatTimesMillis[i] - beatTimesMillis[i - 1]
            if (interval < HrvRmssd.MIN_INTERVAL_MILLIS || interval > HrvRmssd.MAX_INTERVAL_MILLIS) continue
            times += (beatTimesMillis[i] + beatTimesMillis[i - 1]) / 2
            values += interval
        }
        return fromIntervalSeries(times.toDoubleArray(), values.toDoubleArray())
    }

    /**
     * From intervals that come with their own times (the midpoint of each, in
     * milliseconds, ascending), as a source that measures intervals rather
     * than beats reports them. Intervals far from the median are left out.
     */
    fun fromIntervalSeries(timesMillis: DoubleArray, intervalsMillis: DoubleArray): Estimate? {
        require(timesMillis.size == intervalsMillis.size)
        if (intervalsMillis.size < MIN_BEATS) return null
        val median = intervalsMillis.sorted()[intervalsMillis.size / 2]
        val keep = intervalsMillis.indices.filter { abs(intervalsMillis[it] - median) <= HrvRmssd.MAX_DEVIATION_FROM_MEDIAN * median }
        if (keep.size < MIN_BEATS) return null
        return estimate(resample(DoubleArray(keep.size) { timesMillis[keep[it]] }, DoubleArray(keep.size) { intervalsMillis[keep[it]] }))
    }

    /** From the baseline of the raw signal: its mean in quarter-second bins. */
    fun fromSignal(timesMillis: DoubleArray, values: DoubleArray): Estimate? {
        require(timesMillis.size == values.size)
        if (timesMillis.size < 2) return null
        val start = timesMillis.first()
        val bins = ((timesMillis.last() - start) / 1000.0 * SERIES_HZ).toInt() + 1
        if (bins < MIN_SERIES_SECONDS * SERIES_HZ) return null
        val sum = DoubleArray(bins)
        val count = IntArray(bins)
        for (i in timesMillis.indices) {
            val bin = ((timesMillis[i] - start) / 1000.0 * SERIES_HZ).toInt().coerceIn(0, bins - 1)
            sum[bin] += values[i]
            count[bin]++
        }
        // An empty bin (a hole in the samples) makes the window useless, as for the beats.
        if (count.any { it == 0 }) return null
        return estimate(DoubleArray(bins) { sum[it] / count[it] })
    }

    /** Linear interpolation onto the [SERIES_HZ] grid. Null series for less than [MIN_SERIES_SECONDS]. */
    private fun resample(times: DoubleArray, values: DoubleArray): DoubleArray? {
        val seconds = (times.last() - times.first()) / 1000.0
        if (seconds < MIN_SERIES_SECONDS) return null
        val count = (seconds * SERIES_HZ).toInt() + 1
        val grid = DoubleArray(count)
        var source = 0
        for (k in 0 until count) {
            val t = times.first() + k * 1000.0 / SERIES_HZ
            while (source < times.size - 2 && times[source + 1] < t) source++
            val span = times[source + 1] - times[source]
            val share = if (span <= 0) 0.0 else ((t - times[source]) / span).coerceIn(0.0, 1.0)
            grid[k] = values[source] + share * (values[source + 1] - values[source])
        }
        return grid
    }

    private fun estimate(series: DoubleArray?): Estimate? {
        if (series == null || series.size < MIN_SERIES_SECONDS * SERIES_HZ) return null
        val x = detrend(series)
        val n = x.size
        for (i in 0 until n) x[i] *= 0.5 - 0.5 * cos(2 * PI * i / (n - 1))
        val low = MIN_BREATHS_PER_MINUTE / 60.0
        val high = MAX_BREATHS_PER_MINUTE / 60.0
        val steps = ((high - low) / FREQUENCY_STEP_HZ).toInt() + 1
        val power = DoubleArray(steps) { power(x, low + it * FREQUENCY_STEP_HZ) }
        val total = power.sum()
        if (total <= 0.0) return null
        val best = power.indices.maxByOrNull { power[it] } ?: return null
        val reach = (PEAK_HALF_WIDTH_HZ / FREQUENCY_STEP_HZ).toInt()
        val near = (maxOf(0, best - reach)..minOf(steps - 1, best + reach)).sumOf { power[it] }
        val share = near / total
        if (share < MIN_PEAK_SHARE) return null
        // A peak on the edge of the band is the tail of something outside it.
        if (best == 0 || best == steps - 1) return null
        return Estimate((low + best * FREQUENCY_STEP_HZ) * 60.0, share)
    }

    /** The series minus its centred moving average over [TREND_SECONDS]. */
    private fun detrend(series: DoubleArray): DoubleArray {
        val half = (TREND_SECONDS * SERIES_HZ / 2).toInt()
        val prefix = DoubleArray(series.size + 1)
        for (i in series.indices) prefix[i + 1] = prefix[i] + series[i]
        return DoubleArray(series.size) {
            val from = maxOf(0, it - half)
            val to = minOf(series.size, it + half + 1)
            series[it] - (prefix[to] - prefix[from]) / (to - from)
        }
    }

    private fun power(x: DoubleArray, hertz: Double): Double {
        var re = 0.0
        var im = 0.0
        val w = 2 * PI * hertz / SERIES_HZ
        for (i in x.indices) {
            re += x[i] * cos(w * i)
            im += x[i] * sin(w * i)
        }
        return re * re + im * im
    }
}
