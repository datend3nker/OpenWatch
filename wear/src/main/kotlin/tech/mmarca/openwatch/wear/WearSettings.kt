package tech.mmarca.openwatch.wear

import android.content.Context
import tech.mmarca.openwatch.wearlink.HeartRateInterval

/**
 * The choices made on the watch's status screen, kept on the watch.
 *
 * - [heartRateDay]: how often the heart rate is measured outside bedtime
 *   mode, every 10 seconds (the default, as in OpenVitals) or power save.
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

    /**
     * How a sleep hold keeps the night recorded:
     * - [LOCK]: a lasting wake lock, every minute whole; the costliest.
     * - [SYNCED]: the [WakeClock]'s dense beat, every 40 seconds, and a flush
     *   of every sensor at each; every minute whole, the processor sleeps in
     *   between.
     * - [QUIET]: as [SYNCED] while the wearer moves; while still, the beat
     *   stretches to [WakeClock.QUIET_PERIOD_MILLIS] and minutes between two
     *   beats keep only the accelerometer's last 48 seconds (flagged sparse).
     *   A wake-up motion sensor brings the dense beat back at once.
     *
     * The two beat modes take the heart rate from the sensor while sleep is
     * held, whatever [heartRateSource] says: its batches are the beat, and a
     * second light source would only add cost. Without a beat (no heart rate
     * grant) they fall back to the lock.
     */
    enum class SleepHoldMode { LOCK, SYNCED, QUIET }

    private const val PREFS = "wear_settings"
    private const val KEY_HEART_RATE_DAY = "heart_rate_day"
    private const val KEY_HEART_RATE_SOURCE = "heart_rate_source"
    private const val KEY_HRV_SOURCE = "hrv_source"
    private const val KEY_SYNCED_SLEEP_HOLD = "synced_sleep_hold"
    private const val KEY_SLEEP_HOLD = "sleep_hold"
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

    /** The [SleepHoldMode]; a watch that stored only the older on/off switch keeps its choice. */
    fun sleepHold(context: Context): SleepHoldMode {
        val prefs = prefs(context)
        prefs.getString(KEY_SLEEP_HOLD, null)?.let { stored -> SleepHoldMode.entries.firstOrNull { it.name == stored }?.let { return it } }
        return if (prefs.getBoolean(KEY_SYNCED_SLEEP_HOLD, false)) SleepHoldMode.SYNCED else SleepHoldMode.LOCK
    }

    fun setSleepHold(context: Context, mode: SleepHoldMode) {
        prefs(context).edit().putString(KEY_SLEEP_HOLD, mode.name).remove(KEY_SYNCED_SLEEP_HOLD).apply()
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
        sleepHold = sleepHold(context),
        skinTemperature = skinTemperature(context),
        spo2AtNight = spo2AtNight(context),
    )

    /** Stores every setting of [preferences], for the phone's `SET_PREF`. */
    fun write(context: Context, preferences: WatchPreferences) {
        setHeartRateDay(context, preferences.heartRateDay)
        setHeartRateSource(context, preferences.heartRateSource)
        setHrvSource(context, preferences.hrvSource)
        setSleepHold(context, preferences.sleepHold)
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
