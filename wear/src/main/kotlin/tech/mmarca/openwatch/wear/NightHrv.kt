package tech.mmarca.openwatch.wear

/**
 * One night-time HRV window from the raw PPG: beats, then RMSSD. The
 * recorder on the watch collects the samples; everything else is here, so it
 * can be tested with a synthetic pulse.
 */
object NightHrv {

    /** How long one window listens. */
    const val WINDOW_MILLIS = 5L * 60 * 1000

    /** Null when the signal had a gap, no rhythm, or too few clean beats. */
    fun analyze(timesMillis: DoubleArray, values: DoubleArray): HrvWindowResult? {
        val beats = PpgBeats.detect(timesMillis, values) ?: return null
        return HrvRmssd.of(beats)
    }

    /** The breathing rate of the same window by both routes; see [RespiratoryRate]. */
    fun respiration(timesMillis: DoubleArray, values: DoubleArray): RespiratoryRate.Window =
        RespiratoryRate.of(PpgBeats.detect(timesMillis, values), timesMillis, values)
}
