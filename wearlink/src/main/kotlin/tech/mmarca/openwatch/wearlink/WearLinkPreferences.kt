package tech.mmarca.openwatch.wearlink

/**
 * The settings the phone may read with `GET_PREF` and change with
 * `SET_PREF`: the watch's own ([WearSettings] on the watch). Opaque
 * tokens on the wire; the watch refuses an unknown key or value. The
 * read-only keys say what the watch can offer.
 */
object WearLinkPreferences {
    /** The heart rate outside bedtime mode. Values are `HeartRateInterval.wire` (`10`, `600`). */
    const val HEART_RATE_DAY: String = "hr_day"

    /** Where the heart rate comes from. [SENSOR] / [SAMSUNG]. */
    const val HEART_RATE_SOURCE: String = "hr_source"

    /** Where the night HRV windows' beats come from. [SENSOR] / [SAMSUNG]. */
    const val HRV_SOURCE: String = "hrv_source"

    /** How a sleep hold keeps the minutes whole. [LOCK] / [SYNCED]. */
    const val SLEEP_HOLD: String = "sleep_hold"

    /** Skin temperature at night from the Samsung platform. [OFF] / [ON]. */
    const val SKIN_TEMPERATURE: String = "skin"

    /** SpO2 spot measurements at night from the Samsung platform. [OFF] / [ON]. */
    const val SPO2: String = "spo2"

    /** The zones' maximum and resting heart rate from the profile, whole bpm; `0` for none. */
    const val HEART_RATE_MAX: String = "hr_max"
    const val HEART_RATE_REST: String = "hr_rest"

    /** The profile for a workout's energy: weight in 100 g, height in cm, birth year, `f`/`m`; `0` for none. */
    const val BODY_WEIGHT: String = "body_hg"
    const val BODY_HEIGHT: String = "body_cm"
    const val BIRTH_YEAR: String = "birth_year"
    const val SEX: String = "sex"

    /** Read only: whether the watch has the Samsung Health Platform. [OFF] / [ON]. */
    const val SAMSUNG_AVAILABLE: String = "samsung"

    /** Read only: whether the watch holds the permission its skin temperature needs. [OFF] / [ON]. */
    const val SKIN_GRANTED: String = "skin_granted"

    /** Read only: whether the watch holds the permission its SpO2 needs. [OFF] / [ON]. */
    const val SPO2_GRANTED: String = "spo2_granted"

    const val SENSOR: String = "sensor"
    const val SAMSUNG: String = "samsung"
    const val LOCK: String = "lock"
    const val SYNCED: String = "synced"
    const val OFF: String = "0"
    const val ON: String = "1"
}
