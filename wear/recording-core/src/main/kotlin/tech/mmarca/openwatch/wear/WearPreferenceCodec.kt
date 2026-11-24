package tech.mmarca.openwatch.wear

import tech.mmarca.healthcalculations.insights.HeartRateZones
import tech.mmarca.healthcalculations.preferences.BiologicalSex
import tech.mmarca.healthcalculations.preferences.BodyProfile
import tech.mmarca.openwatch.wearlink.HeartRateInterval
import tech.mmarca.openwatch.wearlink.WearLinkPreferences as Preferences

/** The watch's settings in one value, as [WearSettings] keeps them. */
data class WatchPreferences(
    val heartRateDay: HeartRateInterval,
    val heartRateSource: MeasurementSource,
    val hrvSource: MeasurementSource,
    val syncedSleepHold: Boolean,
    val skinTemperature: Boolean,
    val spo2AtNight: Boolean,
    /** The zones' maximum and resting heart rate from the phone; null for none. */
    val maxHeartRate: Int? = null,
    val restingHeartRate: Int? = null,
    /** How the screens and tiles show a reading, chosen on the watch. Not a link token: the phone neither reads nor sets it. */
    val unitSystem: UnitSystem = UnitSystem.METRIC,
    /** The body profile a workout's figures need, from the phone: the weight in 100 g, the height in cm, the birth year; null for none. */
    val weightHectograms: Int? = null,
    val heightCm: Int? = null,
    val birthYear: Int? = null,
    val sex: BiologicalSex? = null,
    /** A run or a ride with a route pauses by itself when the wearer stops (`AutoPause`). On the watch only, not a link token. */
    val autoPause: Boolean = true,
    /** The watch offers to record a walk or a run it sees in the step minutes (`ActivityDetector`). On the watch only. */
    val autoDetect: Boolean = true,
) {
    /**
     * The body profile the phone sent, as health-calculations reads it: what a workout's
     * energy, training load and recovery rest on ([tech.mmarca.openwatch.wearlink.WorkoutMetrics]).
     * The zones' heart rates are not in it: they are the zones' own ([zoneBasis]).
     */
    fun bodyProfile(): BodyProfile = BodyProfile(
        birthYear = birthYear,
        weightKg = weightHectograms?.let { it / HECTOGRAMS_PER_KG },
        heightCm = heightCm?.toDouble(),
        sex = sex,
    )

    /**
     * What the heart rate zones rest on: the phone's maximum and resting rate;
     * null until the phone sends a maximum, and then no screen draws or counts
     * zones rather than zones on a guess. Every screen that does takes it from
     * here, so they agree, and follow a figure the phone sends.
     */
    fun zoneBasis(): HeartRateZones.Basis? = maxHeartRate?.let { HeartRateZones.Basis(it, restingHeartRate) }

    private companion object {
        const val HECTOGRAMS_PER_KG = 10.0
    }
}

/** What the watch can offer, which the phone reads but cannot change. */
/** [ppgGranted]: the grant the platform checks for its green PPG, which only the watch can ask for. */
data class WatchAbilities(
    val samsung: Boolean,
    val skinGranted: Boolean,
    val spo2Granted: Boolean,
    val ppgGranted: Boolean = false,
)

/**
 * The watch's settings as the `GET_PREF` and `SET_PREF` tokens
 * ([Preferences]): the phone's settings screen for the watch. Pure.
 */
object WearPreferenceCodec {

    fun encode(preferences: WatchPreferences, abilities: WatchAbilities): Map<String, String> = linkedMapOf(
        Preferences.HEART_RATE_DAY to preferences.heartRateDay.wire,
        Preferences.HEART_RATE_SOURCE to source(preferences.heartRateSource),
        Preferences.HRV_SOURCE to source(preferences.hrvSource),
        Preferences.SLEEP_HOLD to if (preferences.syncedSleepHold) Preferences.SYNCED else Preferences.LOCK,
        Preferences.SKIN_TEMPERATURE to flag(preferences.skinTemperature),
        Preferences.SPO2 to flag(preferences.spo2AtNight),
        Preferences.HEART_RATE_MAX to (preferences.maxHeartRate ?: 0).toString(),
        Preferences.HEART_RATE_REST to (preferences.restingHeartRate ?: 0).toString(),
        Preferences.BODY_WEIGHT to (preferences.weightHectograms ?: 0).toString(),
        Preferences.BODY_HEIGHT to (preferences.heightCm ?: 0).toString(),
        Preferences.BIRTH_YEAR to (preferences.birthYear ?: 0).toString(),
        Preferences.SEX to sex(preferences.sex),
        Preferences.SAMSUNG_AVAILABLE to flag(abilities.samsung),
        Preferences.SKIN_GRANTED to flag(abilities.skinGranted),
        Preferences.SPO2_GRANTED to flag(abilities.spo2Granted),
    )

