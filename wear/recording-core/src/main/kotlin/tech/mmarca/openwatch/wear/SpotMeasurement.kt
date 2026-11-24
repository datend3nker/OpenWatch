package tech.mmarca.openwatch.wear

import tech.mmarca.healthcalculations.signal.HrvRmssd
import tech.mmarca.healthcalculations.signal.HrvWindowResult
import tech.mmarca.openwatch.wearlink.WearLinkProtocol

/** What a running spot measurement ([SpotKind]) reports, and how it ends. */
sealed interface SpotEvent {
    /** A value to show while it runs: the pulse now, or the pressure now. */
    data class Live(val value: Double) : SpotEvent

    /** The sensor gives nothing usable yet: still locking on, or ([offBody]) not on a wrist. */
    data class Waiting(val offBody: Boolean) : SpotEvent

    /**
     * How far a measurement that counts its own progress has come ([share], 0 to 1), rather than
     * the time passed, and what its contact lacks now ([hint], null for nothing).
     */
    data class Progress(val share: Float, val hint: SpotHint? = null) : SpotEvent

    /**
     * An ECG's trace so far: its last seconds in millivolts, oldest first ([millivolts], at
     * [EcgHeartRate.SAMPLE_RATE_HZ]), how much of the unbroken recording it needs is done
     * ([recorded], 0 to 1), and whether both electrodes are touched now ([contact]).
     */
    class Trace(val millivolts: FloatArray, val recorded: Float, val contact: Boolean) : SpotEvent

    /**
     * The reading, in the kind's unit; [secondary] goes with it, the altitude of a pressure. An
     * ECG's [value] is its heart rate and [ecg] what its trace shows; a body composition's [value]
     * is its body fat and [body] the rest. The last event.
     */
    data class Result(
        val value: Double,
        val secondary: Double? = null,
        val ecg: EcgHeartRate.Result? = null,
        val body: BodyComposition? = null,
    ) : SpotEvent

    /** No reading, and why. The last event. */
    data class Failed(val failure: SpotFailure) : SpotEvent
}

/** Why a spot measurement gave no reading. */
enum class SpotFailure {
    /** The watch has no sensor for it. */
    NOT_SUPPORTED,

    /** The grant the measurement reads under is not in: the heart rate's, or the blood oxygen's. */
    PERMISSION_DENIED,

    /** The sensor said the watch is not on a wrist. */
    OFF_BODY,

    /** The time ran out before the readings gave a value. */
    NO_SIGNAL,

    /** The arm moved too much: for an HRV ([Stillness]), or the blood oxygen's tracker said so. */
    MOVED,

    /** The night's measurement holds the sensor now (an HRV window, a blood oxygen); the spot waits for it to end. */
    BUSY,

    /** The sensor could not be started. */
    FAILED,

    /** A body composition needs the wearer's height, weight, year of birth and sex, which the phone sends. */
    NO_PROFILE,
}

/** What a measurement's contact lacks now, as the Samsung platform's trackers report it. */
enum class SpotHint {
    /** The ECG: a finger on the upper button, the back of the watch on the wrist. */
    TOUCH_ELECTRODES,

    /** The watch is loose or off the wrist. */
    WRIST,

    /** Two fingers of the other hand are not on the two buttons. */
    FINGERS,

    /** The fingers are too dry to measure through. */
    FINGERS_DRY,

    /** The hands, or a hand and the body, touch each other. */
    HANDS_TOUCH,

    /** A finger touches the watch's frame. */
    FRAME_TOUCHED,
}

/**
 * A body composition as the Samsung platform computes it from the impedance and the wearer's
 * profile. Masses in kilograms, the basal metabolic rate in kilocalories a day. The body fat,
 * the lean (fat-free) mass and the body water go to Health Connect, as the scale's do; the
 * skeletal muscle and the basal rate are shown only.
 */
data class BodyComposition(
    val bodyFatPercent: Double,
    val leanMassKg: Double,
    val bodyWaterKg: Double,
    val skeletalMuscleKg: Double?,
    val basalMetabolicRateKcal: Double?,
)

/** When a spot measurement has its reading, and what the reading is. Pure. */
object SpotRules {

    /** Readings a heart rate needs before it settles. */
    const val HEART_RATE_READINGS = 8

    /** The newest readings the settled pulse is the median of. */
    private const val SETTLED_OVER = 5

    /**
     * Clean beats a spot HRV's three minutes must rest on: a night's
     * five-minute window needs 100, and three minutes at rest hold about 60
     * percent of that.
     */
    const val HRV_MIN_CLEAN_BEATS = 60

    /** Pressure readings the reading averages, one a second. */
    const val PRESSURE_READINGS = 5

    /**
     * The settled pulse from [readings], oldest first: once
     * [HEART_RATE_READINGS] of them are a heart's, the median of the newest
     * five, so a stray reading while the sensor locks on does not move it.
     * Null until then.
     */
    fun settledHeartRate(readings: List<Int>): Double? {
        val valid = readings.filter { it in WearLinkProtocol.MIN_BPM..WearLinkProtocol.MAX_BPM }
        if (valid.size < HEART_RATE_READINGS) return null
        return valid.takeLast(SETTLED_OVER).sorted()[SETTLED_OVER / 2].toDouble()
    }

    /**
     * The RMSSD in milliseconds of the beats at [beatMillis], ascending, as
     * health-calculations takes a night window's ([HrvRmssd]), on at least
     * [HRV_MIN_CLEAN_BEATS] clean beats. Null when too few are clean.
     */
    fun rmssd(beatMillis: List<Double>): Double? = rmssdWindow(beatMillis)?.rmssdMillis

    /** [rmssd] with the clean beats it rests on. */
    fun rmssdWindow(beatMillis: List<Double>): HrvWindowResult? =
        HrvRmssd.of(beatMillis.toDoubleArray(), minCleanBeats = HRV_MIN_CLEAN_BEATS)

    /**
     * The air pressure of [hectopascals], the mean of the readings, and the
     * altitude it stands for at standard sea-level pressure
     * ([AscentMinuteCounter.altitudeMetres]). Null for fewer than
     * [PRESSURE_READINGS].
     */
    fun airPressure(hectopascals: List<Float>): AirPressure? {
        if (hectopascals.size < PRESSURE_READINGS) return null
        val mean = hectopascals.map { it.toDouble() }.average()
        return AirPressure(mean, AscentMinuteCounter.altitudeMetres(mean))
    }

    /** A reading of the barometer: the pressure in hPa and its altitude in metres. */
    data class AirPressure(val hectopascals: Double, val altitudeMetres: Double)
}
