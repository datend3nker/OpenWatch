package tech.mmarca.openwatch.wear

import kotlin.math.abs

/**
 * What one ECG recording shows: where the steep, tall R waves are, the
 * median distance between them, how even those distances are, and whether a
 * P wave stands before the beats. Pure, so a synthetic trace tests it.
 *
 * The trace is differenced over 20 ms (which also cancels 50 Hz mains hum
 * at 500 Hz sampling), squared, and summed over 150 ms; a beat is a peak of
 * that above a share of its upper level, at least 250 ms after the last one;
 * a trace whose upper level does not stand out from its middle has no beats.
 *
 * The rhythm is a description, not a finding: [Rhythm.SINUS_LIKE] says the
 * beats came evenly and a small wave stood before them, as in a sinus
 * rhythm; [Rhythm.IRREGULAR] says the distances jumped from beat to beat.
 * Neither was checked against annotated recordings, a wrist trace is one
 * lead, and the app draws no medical conclusion from it.
 */
object EcgHeartRate {

    const val SAMPLE_RATE_HZ = 500

    enum class Rhythm {
        /** Even beats with a P wave before them, at a rate where one can be told apart. */
        SINUS_LIKE,

        /** Even beats; no P wave was made out, or the rate is too high to look for one. */
        REGULAR,

        /** The distance between beats jumps from one to the next. */
        IRREGULAR,

        /** Between even and irregular, or too few clean beats to say. */
        UNCLEAR,
    }

    /** [irregularity] is the median jump between neighbouring beat distances, as a share of the median distance. */
    class Result(val bpm: Int, val beatCount: Int, val rhythm: Rhythm, val irregularity: Double, val pWave: Boolean)

    /** Null when the trace is too short or shows fewer than [MIN_BEATS] plausible beats. */
    fun of(millivolts: FloatArray, sampleRateHz: Int = SAMPLE_RATE_HZ): Result? {
        val beats = beats(millivolts, sampleRateHz) ?: return null
        val all = DoubleArray(beats.size - 1) { (beats[it + 1] - beats[it]) * 1000.0 / sampleRateHz }
        val plausible = all.filter { it in MIN_INTERVAL_MILLIS..MAX_INTERVAL_MILLIS }.sorted()
        if (plausible.size + 1 < MIN_BEATS) return null
        val median = plausible[plausible.size / 2]
        val bpm = Math.round(60_000.0 / median).toInt()

        val jumps = (1 until all.size)
            .filter { all[it] in MIN_INTERVAL_MILLIS..MAX_INTERVAL_MILLIS && all[it - 1] in MIN_INTERVAL_MILLIS..MAX_INTERVAL_MILLIS }
            .map { abs(all[it] - all[it - 1]) }
            .sorted()
        val irregularity = if (jumps.size < MIN_JUMPS) Double.NaN else jumps[jumps.size / 2] / median
        val even = irregularity <= EVEN_BELOW
        val pWave = even && bpm <= MAX_BPM_FOR_P_WAVE && hasPWave(millivolts, beats, sampleRateHz)
        val rhythm = when {
            irregularity.isNaN() -> Rhythm.UNCLEAR
            irregularity >= IRREGULAR_FROM -> Rhythm.IRREGULAR
            !even -> Rhythm.UNCLEAR
            pWave -> Rhythm.SINUS_LIKE
            else -> Rhythm.REGULAR
        }
        return Result(bpm, beats.size, rhythm, irregularity, pWave)
    }

    /** The sample of each R wave's tip, or null without beats. */
    private fun beats(millivolts: FloatArray, sampleRateHz: Int): IntArray? {
        val lag = sampleRateHz / 50
        val window = sampleRateHz * 150 / 1000
        val refractory = sampleRateHz * 250 / 1000
        if (millivolts.size < sampleRateHz * MIN_SECONDS) return null

        val energy = DoubleArray(millivolts.size)
        var sum = 0.0
        val squares = DoubleArray(millivolts.size)
        for (i in lag until millivolts.size) {
            val slope = (millivolts[i] - millivolts[i - lag]).toDouble()
            squares[i] = slope * slope
            sum += squares[i]
            if (i - window >= 0) sum -= squares[i - window]
            energy[i] = sum
        }
        val sorted = energy.sortedArray()
        val level = sorted[(energy.size * UPPER_LEVEL).toInt().coerceAtMost(energy.size - 1)]
        // Without beats the upper level is hardly above the middle: noise has no R waves to stand out.
        if (level <= 0.0 || level < sorted[energy.size / 2] * MIN_CONTRAST) return null
        val threshold = level * THRESHOLD_SHARE

        val beats = ArrayList<Int>()
        var i = 1
        while (i < energy.size - 1) {
            if (energy[i] >= threshold && energy[i] >= energy[i - 1] && energy[i] > energy[i + 1]) {
                // The tallest point of this beat: look on for one refractory period.
                var peak = i
                val end = minOf(energy.size - 1, i + refractory)
                for (k in i..end) if (energy[k] > energy[peak]) peak = k
                beats += tipBefore(millivolts, peak, window + lag)
                i = peak + refractory
            } else {
                i++
            }
        }
        return if (beats.size < 2) null else beats.toIntArray()
    }

