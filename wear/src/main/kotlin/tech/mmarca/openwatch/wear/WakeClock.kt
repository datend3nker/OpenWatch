package tech.mmarca.openwatch.wear

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The watch app's one beat: the only thing that wakes the processor on a
 * schedule. Every sensor buffer is emptied on a beat ([SensorWakeSync]) and
 * every periodic job runs on one ([addListener]), so the processor wakes
 * once, does everything that is due, and sleeps until the next beat. The
 * recorders' own handler timers only run while the processor is awake, so
 * they fall onto the beats by themselves.
 *
 * Three modes:
 * - [Mode.DENSE], a beat every [DENSE_PERIOD_MILLIS]: inside the
 *   accelerometer's 48-second buffer, so every minute is recorded whole.
 *   Only a wake-up sensor can beat this often in Doze (an alarm may fire at
 *   most every nine minutes or so); on a Galaxy Watch7 the only batched one
 *   is the heart rate sensor. While the heart rate recorder already runs it
 *   dense from the sensor, its batches are the beats ([onExternalBatch]);
 *   otherwise the clock registers the sensor itself for the beat alone and
 *   ignores the readings.
 * - [Mode.QUIET], for a sleep hold: dense while the wearer moves, and while
 *   still a beat every [QUIET_PERIOD_MILLIS], set as the heart rate's batch
 *   latency ([onBatchLatency]). Minutes between two quiet beats keep only
 *   the accelerometer's last 48 seconds and are flagged sparse. The wake-up
 *   motion sensors the watch has without a permission (device orientation,
 *   wrist tilt) bring the dense beat back at once, for [ACTIVE_HOLD_MILLIS]
 *   after the last motion.
 * - [Mode.SPARSE], a beat every [SPARSE_PERIOD_MILLIS] from an alarm Doze
 *   allows. The accelerometer then loses what its buffer cannot hold.
 *
 * A sleep hold rests on the beat instead of a lasting wake lock
 * ([restsOnBeat]); without the heart rate grant there is no beat and the
 * hold keeps its lock. Everything here runs on the clock's own thread
 * except [setMode], which the service calls on the main thread.
 */
