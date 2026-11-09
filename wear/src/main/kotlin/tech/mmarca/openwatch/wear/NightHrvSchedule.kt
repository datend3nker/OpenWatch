package tech.mmarca.openwatch.wear

import tech.mmarca.openwatch.wearlink.WearLinkProtocol.FLAG_CHARGING
import tech.mmarca.openwatch.wearlink.WearLinkProtocol.FLAG_OFF_BODY
import tech.mmarca.openwatch.wearlink.WearLinkProtocol.FLAG_SCREEN_ON
import tech.mmarca.openwatch.wearlink.WearLinkProtocol.MinuteKind
import tech.mmarca.openwatch.wearlink.WearLinkProtocol.SleepMinute

/**
 * When the watch may take a night-time HRV window, and whether the arm
 * stayed still through one, both read from the sleep minute rows the watch
 * already records. A still arm is the one condition under which the PPG
 * beats are clean enough for millisecond intervals.
 *
 * The movement of a minute is `mv10` (see `sleep-minute-features.md`): 0
 * for a still minute. A minute only closes about 90 seconds after it
 * started, so the newest row is never the current minute.
 */
object NightHrvSchedule {

    private const val MINUTE_MILLIS = 60_000L

    /** Still minutes in a row before a window starts: the wearer is lying quietly, most likely asleep. */
    const val STILL_MINUTES_BEFORE = 10

    /** The newest closed row may be this old: a minute closes 90 s late, plus a tick. */
    private const val MAX_ROW_AGE_MILLIS = 4 * MINUTE_MILLIS

    /**
     * The next window may start as soon as the last one is over: every still
     * stretch is measured whole, and the night's value is the mean of its
     * windows (as Oura and Garmin take theirs), which the apps reading
     * Health Connect form from one record per window.
     */
    const val MIN_GAP_MILLIS = NightHrv.WINDOW_MILLIS

    /** After a window the arm spoiled, the next try may come sooner. */
    const val RETRY_GAP_MILLIS = 10 * MINUTE_MILLIS

    /**
     * Movement a minute inside a window may show before the window is
     * dropped: `mv10`, so 1.0 on the scale where a restless minute is about 10.
     */
    const val MAX_WINDOW_MOVEMENT10 = 10

    /** The earliest the next window may start, given when the last one did and whether it was spoiled. */
    fun nextStartAfter(lastStartMillis: Long, spoiled: Boolean): Long =
        lastStartMillis + if (spoiled) RETRY_GAP_MILLIS else MIN_GAP_MILLIS

    /**
     * Whether a window may start at [nowMillis]. [minutes] are the recent
     * rows, ascending. The last [STILL_MINUTES_BEFORE] consecutive minutes
     * must be recorded, still and neither charging, off the wrist nor with the
     * screen on.
     */
    fun mayStart(minutes: List<SleepMinute>, nowMillis: Long, nextAllowedMillis: Long): Boolean {
        if (nowMillis < nextAllowedMillis) return false
        val newest = minutes.lastOrNull() ?: return false
        if (nowMillis - newest.epochMillis > MAX_ROW_AGE_MILLIS) return false
        val byMinute = minutes.associateBy { it.epochMillis }
        return (0 until STILL_MINUTES_BEFORE).all { back ->
            byMinute[newest.epochMillis - back * MINUTE_MILLIS]?.let(::isStill) == true
        }
    }

    /**
     * Whether every minute that touches the window [startMillis, endMillis]
     * is recorded and (near) still. A minute missing from [minutes] is not
     * proof of stillness.
     */
    fun wasStill(minutes: List<SleepMinute>, startMillis: Long, endMillis: Long): Boolean {
        val byMinute = minutes.associateBy { it.epochMillis }
        var minute = startMillis - startMillis % MINUTE_MILLIS
        while (minute <= endMillis) {
            val row = byMinute[minute] ?: return false
            if (!hasNoDisturbance(row) || row.movement10 > MAX_WINDOW_MOVEMENT10) return false
            minute += MINUTE_MILLIS
        }
        return true
    }

    /** Whether the rows reach the minute the window ended in, so [wasStill] can judge it. */
    fun covers(minutes: List<SleepMinute>, endMillis: Long): Boolean =
        minutes.any { it.epochMillis >= endMillis - endMillis % MINUTE_MILLIS }

    private fun isStill(row: SleepMinute): Boolean = hasNoDisturbance(row) && row.movement10 == 0

    private fun hasNoDisturbance(row: SleepMinute): Boolean =
        row.kind == MinuteKind.RAW && row.flags and (FLAG_CHARGING or FLAG_OFF_BODY or FLAG_SCREEN_ON) == 0
}
