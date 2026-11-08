package tech.mmarca.openwatch.wear

import android.content.Context
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock

/**
 * Lets every sensor ride on the one wake-up the heart rate sensor causes.
 *
 * Only wake-up sensors wake the processor; on the Galaxy Watch7 that is the
 * batched heart rate sensor, every [HeartRateRecorder] latency. The
 * accelerometer, the barometer and the step counter do not wake it: each
 * holds its readings in the hub's buffer and delivers on its own timer, but
 * only while the processor is awake. Their timers drift against the heart
 * rate's, so a buffer may wait for the next wake-up after that, and the
 * accelerometer's holds only 48 seconds: readings were lost even with the
 * heart rate waking the watch every 40 seconds.
 *
 * At each beat [WakeClock] calls [onWake]. This class
 * then flushes every member's buffer at once, holds a short wake lock until
 * each member reports its flush done (or [FLUSH_TIMEOUT_MILLIS] passes), and
 * lets the processor sleep again. One wake-up, every buffer emptied, so the
 * pause between two wake-ups is real sleep.
 *
 * Members join with the listener they registered their sensors with and
 * forward `SensorEventListener2.onFlushCompleted` to [flushed]. All calls may
 * come from any thread.
 */
class SensorWakeSync(context: Context) {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val wakeLock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
        .apply { setReferenceCounted(false) }
    private val thread = HandlerThread("SensorWakeSync").apply { start() }
    private val handler = Handler(thread.looper)
    private val timeout = Runnable { finish(timedOut = true) }

    private val lock = Any()
    private val members = LinkedHashSet<SensorEventListener>()
    private val pending = HashSet<SensorEventListener>()
    private var cycleStartedAt = 0L

    fun join(member: SensorEventListener) = synchronized(lock) { members += member }

    fun leave(member: SensorEventListener) = synchronized(lock) {
        members -= member
        if (pending.remove(member) && pending.isEmpty()) handler.post { finish(timedOut = false) }
    }

    /** The processor is awake for a beat: empty every member's buffer now. */
    fun onWake() {
        val asked = synchronized(lock) {
            if (pending.isNotEmpty() || members.isEmpty()) return
            cycleStartedAt = SystemClock.elapsedRealtime()
            wakeLock.acquire(FLUSH_TIMEOUT_MILLIS + WAKE_LOCK_SLACK_MILLIS)
            // flush() is false for a listener with no sensor registered; that one is not waited for.
            members.filter { sensorManager.flush(it) }.also { pending += it }
        }
        DebugProtocol.count("wake.cycles")
        if (asked.isEmpty()) finish(timedOut = false) else handler.postDelayed(timeout, FLUSH_TIMEOUT_MILLIS)
    }

    /** A member's flush completed: its buffered readings were delivered. */
    fun flushed(member: SensorEventListener) {
        val done = synchronized(lock) { pending.remove(member) && pending.isEmpty() }
        if (done) handler.post { finish(timedOut = false) }
    }

    private fun finish(timedOut: Boolean) {
        handler.removeCallbacks(timeout)
        val started = synchronized(lock) {
            if (!timedOut && pending.isNotEmpty()) return
            pending.clear()
            cycleStartedAt
        }
        if (wakeLock.isHeld) wakeLock.release()
        if (started != 0L) DebugProtocol.count("wake.flushMs", SystemClock.elapsedRealtime() - started)
        if (timedOut) DebugProtocol.count("wake.timeouts")
    }

    fun shutdown() {
        synchronized(lock) {
            members.clear()
            pending.clear()
        }
        handler.removeCallbacksAndMessages(null)
        if (wakeLock.isHeld) wakeLock.release()
        thread.quitSafely()
    }

    private companion object {
        const val WAKE_LOCK_TAG = "OpenWatch:sensorWakeSync"

        /** A flush answers within a few hundred milliseconds; past this the cycle ends anyway. */
        const val FLUSH_TIMEOUT_MILLIS = 3_000L
        const val WAKE_LOCK_SLACK_MILLIS = 1_000L
    }
}
