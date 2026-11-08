package tech.mmarca.openwatch.wearlink

/**
 * How often the watch measures heart rate in one part of the day. [wire] is
 * the value a `SET_PREF` carries and the watch stores; [seconds] is the
 * spacing of the stored samples, 0 for off.
 *
 * Shared by both apps, so the phone's choices and the watch's behaviour are
 * one list.
 */
enum class HeartRateInterval(val wire: String, val seconds: Int) {
    OFF("0", 0),
    EVERY_10_SECONDS("10", 10),

    /**
     * A window roughly every ten minutes, not every ten minutes exactly: the
     * watch wakes on `AlarmManager.setAndAllowWhileIdle`, a best-effort
     * schedule Doze can delay, most on the first firing after the watch
     * goes idle.
     */
    POWER_SAVE("600", 600),
    ;

    companion object {
        /**
         * The interval in force at night for the wearer's [setting]. While sleep is recorded the
         * night is every 10 seconds whatever the setting says, because sleep detection reads every
         * sample. Both apps and the schedule use this one rule, so no screen shows a value that
         * does not apply.
         */
        fun atNight(setting: HeartRateInterval, sleepRecording: Boolean): HeartRateInterval =
            if (sleepRecording) EVERY_10_SECONDS else setting

        /**
         * Null for a value this build does not know. This is the case for
         * the removed `EVERY_MINUTE` ("60"): an old phone talking to a new
         * watch, or a new phone talking to an old watch that still offers
         * it, sends a wire value the other side does not recognise.
         * `WearPreferences.applyPreference` then refuses the change (the
         * phone sees `ERROR bad_request`) and the stored interval stays
         * what it was; nothing crashes.
         */
        fun fromWire(value: String): HeartRateInterval? = entries.firstOrNull { it.wire == value }
    }
}
