package tech.mmarca.openwatch.wear

import tech.mmarca.openwatch.wearlink.HeartRateInterval

/**
 * Which heart rate interval applies now, and whether the watch offers the
 * heart rate at all. Pure: [WearAppService] feeds it the wearer's settings
 * and bedtime mode, and starts, changes or stops the recorder with the
 * answer.
 *
 * The night is bedtime mode, the same one [BedtimeMonitor] reads for the
 * sleep recorder; there is no second schedule.
 */
object HeartRateSchedule {

    /** The rate sleep detection needs: every stored sample, as today. */
    val FULL_RATE: HeartRateInterval = HeartRateInterval.EVERY_10_SECONDS

    /**
     * The interval in force. [FULL_RATE] during a [workout]. Otherwise off while heart rate tracking is off. In bedtime
     * mode with sleep recording on it is [FULL_RATE]: the sleep pipeline reads
     * every sample, so a thinned night would break it. In bedtime mode with
     * sleep recording off it is the [night] setting; otherwise [day].
     */
    fun interval(
        heartRate: Boolean,
        sleepRecording: Boolean,
        bedtime: Boolean,
        day: HeartRateInterval,
        night: HeartRateInterval,
        workout: Boolean = false,
    ): HeartRateInterval = when {
        // A workout records at the full rate, whatever the settings say.
        workout -> FULL_RATE
        !heartRate -> HeartRateInterval.OFF
        bedtime -> HeartRateInterval.atNight(night, sleepRecording)
        else -> day
    }

    /**
     * Whether the watch offers the `hr` capability. Off with heart rate
     * tracking off; with it on, offered while some part of the day, or the
     * sleep recorder, can use the sensor.
     */
    fun offered(
        heartRate: Boolean,
        sleepRecording: Boolean,
        day: HeartRateInterval,
        night: HeartRateInterval,
    ): Boolean = heartRate && (sleepRecording || day != HeartRateInterval.OFF || night != HeartRateInterval.OFF)
}
