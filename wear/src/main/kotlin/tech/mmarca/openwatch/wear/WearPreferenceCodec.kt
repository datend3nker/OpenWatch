package tech.mmarca.openwatch.wear

import tech.mmarca.openwatch.wearlink.HeartRateInterval
import tech.mmarca.openwatch.wearlink.WearLinkProtocol.Preferences

/** The watch's settings in one value, as [WearSettings] keeps them. */
internal data class WatchPreferences(
    val heartRateDay: HeartRateInterval,
    val heartRateSource: WearSettings.Source,
    val hrvSource: WearSettings.Source,
    val syncedSleepHold: Boolean,
    val skinTemperature: Boolean,
    val spo2AtNight: Boolean,
    /** The zones' maximum and resting heart rate from the phone; null for none. */
    val maxHeartRate: Int? = null,
    val restingHeartRate: Int? = null,
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
        Preferences.SLEEP_HOLD to if (preferences.syncedSleepHold) Preferences.SYNCED else Preferences.LOCK,
        Preferences.SKIN_TEMPERATURE to flag(preferences.skinTemperature),
        Preferences.SPO2 to flag(preferences.spo2AtNight),
        Preferences.HEART_RATE_MAX to (preferences.maxHeartRate ?: 0).toString(),
        Preferences.HEART_RATE_REST to (preferences.restingHeartRate ?: 0).toString(),
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
        Preferences.SLEEP_HOLD -> when (value) {
            Preferences.LOCK -> preferences.copy(syncedSleepHold = false)
            Preferences.SYNCED -> preferences.copy(syncedSleepHold = true)
            else -> null
        }
        Preferences.SKIN_TEMPERATURE -> flag(value)?.takeIf { !it || abilities.samsung }?.let { preferences.copy(skinTemperature = it) }
        Preferences.SPO2 -> flag(value)?.takeIf { !it || abilities.samsung }?.let { preferences.copy(spo2AtNight = it) }
        Preferences.HEART_RATE_MAX -> bpm(value, MAX_HEART_RATE_RANGE)?.let { preferences.copy(maxHeartRate = it.takeIf { bpm -> bpm > 0 }) }
        Preferences.HEART_RATE_REST -> bpm(value, RESTING_HEART_RATE_RANGE)?.let { preferences.copy(restingHeartRate = it.takeIf { bpm -> bpm > 0 }) }
        else -> null
    }

    private val MAX_HEART_RATE_RANGE = 100..230
    private val RESTING_HEART_RATE_RANGE = 25..120

    /** A whole bpm within [range], or `0` for none; null for anything else. */
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
