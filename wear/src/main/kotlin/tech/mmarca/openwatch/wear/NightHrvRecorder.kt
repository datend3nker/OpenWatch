package tech.mmarca.openwatch.wear

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.util.Log
import tech.mmarca.openwatch.wear.samsung.SamsungIbiSource
import tech.mmarca.openwatch.wearlink.WearLinkProtocol

/**
 * Measures heart rate variability at night: a five-minute window while the
 * arm lies still, at most every 30 minutes, one [WearMetrics.HRV] row per
 * good window.
 *
 * The rule for when to measure is `NightHrvSchedule` (pure); the beats come
 * from an [HrvWindowSource]: Samsung's raw PPG by default ([PpgWindowSource]),
 * or the Samsung Health Platform's own intervals where the watch has the
 * platform and the wearer chose them ([HrvSourceSetting], [SamsungIbiSource]).
 * This class is the clock, the wake lock and the store around them. A window
 * starts only while the sleep recorder holds the processor awake
 * ([BedtimeHold.HOLDING]: bedtime mode, worn, off the charger) and the last
 * ten minute rows show no movement. The result waits until the minute rows
 * that cover the window have closed, and is dropped when the arm moved.
 *
 * Everything runs on one handler thread, which also does the SQLite work.
 */
class NightHrvRecorder(
    private val context: Context,
    private val store: MetricStore,
    /** Whether bedtime mode holds the processor awake now; read on the recorder's thread. */
    private val isHolding: () -> Boolean,
) {

    private val ppg = PpgWindowSource(context)
    private val samsung = SamsungIbiSource(context)
    private val wakeLock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
        .apply { setReferenceCounted(false) }

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private val tick = Runnable { onTick() }

    // Handler thread only.
    private var nextAllowedAt = 0L
    private var windowStartedAt = 0L
    private var source: HrvWindowSource? = null
    /** A finished window whose source has not answered yet. */
    private var finishing = false
    private var pending: Pending? = null

    private class Pending(val startMillis: Long, val endMillis: Long, val result: HrvWindowResult)

    /** Whether the Samsung Health Platform can be chosen as the source on this watch. */
    val hasSamsungPlatform: Boolean
        get() = samsung.isAvailable

    /** Whether the watch has any source. Without one there is no HRV. */
    val hasSensor: Boolean
        get() = ppg.isAvailable || samsung.isAvailable

    val isRunning: Boolean
        get() = thread != null

    /** Starts or stops the recorder. Main thread. Does nothing without a source. */
    fun setEnabled(enabled: Boolean) {
        if (enabled && hasSensor) start() else stop()
    }

    /** The source chosen now, or the raw PPG when the choice is not on this watch. */
    private fun chosenSource(): HrvWindowSource? = when {
        HrvSourceSetting.get(context) == HrvSourceSetting.Source.SAMSUNG_PLATFORM && samsung.isAvailable -> samsung
        ppg.isAvailable -> ppg
        else -> null
    }

    private fun start() {
        if (thread != null) return
        val worker = HandlerThread("NightHrvRecorder").apply { start() }
        val workerHandler = Handler(worker.looper)
        thread = worker
        handler = workerHandler
        workerHandler.post {
            // A restart must not break the 30-minute gap.
            store.latest(WearMetrics.HRV)?.let { nextAllowedAt = NightHrvSchedule.nextStartAfter(it.epochMillis, spoiled = false) }
            workerHandler.postDelayed(tick, TICK_MILLIS)
        }
        Log.i(TAG, "Night HRV on")
    }

    fun stop() {
        val worker = thread ?: return
        handler?.removeCallbacks(tick)
        handler?.post {
            endWindow()
            pending = null
        }
        worker.quitSafely()
        thread = null
        handler = null
        Log.i(TAG, "Night HRV off")
    }

    private fun onTick() {
        val workerHandler = handler ?: return
        val now = System.currentTimeMillis()
        finishPending(now)
        if (windowStartedAt != 0L) {
            when {
                !isHolding() -> {
                    Log.i(TAG, "Window dropped: no longer in bedtime hold")
                    endWindow()
                }
                now - windowStartedAt >= NightHrv.WINDOW_MILLIS -> completeWindow(now)
            }
        } else if (!finishing && isHolding() && NightHrvSchedule.mayStart(recentMinutes(now), now, nextAllowedAt)) {
            beginWindow(now)
        }
        val delay = if (windowStartedAt != 0L) WINDOW_TICK_MILLIS else TICK_MILLIS
        workerHandler.postDelayed(tick, delay)
    }

    private fun recentMinutes(now: Long): List<WearLinkProtocol.SleepMinute> =
        store.since(WearMetrics.SLEEP_MINUTES, now - LOOKBACK_MILLIS, LOOKBACK_ROWS)

    private fun beginWindow(now: Long) {
        val chosen = chosenSource() ?: return
        val workerHandler = handler ?: return
        val started = chosen.begin(workerHandler, now)
        nextAllowedAt = NightHrvSchedule.nextStartAfter(now, spoiled = !started)
        if (!started) return
        source = chosen
        windowStartedAt = now
        wakeLock.acquire(NightHrv.WINDOW_MILLIS + WAKE_LOCK_SLACK_MILLIS)
        Log.i(TAG, if (chosen === samsung) "Window started, Samsung platform" else "Window started, raw PPG")
    }

    /** Ends the window and queues its result until the movement rows can judge it. */
    private fun completeWindow(now: Long) {
        val started = windowStartedAt
        val finished = source ?: return
        source = null
        windowStartedAt = 0L
        finishing = true
        finished.finish(now) { result ->
            finishing = false
            if (wakeLock.isHeld) wakeLock.release()
            if (result == null) {
                Log.i(TAG, "Window gave no value")
                nextAllowedAt = NightHrvSchedule.nextStartAfter(started, spoiled = true)
                return@finish
            }
            pending = Pending(started, now, result)
            finishPending(now)
        }
    }

    private fun endWindow() {
        source?.cancel()
        source = null
        if (finishing) {
            ppg.cancel()
            samsung.cancel()
            finishing = false
        }
        if (wakeLock.isHeld) wakeLock.release()
        windowStartedAt = 0L
    }

    /** Stores the waiting result once the rows covering it show a still arm; drops it when they do not. */
    private fun finishPending(now: Long) {
        val waiting = pending ?: return
        val rows = store.since(WearMetrics.SLEEP_MINUTES, waiting.startMillis - LOOKBACK_MILLIS, LOOKBACK_ROWS)
        if (!NightHrvSchedule.covers(rows, waiting.endMillis)) {
            if (now - waiting.endMillis > PENDING_TIMEOUT_MILLIS) {
                pending = null
                Log.i(TAG, "Window dropped: no movement rows for it")
            }
            return
        }
        pending = null
        if (!NightHrvSchedule.wasStill(rows, waiting.startMillis, waiting.endMillis)) {
            nextAllowedAt = NightHrvSchedule.nextStartAfter(waiting.startMillis, spoiled = true)
            Log.i(TAG, "Window dropped: the arm moved")
            return
        }
        val rmssd10 = Math.round(waiting.result.rmssdMillis * 10).toInt()
        store.put(WearMetrics.HRV, WearLinkProtocol.HrvWindow(waiting.startMillis, rmssd10, waiting.result.beatCount))
        store.prune(WearMetrics.HRV, now)
        Log.i(TAG, "Window stored (${waiting.result.beatCount} beats)")
    }

    private companion object {
        const val TAG = "NightHrvRecorder"
        const val WAKE_LOCK_TAG = "OpenWatch:nightHrv"

        const val TICK_MILLIS = 30_000L
        const val WINDOW_TICK_MILLIS = 10_000L

        /** Covers the source's wait for its last points after the window. */
        const val WAKE_LOCK_SLACK_MILLIS = 60_000L

        /** Rows read to judge a start or a window: the 10 still minutes plus a window and slack. */
        const val LOOKBACK_MILLIS = 30L * 60 * 1000
        const val LOOKBACK_ROWS = 40

        /** The movement rows close within 150 s; after this the window is given up. */
        const val PENDING_TIMEOUT_MILLIS = 8L * 60 * 1000
    }
}
