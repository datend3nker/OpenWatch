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
 * Two modes:
 * - [Mode.DENSE], a beat every [DENSE_PERIOD_MILLIS]: inside the
 *   accelerometer's 48-second buffer, so every minute is recorded whole.
 *   Only a wake-up sensor can beat this often in Doze (an alarm may fire at
 *   most every nine minutes or so); on a Galaxy Watch7 that is the batched
 *   heart rate sensor. While the heart rate recorder already runs it dense
 *   from the sensor, its batches are the beats ([onExternalBatch]);
 *   otherwise the clock registers the sensor itself, slowly, for the beat
 *   alone and ignores the readings.
 * - [Mode.SPARSE], a beat every [SPARSE_PERIOD_MILLIS] from an alarm Doze
 *   allows. The accelerometer then loses what its buffer cannot hold.
 *
 * A sleep hold rests on a dense beat instead of a lasting wake lock
 * ([isDense]); without the heart rate grant there is no dense beat and the
 * hold keeps its lock. Everything here runs on the clock's own thread
 * except [setMode], which the service calls on the main thread.
 */
class WakeClock(private val context: Context, private val wakeSync: SensorWakeSync) : SensorEventListener {

    enum class Mode { DENSE, SPARSE }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
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
    private var lastBeatAt = 0L
    private var lastArrivalAt = 0L

    /** Whether beats come at least every [DENSE_PERIOD_MILLIS]: every minute is recorded whole without a lasting lock. */
    val isDense: Boolean
        get() = mode == Mode.DENSE && (external || ownSource)

    /** Called on the clock's thread at every beat with the beat's wall time. */
    fun addListener(listener: (Long) -> Unit) {
        listeners += listener
    }

    /**
     * Sets the mode; [externalDense] is true while the heart rate recorder
     * delivers dense batches from the sensor, which then are the beats. A
     * dense mode without a source (no heart rate grant) falls back to the
     * alarm, and [isDense] stays false. Main thread.
     */
    fun setMode(next: Mode, externalDense: Boolean) {
        if (configured && next == mode && externalDense == external) return
        configured = true
        mode = next
        external = externalDense
        val wantOwn = next == Mode.DENSE && !externalDense
        if (wantOwn && !ownSource) ownSource = registerOwn()
        if (!wantOwn && ownSource) {
            sensorManager.unregisterListener(this)
            ownSource = false
        }
        alarmWanted = !external && !ownSource
        if (alarmWanted) scheduleAlarm() else alarmManager.cancel(alarmIntent)
        DebugProtocol.event("WakeClock", "mode", "mode" to next.name, "external" to externalDense, "own" to ownSource)
        Log.i(TAG, "Beat: ${next.name.lowercase()}, dense=$isDense")
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

    private fun registerOwn(): Boolean {
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE, true) ?: return false
        if (!WearPermissions.hasHeartRate(context)) return false
        return try {
            sensorManager.registerListener(this, sensor, OWN_SAMPLING_MICROS, (DENSE_PERIOD_MILLIS * 1000).toInt(), handler)
        } catch (_: SecurityException) {
            false
        }
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
        alarmManager.cancel(alarmIntent)
        thread.quitSafely()
    }

    companion object {
        private const val TAG = "WakeClock"
        private const val ALARM_REQUEST_CODE = 2

        /** Inside the accelerometer's 48 seconds, with room for a late batch. */
        const val DENSE_PERIOD_MILLIS = 40_000L

        /** About as often as Doze lets an app's alarm fire. */
        const val SPARSE_PERIOD_MILLIS = 10L * 60 * 1000

        /** The clock's own heart rate registration only beats; a slow rate is enough. */
        private const val OWN_SAMPLING_MICROS = 10_000_000

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
