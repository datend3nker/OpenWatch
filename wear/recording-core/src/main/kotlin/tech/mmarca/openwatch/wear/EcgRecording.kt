package tech.mmarca.openwatch.wear

/**
 * One ECG recording as the Samsung platform's tracker hands it over: [seconds] of unbroken
 * contact, at [EcgHeartRate.SAMPLE_RATE_HZ]. A lifted finger starts it over. Pure, so the rule is
 * tested off the watch; `SpotMeasurements` feeds it the points.
 */
class EcgRecording(seconds: Int = RECORD_SECONDS) {

    private val samples = FloatArray(seconds * EcgHeartRate.SAMPLE_RATE_HZ)
    private var count = 0

    /** Whether both electrodes are touched now, as the last point that said so says. */
    var contact: Boolean = false
        private set

    /** How much of the recording is done, 0 to 1. */
    val recorded: Float get() = count.toFloat() / samples.size

    val isComplete: Boolean get() = count == samples.size

    /**
     * One point: its voltage ([millivolts], NaN for none) and, where the point carries it, whether
     * the electrodes are touched ([touching], null on the points that do not say).
     */
    fun add(millivolts: Float, touching: Boolean?) {
        if (touching != null) {
            // Only an unbroken recording counts.
            if (!touching) count = 0
            contact = touching
        }
        if (!contact || millivolts.isNaN() || count == samples.size) return
        samples[count++] = millivolts
    }

    /** The last [seconds] of the recording so far, oldest first. */
    fun trace(seconds: Int): FloatArray {
        val wanted = minOf(count, seconds * EcgHeartRate.SAMPLE_RATE_HZ)
        return samples.copyOfRange(count - wanted, count)
    }

    /** The whole recording once complete. */
    fun samples(): FloatArray = samples.copyOf(count)

    companion object {
        const val RECORD_SECONDS = 30
    }
}
