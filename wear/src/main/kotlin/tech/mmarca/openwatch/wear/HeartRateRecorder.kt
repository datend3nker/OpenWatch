package tech.mmarca.openwatch.wear

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import tech.mmarca.openwatch.wear.samsung.IbiPoint
import tech.mmarca.openwatch.wear.samsung.SamsungHeartRateFeed
import tech.mmarca.openwatch.wearlink.HeartRateInterval
import tech.mmarca.openwatch.wearlink.WearLinkProtocol

/**
 * Keeps the heart rate measured at the interval in force and writes what it
 * reports to the [MetricStore]. Runs inside `WearAppService`, so it outlives
 * the activity. The power-save interval and its alarm come from OpenVitals.
 *
 * From the Android heart rate sensor, one way of running per interval:
 * - [HeartRateInterval.EVERY_10_SECONDS]: the sensor stays registered, batched:
 *   the sensor hub buffers readings for up to [MAX_REPORT_LATENCY_MICROS] and
 *   wakes the CPU once per batch. One stored sample every ten seconds.
 * - [HeartRateInterval.POWER_SAVE]: the sensor is registered for a window of
 *   at most [WINDOW_CAP_MILLIS], unbatched, and is unregistered as soon as its
 *   first usable reading is stored. Between the windows the sensor, and
 *   usually the CPU, are off. The next window opens on a [WakeClock] beat
 *   ([onBeat]) once the interval has passed, so it shares the wake-up that
 *   empties every other sensor's buffer; the recorder keeps no alarm of its own.
 *
 * From the Samsung Health Platform ([WearSettings.Source.SAMSUNG_PLATFORM],
 * Samsung watches only): the recorder subscribes to the [SamsungHeartRateFeed]
 * for as long as the interval is not off, and the platform sets its own pace
 * and batching; the interval only switches it on and off. One stored sample
 * every ten seconds, as from the sensor.
 *
 * [setPlan] is the only entry point; the service calls it with the plan in
 * force, and an unchanged one does nothing. The listener and the timers run
 * on the recorder's own thread, where the SQLite write is allowed.
 * [onBeat] is the exception: the clock calls it on its own thread, and it
 * only posts to the recorder's thread.
 */
