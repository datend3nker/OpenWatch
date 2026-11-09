package tech.mmarca.openwatch.wear.features.measure

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import kotlin.math.sqrt
import kotlinx.coroutines.delay
import tech.mmarca.openwatch.wear.HrvWindowResult
import tech.mmarca.openwatch.wear.NightHrv
import tech.mmarca.openwatch.wear.PpgRawLogger
import tech.mmarca.openwatch.wear.SensorTime

/**
 * One spot HRV measurement from the raw pulse sensor of a watch that reports no single beats:
 * [durationMillis] of the 25 Hz signal together with the accelerometer, then the same beat
 * detection and RMSSD as the night windows (`NightHrv`). The wearer must stay still; the
 * accelerometer decides ([Stillness]).
 *
 * The sensor is registered unbatched under a partial wake lock for the window, as the night
 * recorder does, because the batched sensor's gaps lose beats. Whether it can run beside the
 * normal heart rate sensor is not verified on a watch.
 */
internal class RawPpgWindow(private val context: Context) {

    class Outcome(val result: HrvWindowResult?, val moved: Boolean)

    /** Null when the watch has no raw pulse sensor. */
    suspend fun measure(durationMillis: Long): Outcome? {
        val manager = context.getSystemService(SensorManager::class.java) ?: return null
        val ppg = manager.getSensorList(Sensor.TYPE_ALL).firstOrNull { it.stringType == PpgRawLogger.SENSOR_TYPE } ?: return null
        val accelerometer = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val pulse = Series()
        val motion = Series()
        val thread = HandlerThread("RawPpgWindow").apply { start() }
        val handler = Handler(thread.looper)
        val startedAt = System.currentTimeMillis()
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val at = SensorTime.epochMillisOf(event.timestamp)
                // The cached reading handed to a new listener carries the time the sensor last ran.
                if (at < startedAt - STALE_MILLIS) return
                if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                    val (x, y, z) = event.values.map { it.toDouble() }
                    motion.add(at, sqrt(x * x + y * y + z * z))
                } else if (event.values.size > PPG_CHANNEL) {
                    // The channel carries an integer's bits in a float.
                    pulse.add(event.timestamp / NANOS_PER_MILLI, java.lang.Float.floatToRawIntBits(event.values[PPG_CHANNEL]).toDouble())
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        val wakeLock = context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
        try {
            wakeLock.acquire(durationMillis + WAKE_LOCK_SLACK_MILLIS)
            if (!manager.registerListener(listener, ppg, ppg.minDelay.coerceAtLeast(1), 0, handler)) return null
            accelerometer?.let { manager.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL, 0, handler) }
            delay(durationMillis)
        } finally {
            manager.unregisterListener(listener)
            if (wakeLock.isHeld) wakeLock.release()
            thread.quitSafely()
        }
        val (pulseTimes, pulseValues) = pulse.snapshot()
        val (motionTimes, motionMagnitudes) = motion.snapshot()
        val moved = accelerometer != null && Stillness.moved(motionTimes.map { it.toLong() }.toLongArray(), motionMagnitudes)
        return Outcome(NightHrv.analyze(pulseTimes, pulseValues), moved)
    }

    /** Readings in time order, added from the sensor thread and read once after the sensors stop. */
    private class Series {
        private var times = DoubleArray(INITIAL)
        private var values = DoubleArray(INITIAL)
        private var size = 0

        @Synchronized
        fun add(time: Double, value: Double) {
            if (size == times.size) {
                times = times.copyOf(size * 2)
                values = values.copyOf(size * 2)
            }
            times[size] = time
            values[size] = value
            size++
        }

        fun add(time: Long, value: Double) = add(time.toDouble(), value)

        @Synchronized
        fun snapshot(): Pair<DoubleArray, DoubleArray> = times.copyOf(size) to values.copyOf(size)
    }

    private companion object {
        const val WAKE_LOCK_TAG = "OpenVitals:spotHrv"
        const val WAKE_LOCK_SLACK_MILLIS = 60_000L
        const val STALE_MILLIS = 1_000L
        const val NANOS_PER_MILLI = 1_000_000.0

        /** Raw PPG channel `v5`: the pulsatile one, found by the research spike. */
        const val PPG_CHANNEL = 5
        const val INITIAL = 8_192
    }
}
