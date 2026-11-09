package tech.mmarca.openwatch.wear.features.measure

import tech.mmarca.openwatch.wear.HrvRmssd

/** Readings a heart rate measurement needs before it settles. */
internal const val HeartRateReadingsNeeded = 8

/** Clean intervals the live HRV figure needs before it shows; fewer means too short or too noisy. */
internal const val HrvIntervalsNeeded = 30

/**
 * The settled pulse from a run of readings: the median of the last five, so
 * one stray reading while the sensor locks on does not move it. Zero readings
 * mean "no signal" and are ignored.
 */
internal fun settledHeartRate(readings: List<Double>): Double? {
    val valid = readings.filter { it in 25.0..250.0 }
    if (valid.size < HeartRateReadingsNeeded) return null
    return valid.takeLast(5).sorted()[2]
}

/** Beat-to-beat intervals in milliseconds from beat timestamps in nanoseconds. */
internal fun rrIntervalsMillis(beatTimestampsNanos: List<Long>): List<Double> =
    beatTimestampsNanos.zipWithNext { a, b -> (b - a) / 1_000_000.0 }

/**
 * RMSSD in milliseconds from beat timestamps in nanoseconds, by the same
 * rules as the night windows ([HrvRmssd]): intervals no heart produces and
 * ectopic ones are judged against their local median, and only neighbouring
 * intervals form a pair, so a removed beat never joins its two sides.
 * Null when fewer than [minCleanBeats] clean intervals remain.
 */
internal fun rmssd(beatTimestampsNanos: List<Long>, minCleanBeats: Int): Double? =
    HrvRmssd.of(DoubleArray(beatTimestampsNanos.size) { beatTimestampsNanos[it] / 1_000_000.0 }, minCleanBeats)?.rmssdMillis

/**
 * Clean intervals a 3-minute reading needs: the night window's 100 is for five
 * minutes, and three minutes at rest hold about 60 percent of that.
 */
internal const val HrvBeatsNeededForReading = 60
