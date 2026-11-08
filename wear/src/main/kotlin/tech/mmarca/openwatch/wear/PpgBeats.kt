package tech.mmarca.openwatch.wear

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Beat times from a raw photoplethysmogram (PPG), for [HrvRmssd].
 *
 * The watch's raw PPG arrives at 25 Hz, so one sample is 40 ms, as long as
 * the intervals whose differences make an RMSSD of 20 to 80 ms. Three steps
 * keep the timing finer than a sample: the signal is band-passed with a
 * filter that adds no delay (forward and backward), each beat is the top of
 * a parabola through the peak sample and its neighbours, and the minimum
 * distance between beats follows the signal's own rhythm so the notch after
 * a pulse is not read as a beat.
 */
object PpgBeats {

    const val SAMPLE_RATE_HZ = 25.0
    private const val STEP_MILLIS = 1000.0 / SAMPLE_RATE_HZ

    /** A hole in the samples longer than this makes the window useless. */
    const val MAX_GAP_MILLIS = 120L

    /**
     * 0.5-4 Hz (30-240 bpm) is a common pass-band for wrist PPG pulse-rate work (e.g. Elgendi et al. and
     * the Analog Devices PRV application note use 0.4-4 Hz), but papers do not agree it is optimal: a
     * 2025 study (Watanabe et al., "Beyond Motion Artifacts", arXiv:2510.06158) reports that cutoffs tuned
     * per person and activity beat a fixed band like this one by a wide margin. This band is this app's
     * starting choice, not a validated optimum for this sensor.
     */
    private const val LOW_CUT_HZ = 0.5
    private const val HIGH_CUT_HZ = 4.0

    /** The filters start cold; beats this close to either end are not used. */
    private const val EDGE_MILLIS = 5_000.0

    /** The shortest interval the first pass allows: 200 bpm. */
    private const val FIRST_PASS_MIN_MILLIS = 300.0

    /** The second pass keeps beats at least this share of the median interval apart. */
    private const val RHYTHM_SHARE = 0.6

    /** A peak must reach this share of a typical peak to count. */
    private const val MIN_PEAK_SHARE = 0.35
    private const val TYPICAL_PEAK_QUANTILE = 0.75

    /**
     * Beat times in milliseconds on the clock of [timesMillis], ascending.
     * Null when the samples have a gap over [MAX_GAP_MILLIS], are too few, or
     * hold no rhythm. [timesMillis] and [values] are the same length.
     */
    fun detect(timesMillis: DoubleArray, values: DoubleArray): DoubleArray? {
        require(timesMillis.size == values.size)
        if (timesMillis.size < 2 * SAMPLE_RATE_HZ * EDGE_MILLIS / 1000) return null
        val grid = resample(timesMillis, values) ?: return null
        val filtered = bandPass(grid)
        val first = peaks(filtered, FIRST_PASS_MIN_MILLIS) ?: return null
        val typical = median(DoubleArray(first.size - 1) { first[it + 1] - first[it] })
        val second = peaks(filtered, RHYTHM_SHARE * typical) ?: return null
        val edge = (EDGE_MILLIS / STEP_MILLIS)
        return second.filter { it > edge && it < filtered.size - 1 - edge }
            .map { timesMillis[0] + it * STEP_MILLIS }
            .toDoubleArray()
    }

    /** Linear interpolation onto the 25 Hz grid that starts at the first sample; null on a gap. */
    private fun resample(times: DoubleArray, values: DoubleArray): DoubleArray? {
        for (i in 1 until times.size) if (times[i] - times[i - 1] > MAX_GAP_MILLIS) return null
        val count = ((times.last() - times.first()) / STEP_MILLIS).toInt() + 1
        val grid = DoubleArray(count)
        var source = 0
        for (k in 0 until count) {
            val t = times.first() + k * STEP_MILLIS
            while (source < times.size - 2 && times[source + 1] < t) source++
            val span = times[source + 1] - times[source]
            val share = if (span <= 0) 0.0 else ((t - times[source]) / span).coerceIn(0.0, 1.0)
            grid[k] = values[source] + share * (values[source + 1] - values[source])
        }
        return grid
    }

    private fun bandPass(signal: DoubleArray): DoubleArray {
        val mean = signal.average()
        var x = DoubleArray(signal.size) { signal[it] - mean }
        x = zeroPhase(x, Biquad.highPass(LOW_CUT_HZ))
        return zeroPhase(x, Biquad.lowPass(HIGH_CUT_HZ))
    }

    private fun zeroPhase(signal: DoubleArray, filter: Biquad): DoubleArray {
        val forward = filter.run(signal)
        forward.reverse()
        val back = filter.run(forward)
        back.reverse()
        return back
    }

    /**
     * Sub-sample peak positions in samples, at least [minDistanceMillis] apart,
     * where two peaks compete the higher stays. Null with fewer than three.
     */
    private fun peaks(x: DoubleArray, minDistanceMillis: Double): DoubleArray? {
        val candidates = (1 until x.size - 1).filter { x[it] > 0 && x[it] > x[it - 1] && x[it] >= x[it + 1] }
        if (candidates.size < 3) return null
        val typical = candidates.map { x[it] }.sorted().let { it[((it.size - 1) * TYPICAL_PEAK_QUANTILE).toInt()] }
        val minDistance = minDistanceMillis / STEP_MILLIS
        val kept = ArrayList<Int>()
        for (index in candidates) {
            if (x[index] < MIN_PEAK_SHARE * typical) continue
            val last = kept.lastOrNull()
            when {
                last == null || index - last >= minDistance -> kept += index
                x[index] > x[last] -> kept[kept.size - 1] = index
            }
        }
        if (kept.size < 3) return null
        return DoubleArray(kept.size) { refine(x, kept[it]) }
    }

    /** The top of the parabola through the sample and its two neighbours, in samples. */
    private fun refine(x: DoubleArray, i: Int): Double {
        val denominator = x[i - 1] - 2 * x[i] + x[i + 1]
        if (denominator == 0.0) return i.toDouble()
        return i + (0.5 * (x[i - 1] - x[i + 1]) / denominator).coerceIn(-0.5, 0.5)
    }

    private fun median(values: DoubleArray): Double {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
    }

    /** One second-order section, Butterworth shape, by the bilinear transform. */
    private class Biquad(
        private val b0: Double,
        private val b1: Double,
        private val b2: Double,
        private val a1: Double,
        private val a2: Double,
    ) {
        fun run(input: DoubleArray): DoubleArray {
            val out = DoubleArray(input.size)
            var x1 = 0.0
            var x2 = 0.0
            var y1 = 0.0
            var y2 = 0.0
            for (i in input.indices) {
                val y = b0 * input[i] + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
                x2 = x1
                x1 = input[i]
                y2 = y1
                y1 = y
                out[i] = y
            }
            return out
        }

        companion object {
            private const val Q = 0.70710678

            fun lowPass(cutHz: Double): Biquad {
                val w = 2 * PI * cutHz / SAMPLE_RATE_HZ
                val alpha = sin(w) / (2 * Q)
                val a0 = 1 + alpha
                val c = cos(w)
                return Biquad((1 - c) / 2 / a0, (1 - c) / a0, (1 - c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
            }

            fun highPass(cutHz: Double): Biquad {
                val w = 2 * PI * cutHz / SAMPLE_RATE_HZ
                val alpha = sin(w) / (2 * Q)
                val a0 = 1 + alpha
                val c = cos(w)
                return Biquad((1 + c) / 2 / a0, -(1 + c) / a0, (1 + c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
            }
        }
    }
}
