package tech.mmarca.openwatch.wear

import android.content.Context
import tech.mmarca.openwatch.wearlink.HeartRateInterval

/**
 * The choices made on the watch's status screen, kept on the watch.
 *
 * - [heartRateDay]: every 10 seconds outside bedtime mode (the default, as
 *   in OpenVitals) or power save, the one setting that may cost data: the
 *   heart rate's windows by day and the quiet beat at night ([WakeClock]).
 *   Bedtime mode always measures at the full rate, because the sleep
 *   pipeline reads every sample (`HeartRateSchedule`).
 * - [heartRateSource] and [hrvSource]: the raw Android sensors by default.
 *   The Samsung Health Platform is offered only on a watch that has it, and
 *   only by choice, because outside the platform's Developer Mode the
 *   platform asks Samsung about this app before it answers, and the app
 *   cannot tell which mode is on.
 */
internal object WearSettings {

    enum class Source { RAW_SENSOR, SAMSUNG_PLATFORM }

    private const val PREFS = "wear_settings"
    private const val KEY_HEART_RATE_DAY = "heart_rate_day"
    private const val KEY_HEART_RATE_SOURCE = "heart_rate_source"
    private const val KEY_HRV_SOURCE = "hrv_source"
    private const val KEY_SYNCED_SLEEP_HOLD = "synced_sleep_hold"
    private const val KEY_SKIN_TEMPERATURE = "skin_temperature"
    private const val KEY_SPO2 = "spo2_at_night"

    fun heartRateDay(context: Context): HeartRateInterval =
        prefs(context).getString(KEY_HEART_RATE_DAY, null)?.let(HeartRateInterval::fromWire)
            ?.takeIf { it != HeartRateInterval.OFF } ?: HeartRateInterval.EVERY_10_SECONDS

    fun setHeartRateDay(context: Context, interval: HeartRateInterval) {
        prefs(context).edit().putString(KEY_HEART_RATE_DAY, interval.wire).apply()
    }

    fun heartRateSource(context: Context): Source = source(context, KEY_HEART_RATE_SOURCE)

    fun setHeartRateSource(context: Context, source: Source) = setSource(context, KEY_HEART_RATE_SOURCE, source)

    fun hrvSource(context: Context): Source = source(context, KEY_HRV_SOURCE)

    fun setHrvSource(context: Context, source: Source) = setSource(context, KEY_HRV_SOURCE, source)

    /**
     * How a sleep hold keeps every minute whole: false (the default) with a
     * lasting wake lock, true with the [WakeClock]'s dense beat and a flush
     * of every sensor at each ([SensorWakeSync]), which lets the processor
     * sleep in between. The second is the one to measure; it falls back to
     * the lock while there is no dense beat (no heart rate grant).
     */
    fun syncedSleepHold(context: Context): Boolean = prefs(context).getBoolean(KEY_SYNCED_SLEEP_HOLD, false)

    fun setSyncedSleepHold(context: Context, synced: Boolean) {
        prefs(context).edit().putBoolean(KEY_SYNCED_SLEEP_HOLD, synced).apply()
    }

    /** Skin temperature at night from the Samsung platform, the only source. Off by default. */
    fun skinTemperature(context: Context): Boolean = prefs(context).getBoolean(KEY_SKIN_TEMPERATURE, false)

    fun setSkinTemperature(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_SKIN_TEMPERATURE, on).apply()
    }

    /** SpO2 spot measurements at night from the Samsung platform, the only source. Off by default. */
    fun spo2AtNight(context: Context): Boolean = prefs(context).getBoolean(KEY_SPO2, false)

    fun setSpo2AtNight(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_SPO2, on).apply()
    }

    /** Every setting in one value, for the phone's `GET_PREF`. */
    fun read(context: Context): WatchPreferences = WatchPreferences(
        heartRateDay = heartRateDay(context),
        heartRateSource = heartRateSource(context),
        hrvSource = hrvSource(context),
        syncedSleepHold = syncedSleepHold(context),
        skinTemperature = skinTemperature(context),
        spo2AtNight = spo2AtNight(context),
    )

    /** Stores every setting of [preferences], for the phone's `SET_PREF`. */
    fun write(context: Context, preferences: WatchPreferences) {
        setHeartRateDay(context, preferences.heartRateDay)
        setHeartRateSource(context, preferences.heartRateSource)
        setHrvSource(context, preferences.hrvSource)
        setSyncedSleepHold(context, preferences.syncedSleepHold)
        setSkinTemperature(context, preferences.skinTemperature)
        setSpo2AtNight(context, preferences.spo2AtNight)
    }

    private fun source(context: Context, key: String): Source {
        val stored = prefs(context).getString(key, null)
        return Source.entries.firstOrNull { it.name == stored } ?: Source.RAW_SENSOR
    }

    private fun setSource(context: Context, key: String, source: Source) {
        prefs(context).edit().putString(key, source.name).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
