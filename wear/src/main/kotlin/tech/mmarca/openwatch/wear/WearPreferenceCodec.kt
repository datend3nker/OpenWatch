package tech.mmarca.openwatch.wear

import tech.mmarca.openwatch.wearlink.HeartRateInterval
import tech.mmarca.openwatch.wearlink.WearLinkPreferences as Preferences

/** The watch's settings in one value, as [WearSettings] keeps them. */
internal data class WatchPreferences(
    val heartRateDay: HeartRateInterval,
    val heartRateSource: WearSettings.Source,
    val hrvSource: WearSettings.Source,
    val skinTemperature: Boolean,
    val spo2AtNight: Boolean,
    /** The zones' maximum and resting heart rate from the phone; null for none. */
    val maxHeartRate: Int? = null,
    val restingHeartRate: Int? = null,
    /** The profile for a workout's energy, from the phone; null for none. */
    val weightHectograms: Int? = null,
    val heightCm: Int? = null,
    val birthYear: Int? = null,
    val female: Boolean? = null,
)

/** What the watch can offer, which the phone reads but cannot change. */
internal data class WatchAbilities(val samsung: Boolean, val skinGranted: Boolean, val spo2Granted: Boolean)

/**
 * The watch's settings as the `GET_PREF` and `SET_PREF` tokens
 * ([Preferences]): the phone's settings screen for the watch. Pure.
 */
internal object WearPreferenceCodec {

    fun encode(preferences: WatchPreferences, abilities: WatchAbilities): Map<String, String> = linkedMapOf(
        Preferences.HEART_RATE_DAY to preferences.heartRateDay.wire,
        Preferences.HEART_RATE_SOURCE to source(preferences.heartRateSource),
        Preferences.HRV_SOURCE to source(preferences.hrvSource),
        Preferences.SKIN_TEMPERATURE to flag(preferences.skinTemperature),
        Preferences.SPO2 to flag(preferences.spo2AtNight),
        Preferences.HEART_RATE_MAX to (preferences.maxHeartRate ?: 0).toString(),
        Preferences.HEART_RATE_REST to (preferences.restingHeartRate ?: 0).toString(),
        Preferences.BODY_WEIGHT to (preferences.weightHectograms ?: 0).toString(),
        Preferences.BODY_HEIGHT to (preferences.heightCm ?: 0).toString(),
        Preferences.BIRTH_YEAR to (preferences.birthYear ?: 0).toString(),
        Preferences.SEX to when (preferences.female) { true -> "f"; false -> "m"; null -> "0" },
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
        Preferences.HEART_RATE_SOURCE -> source(value, abilities)?.let { preferences.copy(heartRateSource = it) }
        Preferences.HRV_SOURCE -> source(value, abilities)?.let { preferences.copy(hrvSource = it) }
        Preferences.SKIN_TEMPERATURE -> flag(value)?.takeIf { !it || abilities.samsung }?.let { preferences.copy(skinTemperature = it) }
        Preferences.SPO2 -> flag(value)?.takeIf { !it || abilities.samsung }?.let { preferences.copy(spo2AtNight = it) }
        Preferences.HEART_RATE_MAX -> bpm(value, MAX_HEART_RATE_RANGE)?.let { preferences.copy(maxHeartRate = it.takeIf { bpm -> bpm > 0 }) }
        Preferences.HEART_RATE_REST -> bpm(value, RESTING_HEART_RATE_RANGE)?.let { preferences.copy(restingHeartRate = it.takeIf { bpm -> bpm > 0 }) }
        Preferences.BODY_WEIGHT -> bpm(value, WEIGHT_HECTOGRAM_RANGE)?.let { preferences.copy(weightHectograms = it.takeIf { v -> v > 0 }) }
        Preferences.BODY_HEIGHT -> bpm(value, HEIGHT_CM_RANGE)?.let { preferences.copy(heightCm = it.takeIf { v -> v > 0 }) }
        Preferences.BIRTH_YEAR -> bpm(value, BIRTH_YEAR_RANGE)?.let { preferences.copy(birthYear = it.takeIf { v -> v > 0 }) }
        Preferences.SEX -> when (value) {
            "f" -> preferences.copy(female = true)
            "m" -> preferences.copy(female = false)
            "0" -> preferences.copy(female = null)
            else -> null
        }
        else -> null
    }

    private val WEIGHT_HECTOGRAM_RANGE = 200..4000
    private val HEIGHT_CM_RANGE = 80..260
    private val BIRTH_YEAR_RANGE = 1900..2100
    private val MAX_HEART_RATE_RANGE = 100..230
    private val RESTING_HEART_RATE_RANGE = 25..120

    /** A whole number within [range], or `0` for none; null for anything else. */
    private fun bpm(value: String, range: IntRange): Int? = value.toIntOrNull()?.takeIf { it == 0 || it in range }

    private fun source(source: WearSettings.Source): String =
        if (source == WearSettings.Source.SAMSUNG_PLATFORM) Preferences.SAMSUNG else Preferences.SENSOR

    private fun source(value: String, abilities: WatchAbilities): WearSettings.Source? = when (value) {
        Preferences.SENSOR -> WearSettings.Source.RAW_SENSOR
        Preferences.SAMSUNG -> WearSettings.Source.SAMSUNG_PLATFORM.takeIf { abilities.samsung }
        else -> null
    }

    private fun flag(on: Boolean): String = if (on) Preferences.ON else Preferences.OFF

    private fun flag(value: String): Boolean? = when (value) {
        Preferences.ON -> true
        Preferences.OFF -> false
        else -> null
    }
}