internal class HeartRateRecorder(
    private val context: Context,
    private val store: MetricStore,
    private val samsungFeed: SamsungHeartRateFeed,
    /** Told at each dense batch: the batch is the [WakeClock]'s beat. Called on the recorder's thread. */
    private val onBatch: () -> Unit = {},
    /** The source in use changed by itself (a fall-back to the sensor). Called on the recorder's thread. */
    private val onSourceChanged: () -> Unit = {},
    /** Every stored sample, for the sleep minute recorder. Called on this recorder's thread. */
    private val onSample: (epochMillis: Long, bpm: Int) -> Unit = { _, _ -> },
    /** False when the sensor reported no contact, an unreliable reading or 0 bpm. */
    private val onContact: (epochMillis: Long, contact: Boolean) -> Unit = { _, _ -> },
) : SensorEventListener, SamsungHeartRateFeed.Subscriber {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val wakeLock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
        .apply { setReferenceCounted(false) }
    private var thread: HandlerThread? = null

    /** Set with [thread]; the timers and the window run on it. */
    @Volatile
    private var handler: Handler? = null

    /** The interval this recorder runs at; [HeartRateInterval.OFF] while stopped. Read on any thread. */
    @Volatile
    var interval: HeartRateInterval = HeartRateInterval.OFF
        private set

    /** The source in use while running. Read on any thread. */
    @Volatile
    internal var source: WearSettings.Source = WearSettings.Source.RAW_SENSOR
        private set

    /** The source the plan asked for; [source] differs from it after a fall-back. */
    @Volatile
    private var requestedSource: WearSettings.Source = WearSettings.Source.RAW_SENSOR

    /** Set when the Samsung platform failed; the sensor stands in until [resetFallBack]. */
    @Volatile
    private var samsungFailed = false
    private var samsungDelivered = false
    private val noDataTask = Runnable { fallBack("no_data") }

    private var sensor: Sensor? = null

    // Samples kept since the last write: one transaction per batch, not one per sample.
    private val pendingSamples = ArrayList<WearLinkProtocol.HeartRateSample>()
    private val commitTask = Runnable { commit() }
    private var lastStoredAt = 0L
    private var lastPrunedAt = 0L
    private var subscribedAt = 0L
    private var lastArrivalAt = 0L

    // Read and written on the recorder's thread only. The tasks are fields:
    // removing a timer needs the same Runnable that posted it.
    private var windowOpen = false
    private var windowOpenedAt = 0L
    private var windowValues = 0
    private val openWindowTask = Runnable { openWindow() }
    private val closeWindowTask = Runnable { closeWindow() }

    val isRunning: Boolean
        get() = thread != null

    /**
     * Brings the recorder to [wanted] from [wantedSource]: starts it, changes
     * its interval or source, or stops it. False when [wanted] is not off and
     * the source could not start (no sensor, a refused registration or bind).
     */
    internal fun setPlan(wanted: HeartRateInterval, wantedSource: WearSettings.Source): Boolean {
        val running = wanted != HeartRateInterval.OFF && thread != null
        if (wanted == interval && (wanted == HeartRateInterval.OFF || (running && wantedSource == requestedSource))) return true
        stop()
        requestedSource = wantedSource
        if (wanted == HeartRateInterval.OFF) return true
        val started = if (wantedSource == WearSettings.Source.SAMSUNG_PLATFORM && samsungFeed.isAvailable && !samsungFailed) {
            startSamsung(wanted)
        } else {
            start(wanted)
        }
        DebugProtocol.event("HeartRate", "plan", "intervalSec" to wanted.seconds, "source" to source.name, "started" to started)
        return started
    }

    private fun start(wanted: HeartRateInterval): Boolean {
        // The wake-up variant empties its batch even while the watch sleeps; the
        // other one hands over only what survived until the next wake.
        val found = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE, true)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)
        if (found == null) {
            Log.w(TAG, "No heart rate sensor")
            return false
        }
        val workerHandler = startThread()
        sensor = found
        interval = wanted
        source = WearSettings.Source.RAW_SENSOR
        if (wanted == HeartRateInterval.EVERY_10_SECONDS) {
            if (!registerBatched(found, workerHandler)) {
                Log.w(TAG, "Heart rate listener not registered")
                stop()
                return false
            }
        } else {
            workerHandler.post(openWindowTask)
        }
        Log.i(TAG, "Recording heart rate from ${found.name} every ${wanted.seconds} s")
        return true
    }

    private fun startSamsung(wanted: HeartRateInterval): Boolean {
        val workerHandler = startThread()
        interval = wanted
        source = WearSettings.Source.SAMSUNG_PLATFORM
        subscribedAt = System.currentTimeMillis()
        samsungDelivered = false
        if (!samsungFeed.subscribe(this, workerHandler)) {
            Log.w(TAG, "Samsung platform refused")
            stop()
            samsungFailed = true
            DebugProtocol.event("HeartRate", "fallback", "why" to "bind_refused")
            return start(wanted)
        }
        workerHandler.postDelayed(noDataTask, FIRST_POINT_TIMEOUT_MILLIS)
        Log.i(TAG, "Recording heart rate from the Samsung platform")
        return true
    }

    private fun startThread(): Handler {
        val worker = HandlerThread("HeartRateRecorder").apply { start() }
        val workerHandler = Handler(worker.looper)
        thread = worker
        handler = workerHandler
        return workerHandler
    }

    /** Registration for the continuous interval: one reading a second, batched. */
    private fun registerBatched(found: Sensor, workerHandler: Handler): Boolean =
        try {
            sensorManager.registerListener(this, found, SAMPLING_PERIOD_MICROS, MAX_REPORT_LATENCY_MICROS, workerHandler)
        } catch (e: SecurityException) {
            // The grant was revoked between the check and the registration.
            Log.e(TAG, "Heart rate sensor refused", e)
            false
        }

    fun stop() {
        val worker = thread ?: return
        val workerHandler = handler
        // Pending timers would reopen the sensor after this returns; the kept samples are still written.
        workerHandler?.removeCallbacksAndMessages(null)
        workerHandler?.post(commitTask)
        sensorManager.unregisterListener(this)
        samsungFeed.unsubscribe(this)
        if (wakeLock.isHeld) wakeLock.release()
        windowOpen = false
        worker.quitSafely()
        thread = null
        handler = null
        sensor = null
        interval = HeartRateInterval.OFF
        Log.i(TAG, "Stopped recording heart rate")
    }

    /**
     * A [WakeClock] beat: opens the next power-save window once the interval
     * has passed since the last one opened. Called on the clock's thread;
     * only posts to the recorder's thread. A no-op at any other interval or
     * source.
     */
    fun onBeat() {
        val workerHandler = handler ?: return
        if (interval != HeartRateInterval.POWER_SAVE || source != WearSettings.Source.RAW_SENSOR) return
        workerHandler.post {
            if (windowOpen || interval != HeartRateInterval.POWER_SAVE) return@post
            val elapsed = SystemClock.elapsedRealtime() - windowOpenedAt
            if (nextWindowDelayMillis(elapsed, interval.seconds) <= BEAT_TOLERANCE_MILLIS) openWindow()
        }
    }

    /** Starts a window: the sensor on, until its first usable reading or [WINDOW_CAP_MILLIS]. Recorder thread. */
    private fun openWindow() {
        val workerHandler = handler ?: return
        val found = sensor ?: return
        windowOpenedAt = SystemClock.elapsedRealtime()
        windowValues = 0
        wakeLock.acquire(WINDOW_CAP_MILLIS + WAKE_LOCK_SLACK_MILLIS)
        val registered = try {
            sensorManager.registerListener(this, found, SAMPLING_PERIOD_MICROS, 0, workerHandler)
        } catch (e: SecurityException) {
            Log.e(TAG, "Heart rate sensor refused", e)
            false
        }
        if (!registered) {
            Log.w(TAG, "Heart rate window not registered")
            DebugProtocol.event("HeartRate", "window_failed")
            if (wakeLock.isHeld) wakeLock.release()
            return
        }
        windowOpen = true
        workerHandler.postDelayed(closeWindowTask, WINDOW_CAP_MILLIS)
    }

    /**
     * Ends the open window: the sensor off, and the next window scheduled.
     * Does nothing when no window is open, so the reading path and the
     * timeout cannot both schedule the next one. Recorder thread.
     */
    private fun closeWindow() {
        if (!windowOpen) return
        handler?.removeCallbacks(closeWindowTask)
        windowOpen = false
        sensorManager.unregisterListener(this)
        if (wakeLock.isHeld) wakeLock.release()
        val duration = SystemClock.elapsedRealtime() - windowOpenedAt
        DebugProtocol.count("hr.windows")
        DebugProtocol.count("hr.windowMs", duration)
        if (windowValues == 0) DebugProtocol.count("hr.windowsEmpty")
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_HEART_RATE) return
        DebugProtocol.arrival("hr")
        val arrived = SystemClock.elapsedRealtime()
        if (arrived - lastArrivalAt > BATCH_GAP_MILLIS && interval == HeartRateInterval.EVERY_10_SECONDS) onBatch()
        lastArrivalAt = arrived
        // No contact and unreliable readings carry a rate of zero or a flag; both are skipped.
        val bpm = event.values.firstOrNull()?.toInt() ?: return
        val at = SensorTime.epochMillisOf(event.timestamp)
        // The cached reading handed to a new listener carries the time the sensor last ran.
        if (SensorTime.isStale(at)) return
        if (!isUsableHeartRate(event.accuracy, bpm)) {
            noteSkipped(event.accuracy, bpm)
            onContact(at, false)
            return
        }
        keep(at, bpm)
        // One reading per window is all a power-save window needs: the sensor goes off now.
        if (interval == HeartRateInterval.POWER_SAVE && windowOpen) closeWindow()
    }

    override fun onError() {
        handler?.post { fallBack("error") }
    }

    /** The Samsung platform failed or stayed silent: the sensor takes over at the same interval. Recorder thread. */
    private fun fallBack(why: String) {
        if (source != WearSettings.Source.SAMSUNG_PLATFORM || thread == null) return
        val keep = interval
        Log.w(TAG, "Samsung platform failed, falling back to the sensor")
        DebugProtocol.event("HeartRate", "fallback", "why" to why)
        samsungFailed = true
        stop()
        start(keep)
        onSourceChanged()
    }

    /** The wearer changed a setting: the Samsung platform may be tried again. */
    fun resetFallBack() {
        samsungFailed = false
    }

    override fun onPoints(points: List<IbiPoint>) {
        if (source != WearSettings.Source.SAMSUNG_PLATFORM || thread == null) return
        if (!samsungDelivered && points.any { it.hasHeartRate }) {
            samsungDelivered = true
            handler?.removeCallbacks(noDataTask)
        }
        for (point in points) {
            // The first delivery can reach back before the subscription; that part is not this recording.
            if (point.epochMillis < subscribedAt - SensorTime.MAX_EVENT_AGE_MILLIS) continue
            when {
                point.hasHeartRate && point.bpm in WearLinkProtocol.MIN_BPM..WearLinkProtocol.MAX_BPM -> keep(point.epochMillis, point.bpm)
                point.heartRateStatus < 0 -> onContact(point.epochMillis, false)
            }
        }
    }

    private fun keep(at: Long, bpm: Int) {
        if (at - lastStoredAt < MIN_SAMPLE_GAP_MILLIS) return
        lastStoredAt = at
        pendingSamples += WearLinkProtocol.HeartRateSample(at, bpm)
        // Posted behind the rest of the batch, so the whole batch is one write.
        if (pendingSamples.size == 1) handler?.post(commitTask)
        onSample(at, bpm)
        windowValues++
        DebugProtocol.count("hr.stored")
        DebugProtocol.max("hr.lagMs.max", System.currentTimeMillis() - at)
    }

    /** Writes the kept samples in one transaction. Recorder thread. */
    private fun commit() {
        if (pendingSamples.isEmpty()) return
        val batch = pendingSamples.toList()
        pendingSamples.clear()
        store.putAll(WearMetrics.HEART_RATE, batch)
        val at = batch.last().epochMillis
        if (at - lastPrunedAt > PRUNE_EVERY_MILLIS) {
            lastPrunedAt = at
            store.prune(WearMetrics.HEART_RATE, at)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private var lastSkipLogAt = 0L

    /** Off the wrist the sensor keeps reporting; one line a minute says so without flooding. */
    private fun noteSkipped(accuracy: Int, bpm: Int) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastSkipLogAt < SKIP_LOG_EVERY_MILLIS) return
        lastSkipLogAt = now
        Log.d(TAG, "Skipping readings: accuracy=$accuracy bpm=$bpm")
    }

    private companion object {
        const val TAG = "HeartRateRecorder"
        const val WAKE_LOCK_TAG = "OpenWatch:heartRatePowerSave"

        /** One reading a second from the sensor; the store keeps one in ten. */
        const val SAMPLING_PERIOD_MICROS = 1_000_000

        /**
         * How long the sensor hub may hold readings before waking the CPU.
         * This is the wake-up sensor, so it sets how often the watch wakes at
         * night, and that must fit inside the accelerometer's buffer: that
         * sensor cannot wake the watch, its buffer holds 300 readings, 48
         * seconds at the 6.25 Hz the hub delivers, and everything older is
         * lost. At a minute the first night kept 30 readings a minute.
         */
        const val MAX_REPORT_LATENCY_MICROS = 40 * 1_000_000

        /**
         * Stored sample spacing. Enough for resting and daily curves; a tenth of the bytes.
         * [TileDay.MAX_HEART_RATE_SAMPLES_PER_DAY] counts on it: a closer spacing needs a higher limit there.
         */
        const val MIN_SAMPLE_GAP_MILLIS = 10_000L

        /**
         * The safety ceiling on a power-save window: the most time the sensor
         * stays on if no usable reading ever arrives. On-device, a reading
         * usually closes the window within a few seconds.
         */
        const val WINDOW_CAP_MILLIS = 20_000L

        /** Held past [WINDOW_CAP_MILLIS] so the lock cannot expire mid-window on a slow start. */
        const val WAKE_LOCK_SLACK_MILLIS = 10_000L

        /** A beat may come a little before the interval is up; it still opens the window. */
        const val BEAT_TOLERANCE_MILLIS = 60_000L

        const val PRUNE_EVERY_MILLIS = 6L * 60 * 60 * 1000
        const val SKIP_LOG_EVERY_MILLIS = 60_000L

        /** The platform's first point came after 125 s on a fresh boot; past this it is taken as silent. */
        const val FIRST_POINT_TIMEOUT_MILLIS = 5L * 60 * 1000

        /** Readings closer than this arrived in one batch, one wake-up. */
        const val BATCH_GAP_MILLIS = 2_000L
    }
}

/** False for no contact, an unreliable reading, or a rate outside what a heart does. */
internal fun isUsableHeartRate(accuracy: Int, bpm: Int): Boolean =
    accuracy != SensorManager.SENSOR_STATUS_NO_CONTACT &&
        accuracy != SensorManager.SENSOR_STATUS_UNRELIABLE &&
        bpm in WearLinkProtocol.MIN_BPM..WearLinkProtocol.MAX_BPM

/**
 * How long to wait, from now, before a power-save window should next open:
 * the interval minus the time the window just used, never negative. Pure, so
 * it is tested without the real clock.
 */
internal fun nextWindowDelayMillis(elapsedSinceOpenMillis: Long, intervalSeconds: Int): Long =
    (intervalSeconds * 1000L - elapsedSinceOpenMillis).coerceAtLeast(0L)
