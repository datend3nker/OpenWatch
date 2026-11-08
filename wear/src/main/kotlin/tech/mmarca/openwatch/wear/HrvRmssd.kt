package tech.mmarca.openwatch.wear

import kotlin.math.abs
import kotlin.math.sqrt

/** What one window of beats gave: the RMSSD and how many beats it rests on. */
data class HrvWindowResult(
    val rmssdMillis: Double,
    /** Beat-to-beat intervals that took part in at least one clean successive pair. */
    val beatCount: Int,
)

/**
 * RMSSD from beat times, after the intervals that cannot be a resting heart
 * are taken out.
 *
 * RMSSD is the root mean square of the differences between successive
 * normal-to-normal intervals. Its definition and the need to remove ectopic
 * beats before computing it are standard (Task Force of the ESC and NASPE,
 * "Heart rate variability: standards of measurement", Circulation 93(5),
 * 1996, doi:10.1161/01.CIR.93.5.1043). The field's usual normative tables
 * (e.g. Shaffer & Ginsberg, "An Overview of Heart Rate Variability Metrics
 * and Norms", Frontiers in Public Health 5:258, 2017,
 * doi:10.3389/fpubh.2017.00258) are for 24-hour or multi-minute recordings
 * with their own population; they say explicitly that short-window norms
 * are not interchangeable with those, so this file sets no "normal RMSSD"
 * range and none should be read off those tables for a 5-minute window.
 *
 * That a single artefact can bias RMSSD more than other HRV metrics is shown
 * empirically (e.g. Peltola, "Role of editing of R-R intervals in the
 * analysis of heart rate variability", Frontiers in Physiology, and the 2021
 * Journal of Sports Science and Medicine study on artefact sensitivity of
 * RMSSD and SDNN), but no single paper fixes the correction thresholds
 * below. There is no universally agreed artefact-rejection method (every
 * source above says so); the specific numbers here (a quarter of the local
 * median, a 30% successive step, five-beat neighbourhood, minimum beat
 * counts) are this app's own choice, built in the spirit of Kubios'
 * threshold correction but not copied from it, and not validated against an
 * ECG or chest-strap reference.
 */
object HrvRmssd {

    /** 200 bpm. Not a physiological artefact bound from a paper, just this file's outer plausibility limit. */
    const val MIN_INTERVAL_MILLIS = 300.0

    /** 30 bpm. Same caveat as [MIN_INTERVAL_MILLIS]. */
    const val MAX_INTERVAL_MILLIS = 2000.0

    /** An interval this far from the local median is an artefact. This app's own choice, not from a paper. */
    const val MAX_DEVIATION_FROM_MEDIAN = 0.25

    /**
     * A successive difference above this share of the local median is no resting rhythm.
     * This app's own choice, not from a paper.
     */
    const val MAX_STEP_OF_MEDIAN = 0.30

    /** Intervals on each side that make the local median. */
    private const val MEDIAN_RADIUS = 5

    /**
     * Fewer clean beats than this and the window is dropped (a five-minute window at rest holds 250 to
     * 350). This app's own choice, not from a paper.
     */
    const val MIN_CLEAN_BEATS = 100

    /** Share of detected intervals that must be clean, else the signal was poor. This app's own choice. */
    const val MIN_CLEAN_SHARE = 0.6

    /**
     * An RMSSD above this is treated as a detector failure, not a heart. It is also the most Health
     * Connect's `HeartRateVariabilityRmssdRecord` accepts, which is a platform limit, not a physiological
     * one; healthy RMSSD is normally well under it.
     */
    const val MAX_PLAUSIBLE_RMSSD_MILLIS = 200.0

    /**
     * [beatMillis] are beat times in milliseconds, ascending. Null when too
     * few beats are clean, or the result is not physiological. A shorter window than the night's
     * five minutes passes a lower [minCleanBeats].
     */
    fun of(beatMillis: DoubleArray, minCleanBeats: Int = MIN_CLEAN_BEATS): HrvWindowResult? {
        if (beatMillis.size < 3) return null
        val intervals = DoubleArray(beatMillis.size - 1) { beatMillis[it + 1] - beatMillis[it] }
        val good = cleanIntervals(intervals)
        var sumSquares = 0.0
        var pairs = 0
        val used = BooleanArray(intervals.size)
        for (i in 0 until intervals.size - 1) {
            if (!good[i] || !good[i + 1]) continue
            val step = intervals[i + 1] - intervals[i]
            if (abs(step) > MAX_STEP_OF_MEDIAN * localMedian(intervals, i)) continue
            sumSquares += step * step
            pairs++
            used[i] = true
            used[i + 1] = true
        }
        val beats = used.count { it }
        if (pairs == 0 || beats < minCleanBeats || beats < MIN_CLEAN_SHARE * intervals.size) return null
        val rmssd = sqrt(sumSquares / pairs)
        if (rmssd > MAX_PLAUSIBLE_RMSSD_MILLIS) return null
        return HrvWindowResult(rmssd, beats)
    }

    private fun cleanIntervals(intervals: DoubleArray): BooleanArray = BooleanArray(intervals.size) { i ->
        val value = intervals[i]
        value in MIN_INTERVAL_MILLIS..MAX_INTERVAL_MILLIS &&
            abs(value - localMedian(intervals, i)) <= MAX_DEVIATION_FROM_MEDIAN * localMedian(intervals, i)
    }

    /** Median of the plausible intervals around [index], the interval itself included. */
    private fun localMedian(intervals: DoubleArray, index: Int): Double {
        val from = maxOf(0, index - MEDIAN_RADIUS)
        val to = minOf(intervals.size - 1, index + MEDIAN_RADIUS)
        val near = (from..to).map { intervals[it] }.filter { it in MIN_INTERVAL_MILLIS..MAX_INTERVAL_MILLIS }.sorted()
        if (near.isEmpty()) return intervals[index]
        val middle = near.size / 2
        return if (near.size % 2 == 1) near[middle] else (near[middle - 1] + near[middle]) / 2
    }
}
