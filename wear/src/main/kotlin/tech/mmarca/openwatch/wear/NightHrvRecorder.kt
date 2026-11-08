package tech.mmarca.openwatch.wear

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.util.Log
import tech.mmarca.openwatch.wearlink.WearLinkProtocol

/**
 * Measures heart rate variability at night: a five-minute window of
 * Samsung's raw PPG while the arm lies still, at most every 30 minutes, one
 * [WearMetrics.HRV] row per good window.
 *
 * The math is `NightHrv` and the rule for when to measure is
 * `NightHrvSchedule`, both pure; this class is the sensor and the clock around them. A
 * window starts only while the sleep recorder holds the processor awake
 * ([BedtimeHold.HOLDING]: bedtime mode, worn, off the charger) and the last
 * ten minute rows show no movement. The result waits until the minute rows
 * that cover the window have closed, and is dropped when the arm moved.
 *
 * The sensor is `com.samsung.sensor.hr_raw`, found by its string type, 25 Hz,
 * under the heart rate permission the app already holds. A watch without it
 * has no HRV. It is registered unbatched with its own partial wake lock for the
 * window; the batched sensor's gaps lose beats. Whether it can run beside the
 * normal heart rate sensor is not verified on a watch.
 *
 * Everything runs on one handler thread, which also does the SQLite work.
 */
class NightHrvRecorder(
    private val context: Context,
    private val store: MetricStore,
    /** Whether bedtime mode holds the processor awake now; read on the recorder's thread. */
    private val isHolding: () -> Boolean,
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sensorManager.getSensorList(Sensor.TYPE_ALL).firstOrNull {
        it.stringType == PpgRawLogger.SENSOR_TYPE
    }
    private val wakeLock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
        .apply { setReferenceCounted(false) }

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private val tick = Runnable { onTick() }

    // Handler thread only.
    private var nextAllowedAt = 0L
    private var windowStartedAt = 0L
    private var times = DoubleArray(INITIAL_CAPACITY)
    private var values = DoubleArray(INITIAL_CAPACITY)
    private var count = 0
    private var pending: Pending? = null

    private class Pending(val startMillis: Long, val endMillis: Long, val result: HrvWindowResult)

    /** Whether the watch has the raw PPG sensor. Without it there is no HRV. */
    val hasSensor: Boolean
        get() = sensor != null

    val isRunning: Boolean
        get() = thread != null

    /** Starts or stops the recorder. Main thread. Does nothing without the sensor. */
    fun setEnabled(enabled: Boolean) {
        if (enabled && sensor != null) start() else stop()
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
        } else if (isHolding() && NightHrvSchedule.mayStart(recentMinutes(now), now, nextAllowedAt)) {
            beginWindow(now)
        }
        val delay = if (windowStartedAt != 0L) WINDOW_TICK_MILLIS else TICK_MILLIS
        workerHandler.postDelayed(tick, delay)
    }

    private fun recentMinutes(now: Long): List<WearLinkProtocol.SleepMinute> =
        store.since(WearMetrics.SLEEP_MINUTES, now - LOOKBACK_MILLIS, LOOKBACK_ROWS)

    private fun beginWindow(now: Long) {
        val raw = sensor ?: return
        val registered = try {
            sensorManager.registerListener(this, raw, raw.minDelay.coerceAtLeast(1), 0, handler)
        } catch (_: SecurityException) {
            Log.w(TAG, "Raw PPG refused")
            false
        }
        nextAllowedAt = NightHrvSchedule.nextStartAfter(now, spoiled = !registered)
        if (!registered) return
        count = 0
        windowStartedAt = now
        wakeLock.acquire(NightHrv.WINDOW_MILLIS + WAKE_LOCK_SLACK_MILLIS)
        Log.i(TAG, "Window started")
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (windowStartedAt == 0L || event.values.size <= PPG_CHANNEL) return
        val at = SensorTime.epochMillisOf(event.timestamp)
        if (at < windowStartedAt - STALE_MILLIS) return
        if (count == times.size) {
            times = times.copyOf(count * 2)
            values = values.copyOf(count * 2)
        }
        times[count] = event.timestamp / 1_000_000.0
        // The channel carries an integer's bits in a float.
        values[count] = java.lang.Float.floatToRawIntBits(event.values[PPG_CHANNEL]).toDouble()
        count++
        if (count >= MAX_SAMPLES) completeWindow(System.currentTimeMillis())
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /** Ends the window and queues its result until the movement rows can judge it. */
    private fun completeWindow(now: Long) {
        val started = windowStartedAt
        val samples = count
        val t = times.copyOf(samples)
        val v = values.copyOf(samples)
        endWindow()
        val result = NightHrv.analyze(t, v)
        if (result == null) {
            Log.i(TAG, "Window gave no value ($samples samples)")
            nextAllowedAt = NightHrvSchedule.nextStartAfter(started, spoiled = true)
            return
        }
        pending = Pending(started, now, result)
        finishPending(now)
    }

    private fun endWindow() {
        if (windowStartedAt == 0L && !wakeLock.isHeld) return
        sensorManager.unregisterListener(this)
        if (wakeLock.isHeld) wakeLock.release()
        windowStartedAt = 0L
        count = 0
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

        /** Raw PPG channel `v5`: the pulsatile one, found by the research spike. */
        const val PPG_CHANNEL = 5

        const val TICK_MILLIS = 30_000L
        const val WINDOW_TICK_MILLIS = 10_000L
        const val WAKE_LOCK_SLACK_MILLIS = 60_000L

        /** A cached reading from before the window started. */
        const val STALE_MILLIS = 1_000L

        /** 25 Hz for five minutes is 7 500; a little more ends the window early. */
        const val MAX_SAMPLES = 25 * 330
        const val INITIAL_CAPACITY = 8_192

        /** Rows read to judge a start or a window: the 10 still minutes plus a window and slack. */
        const val LOOKBACK_MILLIS = 30L * 60 * 1000
        const val LOOKBACK_ROWS = 40

        /** The movement rows close within 150 s; after this the window is given up. */
        const val PENDING_TIMEOUT_MILLIS = 8L * 60 * 1000
    }
}
