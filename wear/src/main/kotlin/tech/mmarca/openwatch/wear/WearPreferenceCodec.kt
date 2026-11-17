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
    /** The daily goals the rings are drawn against; null for no step or energy goal. */
    val stepGoal: Int? = WearSettings.DEFAULT_STEP_GOAL,
    val activeKcalGoal: Int? = WearSettings.DEFAULT_ACTIVE_KCAL_GOAL,
    val waterGoalMl: Int = WearSettings.DEFAULT_WATER_GOAL_ML,
)

/** What the watch can offer, which the phone reads but cannot change. */
internal data class WatchAbilities(
    val samsung: Boolean,
    val skinGranted: Boolean,
    val spo2Granted: Boolean,
    val ppgGranted: Boolean = false,
)

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
        Preferences.GOAL_STEPS to (preferences.stepGoal ?: 0).toString(),
        Preferences.GOAL_ACTIVE_KCAL to (preferences.activeKcalGoal ?: 0).toString(),
        Preferences.GOAL_WATER_ML to preferences.waterGoalMl.toString(),
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
            ?.takeIf { it != WearSettings.Source.SAMSUNG_PPG }?.let { preferences.copy(heartRateSource = it) }
        Preferences.HRV_SOURCE -> source(value, abilities)?.let { preferences.copy(hrvSource = it) }
        Preferences.SKIN_TEMPERATURE -> flag(value)?.takeIf { !it || abilities.samsung }?.let { preferences.copy(skinTemperature = it) }
        Preferences.SPO2 -> flag(value)?.takeIf { !it || abilities.samsung }?.let { preferences.copy(spo2AtNight = it) }
        Preferences.HEART_RATE_MAX -> bpm(value, MAX_HEART_RATE_RANGE)?.let { preferences.copy(maxHeartRate = it.takeIf { bpm -> bpm > 0 }) }
        Preferences.HEART_RATE_REST -> bpm(value, RESTING_HEART_RATE_RANGE)?.let { preferences.copy(restingHeartRate = it.takeIf { bpm -> bpm > 0 }) }
        Preferences.BODY_WEIGHT -> bpm(value, WEIGHT_HECTOGRAM_RANGE)?.let { preferences.copy(weightHectograms = it.takeIf { v -> v > 0 }) }
        Preferences.BODY_HEIGHT -> bpm(value, HEIGHT_CM_RANGE)?.let { preferences.copy(heightCm = it.takeIf { v -> v > 0 }) }
        Preferences.BIRTH_YEAR -> bpm(value, BIRTH_YEAR_RANGE)?.let { preferences.copy(birthYear = it.takeIf { v -> v > 0 }) }
        Preferences.GOAL_STEPS -> bpm(value, STEP_GOAL_RANGE)?.let { preferences.copy(stepGoal = it.takeIf { v -> v > 0 }) }
        Preferences.GOAL_ACTIVE_KCAL -> bpm(value, ACTIVE_KCAL_GOAL_RANGE)?.let { preferences.copy(activeKcalGoal = it.takeIf { v -> v > 0 }) }
        Preferences.GOAL_WATER_ML -> value.toIntOrNull()?.takeIf { it in WATER_GOAL_RANGE }?.let { preferences.copy(waterGoalMl = it) }
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
    // As wide as OpenLink's keys, so a goal another app shares always fits.
    private val STEP_GOAL_RANGE = 100..100_000
    private val ACTIVE_KCAL_GOAL_RANGE = 10..5_000
    private val WATER_GOAL_RANGE = 100..10_000

    /** A whole number within [range], or `0` for none; null for anything else. */
    private fun bpm(value: String, range: IntRange): Int? = value.toIntOrNull()?.takeIf { it == 0 || it in range }

    private fun source(source: WearSettings.Source): String = when (source) {
        WearSettings.Source.RAW_SENSOR -> Preferences.SENSOR
        WearSettings.Source.SAMSUNG_PLATFORM -> Preferences.SAMSUNG
        WearSettings.Source.SAMSUNG_PPG -> Preferences.SAMSUNG_PPG
    }

    private fun source(value: String, abilities: WatchAbilities): WearSettings.Source? = when (value) {
        Preferences.SENSOR -> WearSettings.Source.RAW_SENSOR
        Preferences.SAMSUNG -> WearSettings.Source.SAMSUNG_PLATFORM.takeIf { abilities.samsung }
        // Needs a permission only the watch can ask for.
        Preferences.SAMSUNG_PPG -> WearSettings.Source.SAMSUNG_PPG.takeIf { abilities.samsung && abilities.ppgGranted }
        else -> null
    }

    private fun flag(on: Boolean): String = if (on) Preferences.ON else Preferences.OFF

    private fun flag(value: String): Boolean? = when (value) {
        Preferences.ON -> true
        Preferences.OFF -> false
        else -> null
    }
}