    /** The energy peaks where its window has just passed the R wave; the wave's tip is the sample furthest from that stretch's mean. */
    private fun tipBefore(millivolts: FloatArray, energyPeak: Int, span: Int): Int {
        val from = (energyPeak - span).coerceAtLeast(0)
        var mean = 0.0
        for (k in from..energyPeak) mean += millivolts[k]
        mean /= (energyPeak - from + 1)
        var tip = from
        for (k in from..energyPeak) if (abs(millivolts[k] - mean) > abs(millivolts[tip] - mean)) tip = k
        return tip
    }

    /**
     * Whether a small wave stands before the beats. The stretch from 350 to
     * 60 ms before each R wave is averaged over the beats, which thins noise
     * and hum; the line between its two ends is taken away. A P wave is a
     * bump on that, 100 to 280 ms before the R wave, at least [P_WAVE_SHARE]
     * of the R wave's height, and found alike, same side and same place, in
     * the even and in the odd beats on their own.
     */
    private fun hasPWave(millivolts: FloatArray, beats: IntArray, sampleRateHz: Int): Boolean {
        val before = sampleRateHz * 350 / 1000
        val gap = sampleRateHz * 60 / 1000
        val length = before - gap
        val edge = sampleRateHz * 20 / 1000
        val usable = beats.filter { it - before >= 0 }
        if (usable.size < MIN_BEATS) return false

        class Bump(val at: Int, val height: Double)

        fun bump(group: List<Int>): Bump? {
            if (group.isEmpty()) return null
            val average = DoubleArray(length)
            var rHeight = 0.0
            for (r in group) {
                for (k in 0 until length) average[k] += millivolts[r - before + k]
                rHeight += abs(millivolts[r] - millivolts[r - gap])
            }
            for (k in 0 until length) average[k] /= group.size
            rHeight /= group.size
            val start = average.take(edge).average()
            val end = average.takeLast(edge).average()
            var best = -1
            var bestHeight = 0.0
            val first = before - sampleRateHz * 280 / 1000
            val last = before - sampleRateHz * 100 / 1000
            for (k in first..last) {
                val line = start + (end - start) * k / (length - 1)
                val height = average[k] - line
                if (abs(height) > abs(bestHeight)) {
                    best = k
                    bestHeight = height
                }
            }
            return if (best < 0 || abs(bestHeight) < rHeight * P_WAVE_SHARE) null else Bump(best, bestHeight)
        }

        val even = bump(usable.filterIndexed { index, _ -> index % 2 == 0 }) ?: return false
        val odd = bump(usable.filterIndexed { index, _ -> index % 2 == 1 }) ?: return false
        return even.height * odd.height > 0 && abs(even.at - odd.at) <= sampleRateHz * 40 / 1000
    }

    private const val MIN_SECONDS = 10
    private const val MIN_BEATS = 8
    private const val MIN_JUMPS = 6

    /** The level the R waves reach: with a beat a second, well over 2 % of the samples lie in one. */
    private const val UPPER_LEVEL = 0.98
    private const val THRESHOLD_SHARE = 0.3

    /** Noise alone gives about 1.5 between the two levels; an R wave over a quiet baseline gives hundreds. */
    private const val MIN_CONTRAST = 4.0

    /** 30 to 200 beats a minute. */
    private const val MIN_INTERVAL_MILLIS = 300.0
    private const val MAX_INTERVAL_MILLIS = 2000.0

    /**
     * Breathing moves the distances slowly, so neighbours differ little: under 6 % is even. A rhythm
     * without a pattern jumps by well over a tenth from beat to beat. Between the two nothing is said.
     */
    private const val EVEN_BELOW = 0.06
    private const val IRREGULAR_FROM = 0.12

    /** Above this the last beat's T wave reaches into the stretch a P wave is looked for in. */
    private const val MAX_BPM_FOR_P_WAVE = 100

    /** A P wave is about a tenth of the R wave; below a thirtieth it is not told from the baseline. */
    private const val P_WAVE_SHARE = 0.03
}