    /**
     * [preferences] with [key] set to [value], or null for a key the phone may
     * not change or a value it does not take. A Samsung choice on a watch
     * without the platform is refused.
     */
    fun apply(preferences: WatchPreferences, abilities: WatchAbilities, key: String, value: String): WatchPreferences? = when (key) {
        Preferences.HEART_RATE_DAY -> HeartRateInterval.fromWire(value)
            ?.takeIf { it == HeartRateInterval.EVERY_10_SECONDS || it == HeartRateInterval.POWER_SAVE }
            ?.let { preferences.copy(heartRateDay = it) }
        Preferences.HEART_RATE_SOURCE -> source(value, abilities)
            ?.takeIf { it != MeasurementSource.SAMSUNG_PPG }?.let { preferences.copy(heartRateSource = it) }
        Preferences.HRV_SOURCE -> source(value, abilities)?.let { preferences.copy(hrvSource = it) }
        Preferences.SLEEP_HOLD -> when (value) {
            Preferences.LOCK -> preferences.copy(syncedSleepHold = false)
            Preferences.SYNCED -> preferences.copy(syncedSleepHold = true)
            else -> null
        }
        Preferences.SKIN_TEMPERATURE -> flag(value)?.takeIf { !it || abilities.samsung }?.let { preferences.copy(skinTemperature = it) }
        Preferences.SPO2 -> flag(value)?.takeIf { !it || abilities.samsung }?.let { preferences.copy(spo2AtNight = it) }
        Preferences.HEART_RATE_MAX -> bpm(value, MAX_HEART_RATE_RANGE)?.let { preferences.copy(maxHeartRate = it.takeIf { bpm -> bpm > 0 }) }
        Preferences.HEART_RATE_REST -> bpm(value, RESTING_HEART_RATE_RANGE)?.let { preferences.copy(restingHeartRate = it.takeIf { bpm -> bpm > 0 }) }
        Preferences.BODY_WEIGHT -> whole(value, WEIGHT_HECTOGRAM_RANGE)?.let { preferences.copy(weightHectograms = it.takeIf { hg -> hg > 0 }) }
        Preferences.BODY_HEIGHT -> whole(value, HEIGHT_CM_RANGE)?.let { preferences.copy(heightCm = it.takeIf { cm -> cm > 0 }) }
        Preferences.BIRTH_YEAR -> whole(value, BIRTH_YEAR_RANGE)?.let { preferences.copy(birthYear = it.takeIf { year -> year > 0 }) }
        Preferences.SEX -> when (value) {
            Preferences.FEMALE -> preferences.copy(sex = BiologicalSex.FEMALE)
            Preferences.MALE -> preferences.copy(sex = BiologicalSex.MALE)
            Preferences.OFF -> preferences.copy(sex = null)
            else -> null
        }
        else -> null
    }

    private val MAX_HEART_RATE_RANGE = 100..230
    private val RESTING_HEART_RATE_RANGE = 25..120

    /** 20 to 400 kg, in 100 g. */
    private val WEIGHT_HECTOGRAM_RANGE = 200..4000
    private val HEIGHT_CM_RANGE = 80..260
    private val BIRTH_YEAR_RANGE = 1900..2100

    /** A whole bpm within [range], or `0` for none; null for anything else. */
    private fun bpm(value: String, range: IntRange): Int? = whole(value, range)

    /** A whole number within [range], or `0` for none; null for anything else. */
    private fun whole(value: String, range: IntRange): Int? = value.toIntOrNull()?.takeIf { it == 0 || it in range }

    private fun sex(sex: BiologicalSex?): String = when (sex) {
        BiologicalSex.FEMALE -> Preferences.FEMALE
        BiologicalSex.MALE -> Preferences.MALE
        null -> Preferences.OFF
    }

    private fun source(source: MeasurementSource): String = when (source) {
        MeasurementSource.RAW_SENSOR -> Preferences.SENSOR
        MeasurementSource.SAMSUNG_PLATFORM -> Preferences.SAMSUNG
        MeasurementSource.SAMSUNG_PPG -> Preferences.SAMSUNG_PPG
    }

    private fun source(value: String, abilities: WatchAbilities): MeasurementSource? = when (value) {
        Preferences.SENSOR -> MeasurementSource.RAW_SENSOR
        Preferences.SAMSUNG -> MeasurementSource.SAMSUNG_PLATFORM.takeIf { abilities.samsung }
        // Needs a grant only the watch can ask for.
        Preferences.SAMSUNG_PPG -> MeasurementSource.SAMSUNG_PPG.takeIf { abilities.samsung && abilities.ppgGranted }
        else -> null
    }

    private fun flag(on: Boolean): String = if (on) Preferences.ON else Preferences.OFF

    private fun flag(value: String): Boolean? = when (value) {
        Preferences.ON -> true
        Preferences.OFF -> false
        else -> null
    }
}
