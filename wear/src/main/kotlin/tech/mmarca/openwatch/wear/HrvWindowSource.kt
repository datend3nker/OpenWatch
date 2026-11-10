package tech.mmarca.openwatch.wear

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.util.Log

/**
 * What one night window gave: its RMSSD and its breathing rate, either may
 * be missing. [breathingRoutes] counts the routes that agreed on the rate:
 * two for the raw PPG (beat intervals and signal baseline), one for a source
 * that reports only intervals.
 */
internal class NightWindowResult(
    val hrv: HrvWindowResult?,
    val breathsPerMinute: Double? = null,
    val breathingRoutes: Int = 0,
) {
    val isEmpty: Boolean get() = hrv == null && breathsPerMinute == null
}

/**
 * Where the beats of one night-time HRV window come from. [NightHrvRecorder]
 * owns the schedule, the wake lock and the store; a source only listens
 * between [begin] and [finish]. Every call is on the recorder's thread, and
 * so is every [finish] callback.
 */
internal interface HrvWindowSource {
    /** Whether this watch can serve the source at all. */
    val isAvailable: Boolean

    /** Starts listening for the window that opens at [startMillis]. False when the source refused. */
    fun begin(handler: Handler, startMillis: Long): Boolean

    /** Stops listening and hands over the window's result, null for none, possibly after a short wait. */
    fun finish(endMillis: Long, onResult: (NightWindowResult) -> Unit)

    /** Stops listening and drops the window. */
    fun cancel()
}

/**
 * Samsung's raw PPG, `com.samsung.sensor.hr_raw`, found by its string type,
 * 25 Hz, under the heart rate permission the app already holds; the beats
 * are found here ([NightHrv]). It is registered unbatched for the window;
 * the batched sensor's gaps lose beats. Whether it can run beside the normal
 * heart rate sensor is not verified on a watch.
 */
internal class PpgWindowSource(context: Context) : HrvWindowSource, SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sensorManager.getSensorList(Sensor.TYPE_ALL).firstOrNull {
        it.stringType == PpgRawLogger.SENSOR_TYPE
    }

    private var startedAt = 0L
    private var times = DoubleArray(INITIAL_CAPACITY)
    private var values = DoubleArray(INITIAL_CAPACITY)
    private var count = 0

    override val isAvailable: Boolean
        get() = sensor != null

    override fun begin(handler: Handler, startMillis: Long): Boolean {
        val raw = sensor ?: return false
        count = 0
        startedAt = startMillis
        val registered = try {
            sensorManager.registerListener(this, raw, raw.minDelay.coerceAtLeast(1), 0, handler)
        } catch (_: SecurityException) {
            Log.w(TAG, "Raw PPG refused")
            false
        }
        if (!registered) startedAt = 0L
        return registered
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (startedAt == 0L || event.values.size <= PPG_CHANNEL || count >= MAX_SAMPLES) return
        if (SensorTime.epochMillisOf(event.timestamp) < startedAt - STALE_MILLIS) return
        if (count == times.size) {
            times = times.copyOf(count * 2)
            values = values.copyOf(count * 2)
        }
        times[count] = event.timestamp / 1_000_000.0
        // The channel carries an integer's bits in a float.
        values[count] = java.lang.Float.floatToRawIntBits(event.values[PPG_CHANNEL]).toDouble()
        count++
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun finish(endMillis: Long, onResult: (NightWindowResult) -> Unit) {
        val t = times.copyOf(count)
        val v = values.copyOf(count)
        val window = startedAt
        cancel()
        val result = NightHrv.analyze(t, v)
        val breathing = NightHrv.respiration(t, v)
        DebugProtocol.event(
            "NightHrv", "ppg_window",
            "startedAt" to window, "samples" to t.size, "rmssd" to result?.rmssdMillis, "beats" to result?.beatCount,
            "rrIntervals" to breathing.fromIntervals?.breathsPerMinute, "rrIntervalsShare" to breathing.fromIntervals?.peakShare,
            "rrSignal" to breathing.fromSignal?.breathsPerMinute, "rrSignalShare" to breathing.fromSignal?.peakShare,
            "rr" to breathing.value,
        )
        onResult(NightWindowResult(result, breathing.value, breathing.routes))
    }

    override fun cancel() {
        sensorManager.unregisterListener(this)
        startedAt = 0L
        count = 0
    }

    private companion object {
        const val TAG = "PpgWindowSource"

        /** Raw PPG channel `v5`: the pulsatile one, found by the research spike. */
        const val PPG_CHANNEL = 5

        /** A cached reading from before the window started. */
        const val STALE_MILLIS = 1_000L

        /** 25 Hz for five minutes is 7 500; more than this is not kept. */
        const val MAX_SAMPLES = 25 * 330
        const val INITIAL_CAPACITY = 8_192
    }
}
