package tech.mmarca.openwatch.wear

import android.content.Context
import tech.mmarca.healthcalculations.insights.HeartRateZones
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

    /** [SAMSUNG_PPG], the platform's green PPG analysed on the watch, is a choice for the HRV windows only. */
    enum class Source { RAW_SENSOR, SAMSUNG_PLATFORM, SAMSUNG_PPG }

    /**
     * The wearer as the platform's body composition tracker needs them: read
     * from the body profile the phone sends, handed to the platform only for
     * a measurement.
     */
    data class BodyProfile(val heightCm: Int, val weightKg: Int, val ageYears: Int, val male: Boolean)

    private const val PREFS = "wear_settings"
    private const val KEY_HEART_RATE_DAY = "heart_rate_day"
    private const val KEY_HEART_RATE_SOURCE = "heart_rate_source"
    private const val KEY_HRV_SOURCE = "hrv_source"
    private const val KEY_SKIN_TEMPERATURE = "skin_temperature"
    private const val KEY_SPO2 = "spo2_at_night"
    private const val KEY_MAX_HEART_RATE = "max_heart_rate"
    private const val KEY_RESTING_HEART_RATE = "resting_heart_rate"
    private const val KEY_WEIGHT_HG = "body_weight_hg"
    private const val KEY_HEIGHT_CM = "body_height_cm"
    private const val KEY_BIRTH_YEAR = "birth_year"
    private const val KEY_SEX = "sex"
    private const val KEY_AUTO_PAUSE = "auto_pause"
    private const val KEY_AUTO_DETECT = "auto_detect"

    fun heartRateDay(context: Context): HeartRateInterval =
        prefs(context).getString(KEY_HEART_RATE_DAY, null)?.let(HeartRateInterval::fromWire)
            ?.takeIf { it != HeartRateInterval.OFF } ?: HeartRateInterval.EVERY_10_SECONDS

    fun setHeartRateDay(context: Context, interval: HeartRateInterval) {
        prefs(context).edit().putString(KEY_HEART_RATE_DAY, interval.wire).apply()
    }

    /** Whether a running or cycling workout pauses by itself when the wearer stops. On by default. */
    fun autoPause(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO_PAUSE, true)

    fun setAutoPause(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_PAUSE, on).apply()
    }

    /** Whether the watch offers a walk or a run it sees in the step minutes. On by default. */
    fun autoDetect(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO_DETECT, true)

    fun setAutoDetect(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_DETECT, on).apply()
    }

    fun heartRateSource(context: Context): Source =
        source(context, KEY_HEART_RATE_SOURCE).takeIf { it != Source.SAMSUNG_PPG } ?: Source.RAW_SENSOR

    fun setHeartRateSource(context: Context, source: Source) = setSource(context, KEY_HEART_RATE_SOURCE, source)

    fun hrvSource(context: Context): Source = source(context, KEY_HRV_SOURCE)

    fun setHrvSource(context: Context, source: Source) = setSource(context, KEY_HRV_SOURCE, source)

    /**
     * Whether a sleep hold rests on the heart rate's wake-ups ([SensorWakeSync])
     * instead of a lasting wake lock: only in power save. On a Galaxy Watch7 the
     * hub held the batches up to 107 s in Doze, and the night of 2026-11-09/10
     * kept 12 % of the accelerometer's readings (8.9 against 10.6 mAh an hour),
     * so it is a way to save power, not a way to keep every minute whole.
     */
    fun syncedSleepHold(context: Context): Boolean = heartRateDay(context) == HeartRateInterval.POWER_SAVE

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

    /** The maximum heart rate the phone sent for the zones; null for none. */
    fun maxHeartRate(context: Context): Int? = prefs(context).getInt(KEY_MAX_HEART_RATE, 0).takeIf { it > 0 }

    /** The resting heart rate the phone sent for the zones; null for none. */
    fun restingHeartRate(context: Context): Int? = prefs(context).getInt(KEY_RESTING_HEART_RATE, 0).takeIf { it > 0 }

    /**
     * What the heart rate zones rest on: the phone's figures. The phone sends an
     * age-predicted maximum when the body profile has a birth year; with none it
     * sends no maximum, and the watch shows no zones rather than a guessed one.
     */
    fun zoneBasis(context: Context): HeartRateZones.Basis? =
        maxHeartRate(context)?.let { HeartRateZones.Basis(it, restingHeartRate(context)) }

    private fun positive(context: Context, key: String): Int? = prefs(context).getInt(key, 0).takeIf { it > 0 }

    /**
     * The wearer for a body composition measurement: the profile the phone
     * sent, or the one entered on the watch, which is kept in the same place.
     * Null until all four are known.
     */
    fun bodyProfile(context: Context, currentYear: Int): BodyProfile? {
        val sex = prefs(context).getString(KEY_SEX, null)
        return BodyProfile(
            heightCm = positive(context, KEY_HEIGHT_CM) ?: return null,
            weightKg = Math.round((positive(context, KEY_WEIGHT_HG) ?: return null) / 10f),
            ageYears = currentYear - (positive(context, KEY_BIRTH_YEAR) ?: return null),
            male = when (sex) { "m" -> true; "f" -> false; else -> return null },
        )
    }

    fun setBodyProfile(context: Context, profile: BodyProfile, currentYear: Int) {
        prefs(context).edit()
            .putInt(KEY_HEIGHT_CM, profile.heightCm)
            .putInt(KEY_WEIGHT_HG, profile.weightKg * 10)
            .putInt(KEY_BIRTH_YEAR, currentYear - profile.ageYears)
            .putString(KEY_SEX, if (profile.male) "m" else "f")
            .apply()
    }

    /** Every setting in one value, for the phone's `GET_PREF`. */
    fun read(context: Context): WatchPreferences = WatchPreferences(
        heartRateDay = heartRateDay(context),
        heartRateSource = heartRateSource(context),
        hrvSource = hrvSource(context),
        skinTemperature = skinTemperature(context),
        spo2AtNight = spo2AtNight(context),
        maxHeartRate = maxHeartRate(context),
        restingHeartRate = restingHeartRate(context),
        weightHectograms = positive(context, KEY_WEIGHT_HG),
        heightCm = positive(context, KEY_HEIGHT_CM),
        birthYear = positive(context, KEY_BIRTH_YEAR),
        female = when (prefs(context).getString(KEY_SEX, null)) { "f" -> true; "m" -> false; else -> null },
    )

    /** Stores every setting of [preferences], for the phone's `SET_PREF`. */
    fun write(context: Context, preferences: WatchPreferences) {
        setHeartRateDay(context, preferences.heartRateDay)
        setHeartRateSource(context, preferences.heartRateSource)
        setHrvSource(context, preferences.hrvSource)
        setSkinTemperature(context, preferences.skinTemperature)
        setSpo2AtNight(context, preferences.spo2AtNight)
        prefs(context).edit()
            .putInt(KEY_MAX_HEART_RATE, preferences.maxHeartRate ?: 0)
            .putInt(KEY_RESTING_HEART_RATE, preferences.restingHeartRate ?: 0)
            .putInt(KEY_WEIGHT_HG, preferences.weightHectograms ?: 0)
            .putInt(KEY_HEIGHT_CM, preferences.heightCm ?: 0)
            .putInt(KEY_BIRTH_YEAR, preferences.birthYear ?: 0)
            .putString(KEY_SEX, when (preferences.female) { true -> "f"; false -> "m"; null -> null })
            .apply()
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