class WakeClock(
    private val context: Context,
    private val wakeSync: SensorWakeSync,
    /** The batch latency the heart rate should run at now; called on the clock's thread or in [setMode]. */
    private val onBatchLatency: (millis: Long) -> Unit = {},
) : SensorEventListener {

    enum class Mode { DENSE, QUIET, SPARSE }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val wakeLock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
        .apply { setReferenceCounted(false) }
    private val thread = HandlerThread("WakeClock").apply { start() }
    private val handler = Handler(thread.looper)
    private val listeners = CopyOnWriteArrayList<(Long) -> Unit>()
    private val alarmIntent: PendingIntent by lazy {
        PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE,
            Intent(context, WakeClockAlarmReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
    private val motionListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) = onMotion()
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }
    private val stillCheck = Runnable { goStill() }

    @Volatile
    var mode: Mode = Mode.SPARSE
        private set

    @Volatile
    private var external = false

    @Volatile
    private var ownSource = false

    @Volatile
    private var alarmWanted = false
    private var configured = false
    private var motionRegistered = false

    /** The heart rate batch latency asked for last; [DENSE_PERIOD_MILLIS] outside a quiet stretch. */
    @Volatile
    var batchLatencyMillis: Long = DENSE_PERIOD_MILLIS
        private set
    private var lastBeatAt = 0L
    private var lastArrivalAt = 0L

    /** Whether a beat comes at least every [DENSE_PERIOD_MILLIS] now. */
    val isDense: Boolean
        get() = mode != Mode.SPARSE && (external || ownSource) && batchLatencyMillis == DENSE_PERIOD_MILLIS

    /** Whether a sleep hold may do without a lasting lock: a dense or a quiet beat runs. */
    val restsOnBeat: Boolean
        get() = mode != Mode.SPARSE && (external || ownSource)

    /** Called on the clock's thread at every beat with the beat's wall time. */
    fun addListener(listener: (Long) -> Unit) {
        listeners += listener
    }

    /**
     * Sets the mode; [externalDense] is true while the heart rate recorder
     * delivers dense batches from the sensor, which then are the beats. A
     * dense or quiet mode without a source (no heart rate grant) falls back
     * to the alarm, and [restsOnBeat] stays false. Main thread.
     */
    fun setMode(next: Mode, externalDense: Boolean) {
        if (configured && next == mode && externalDense == external) return
        configured = true
        mode = next
        external = externalDense
        val wantOwn = next != Mode.SPARSE && !externalDense
        if (wantOwn && !ownSource) ownSource = registerOwn()
        if (!wantOwn && ownSource) {
            sensorManager.unregisterListener(this)
            ownSource = false
        }
        alarmWanted = !external && !ownSource
        if (alarmWanted) scheduleAlarm() else alarmManager.cancel(alarmIntent)
        // Only the heart rate's own batches can be stretched; the clock's own registration stays dense.
        val quiet = next == Mode.QUIET && externalDense
        handler.post { setQuiet(quiet) }
        DebugProtocol.event("WakeClock", "mode", "mode" to next.name, "external" to externalDense, "own" to ownSource)
        Log.i(TAG, "Beat: ${next.name.lowercase()}, beating=$restsOnBeat")
    }

    /** A dense heart rate batch from the recorder arrived: that is a beat. Any thread. */
    fun onExternalBatch() {
        handler.post { beat() }
    }

    /** The sparse alarm fired; the service passes it on. Any thread. */
    fun onAlarm() {
        handler.post {
            beat()
            if (alarmWanted) scheduleAlarm()
        }
    }

    /** Starts or ends a quiet stretch: motion sensors on, the beat stretched while still. Clock thread. */
    private fun setQuiet(quiet: Boolean) {
        handler.removeCallbacks(stillCheck)
        if (quiet && !motionRegistered) motionRegistered = registerMotion()
        if (!quiet && motionRegistered) {
            sensorManager.unregisterListener(motionListener)
            motionRegistered = false
        }
        // Without a motion sensor nothing would bring the dense beat back: stay dense.
        setLatency(if (quiet && motionRegistered) QUIET_PERIOD_MILLIS else DENSE_PERIOD_MILLIS)
    }

    /** The wearer moved: dense beats until [ACTIVE_HOLD_MILLIS] pass without motion. Clock thread. */
    private fun onMotion() {
        if (!motionRegistered) return
        DebugProtocol.count("clock.motions")
        // The sensor service's own lock ends with this callback; the re-registration needs a moment more.
        // Already dense, nothing is registered again: a night gave 134 of these an hour, and each
        // one held the processor two seconds for nothing.
        if (batchLatencyMillis != DENSE_PERIOD_MILLIS) wakeLock.acquire(MOTION_WAKE_LOCK_MILLIS)
        handler.removeCallbacks(stillCheck)
        handler.postDelayed(stillCheck, ACTIVE_HOLD_MILLIS)
        setLatency(DENSE_PERIOD_MILLIS)
    }

    /** [ACTIVE_HOLD_MILLIS] passed without motion; the dense beats ran the timer. Clock thread. */
    private fun goStill() {
        if (motionRegistered) setLatency(QUIET_PERIOD_MILLIS)
    }

    private fun setLatency(millis: Long) {
        if (millis == batchLatencyMillis) return
        batchLatencyMillis = millis
        DebugProtocol.event("WakeClock", if (millis == DENSE_PERIOD_MILLIS) "dense" else "quiet")
        onBatchLatency(millis)
    }

    private fun registerOwn(): Boolean {
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE, true) ?: return false
        if (!WearPermissions.hasHeartRate(context)) return false
        return try {
            sensorManager.registerListener(this, sensor, OWN_SAMPLING_MICROS, (DENSE_PERIOD_MILLIS * 1000).toInt(), handler)
        } catch (_: SecurityException) {
            false
        }
    }

    /** The wake-up device orientation and wrist tilt sensors, by type number: their constants are hidden. */
    private fun registerMotion(): Boolean {
        val sensors = sensorManager.getSensorList(Sensor.TYPE_ALL)
            .filter { it.isWakeUpSensor && it.type in MOTION_SENSOR_TYPES }
            .distinctBy { it.type }
        val registered = sensors.count { sensorManager.registerListener(motionListener, it, SensorManager.SENSOR_DELAY_NORMAL, handler) }
        DebugProtocol.event("WakeClock", "motion_sensors", "count" to registered)
        return registered > 0
    }

    override fun onSensorChanged(event: SensorEvent) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastArrivalAt > BATCH_GAP_MILLIS) beat()
        lastArrivalAt = now
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun beat() {
        val now = SystemClock.elapsedRealtime()
        // Two sources can meet on one wake-up; one beat is enough.
        if (now - lastBeatAt < MIN_BEAT_GAP_MILLIS) return
        lastBeatAt = now
        DebugProtocol.count("clock.beats")
        wakeSync.onWake()
        val wall = System.currentTimeMillis()
        for (listener in listeners) listener(wall)
    }

    private fun scheduleAlarm() {
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + SPARSE_PERIOD_MILLIS,
            alarmIntent,
        )
    }

    fun shutdown() {
        sensorManager.unregisterListener(this)
        sensorManager.unregisterListener(motionListener)
        alarmManager.cancel(alarmIntent)
        handler.removeCallbacksAndMessages(null)
        if (wakeLock.isHeld) wakeLock.release()
        thread.quitSafely()
    }

    companion object {
        private const val TAG = "WakeClock"
        private const val WAKE_LOCK_TAG = "OpenWatch:wakeClockMotion"
        private const val ALARM_REQUEST_CODE = 2

        /** Inside the accelerometer's 48 seconds, with room for a late batch. */
        const val DENSE_PERIOD_MILLIS = 40_000L

        /**
         * A still sleeper's beat. The heart rate's buffer holds 600 readings,
         * ten minutes at one a second; four minutes leaves room, and the
         * minutes wait that long before they close ([SleepMinuteRecorder]).
         */
        const val QUIET_PERIOD_MILLIS = 4L * 60 * 1000

        /** About as often as Doze lets an app's alarm fire. */
        const val SPARSE_PERIOD_MILLIS = 10L * 60 * 1000

        /** After the last motion the beat stays dense this long. */
        const val ACTIVE_HOLD_MILLIS = 5L * 60 * 1000

        /** The clock's own heart rate registration only beats; a slow rate is enough. */
        private const val OWN_SAMPLING_MICROS = 10_000_000

        /** `Sensor.TYPE_WRIST_TILT_GESTURE` (26) and `Sensor.TYPE_DEVICE_ORIENTATION` (27), both hidden. */
        private val MOTION_SENSOR_TYPES = setOf(26, 27)
        private const val MOTION_WAKE_LOCK_MILLIS = 2_000L

        private const val BATCH_GAP_MILLIS = 2_000L
        private const val MIN_BEAT_GAP_MILLIS = 5_000L
    }
}

/** Wakes the watch for a sparse beat; reaches the running service through an action, as the other receivers do. */
class WakeClockAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        runCatching {
            context.startForegroundService(Intent(context, WearAppService::class.java).setAction(WearAppService.ACTION_CLOCK_BEAT))
        }.onFailure { Log.e("WakeClockAlarmReceiver", "Cannot reach the service for the beat", it) }
    }
}
