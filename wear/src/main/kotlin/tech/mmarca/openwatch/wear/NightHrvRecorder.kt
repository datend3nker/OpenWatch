package tech.mmarca.openwatch.wear

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.util.Log
import tech.mmarca.openwatch.wear.samsung.SamsungHeartRateFeed
import tech.mmarca.openwatch.wear.samsung.SamsungIbiSource
import tech.mmarca.openwatch.wearlink.WearLinkProtocol

/**
 * Measures heart rate variability at night: five-minute windows one after
 * another while the arm lies still, one [WearMetrics.HRV] row per good
 * window.
 *
 * The rule for when to measure is `NightHrvSchedule` (pure); the beats come
 * from an [HrvWindowSource]: Samsung's raw PPG by default ([PpgWindowSource]),
 * or the Samsung Health Platform's own intervals where the watch has the
 * platform and the wearer chose them ([WearSettings.hrvSource], [SamsungIbiSource]).
 * This class is the clock, the wake lock and the store around them. A window
 * starts only while the sleep recorder holds the processor awake
 * ([BedtimeHold.HOLDING]: bedtime mode, worn, off the charger) and the last
 * ten minute rows show no movement. The result waits until the minute rows
 * that cover the window have closed, and is dropped when the arm moved.
 *
 * Everything runs on one handler thread, which also does the SQLite work.
 */
internal class NightHrvRecorder(
    private val context: Context,
    private val store: MetricStore,
    /** Whether bedtime mode holds the processor awake now; read on the recorder's thread. */
    private val isHolding: () -> Boolean,
    samsungFeed: SamsungHeartRateFeed,
) {

    private val ppg = PpgWindowSource(context)
    private val samsung = SamsungIbiSource(samsungFeed)
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

    /** Set when a Samsung window failed; the raw PPG stands in until [resetFallBack]. */
    @Volatile
    private var samsungFailed = false

    private class Pending(val startMillis: Long, val endMillis: Long, val result: NightWindowResult)

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
        WearSettings.hrvSource(context) == WearSettings.Source.SAMSUNG_PLATFORM && samsung.isAvailable && !samsungFailed -> samsung
        ppg.isAvailable -> ppg
        else -> null
    }

    /** The wearer changed a setting: the Samsung platform may be tried again. */
    fun resetFallBack() {
        samsungFailed = false
    }

    private fun start() {
        if (thread != null) return
        val worker = HandlerThread("NightHrvRecorder").apply { start() }
        val workerHandler = Handler(worker.looper)
        thread = worker
        handler = workerHandler
        workerHandler.post {
            // A restart must not start a window inside the last one.
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
                    DebugProtocol.event("NightHrv", "window_dropped", "why" to "hold_ended")
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
        DebugProtocol.event("NightHrv", "window_started", "source" to if (chosen === samsung) "samsung" else "ppg")
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
            if (finished === samsung && samsung.lastWindowFailed && !samsungFailed) {
                samsungFailed = true
                Log.w(TAG, "Samsung platform failed, the next windows use the raw PPG")
                DebugProtocol.event("NightHrv", "fallback", "startedAt" to started)
            }
            if (wakeLock.isHeld) wakeLock.release()
            if (result.isEmpty) {
                Log.i(TAG, "Window gave no value")
                DebugProtocol.event("NightHrv", "window_dropped", "why" to "no_value", "startedAt" to started)
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
                DebugProtocol.event("NightHrv", "window_dropped", "why" to "no_rows", "startedAt" to waiting.startMillis)
            }
            return
        }
        pending = null
        if (!NightHrvSchedule.wasStill(rows, waiting.startMillis, waiting.endMillis)) {
            nextAllowedAt = NightHrvSchedule.nextStartAfter(waiting.startMillis, spoiled = true)
            Log.i(TAG, "Window dropped: the arm moved")
            DebugProtocol.event("NightHrv", "window_dropped", "why" to "movement", "startedAt" to waiting.startMillis)
            return
        }
        val hrv = waiting.result.hrv
        if (hrv != null) {
            val rmssd10 = Math.round(hrv.rmssdMillis * 10).toInt()
            store.put(WearMetrics.HRV, WearLinkProtocol.HrvWindow(waiting.startMillis, rmssd10, hrv.beatCount))
            store.prune(WearMetrics.HRV, now)
        }
        val breaths = waiting.result.breathsPerMinute
        if (breaths != null) {
            val breaths10 = Math.round(breaths * 10).toInt()
                .coerceIn(WearLinkProtocol.MIN_BREATHS10, WearLinkProtocol.MAX_BREATHS10)
            store.put(WearMetrics.RESPIRATORY_RATE, WearLinkProtocol.RespiratoryWindow(waiting.startMillis, breaths10, waiting.result.breathingRoutes))
            store.prune(WearMetrics.RESPIRATORY_RATE, now)
        }
        Log.i(TAG, "Window stored (${hrv?.beatCount ?: 0} beats)")
        DebugProtocol.event(
            "NightHrv", "window_stored",
            "startedAt" to waiting.startMillis, "rmssd" to hrv?.rmssdMillis, "beats" to hrv?.beatCount,
            "rr" to breaths, "rrRoutes" to waiting.result.breathingRoutes,
        )
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
