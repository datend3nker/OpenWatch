package tech.mmarca.openwatch.wear.features.measure

import tech.mmarca.openwatch.wear.samsung.toSpo2Point
import tech.mmarca.openwatch.wear.samsung.Spo2Point
import tech.mmarca.openwatch.wear.samsung.RawPoint
import tech.mmarca.openwatch.wear.samsung.SamsungHealthPlatform
import tech.mmarca.openwatch.wear.samsung.SamsungIbiSource
import tech.mmarca.openwatch.wear.samsung.SamsungHeartRateFeed
import tech.mmarca.openwatch.wear.WearSettings
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.delay
import kotlin.coroutines.resume
import android.os.HandlerThread
import android.os.Handler
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.withTimeoutOrNull
import tech.mmarca.openwatch.wear.SensorTime
import tech.mmarca.openwatch.wear.isUsableHeartRate

/**
 * Runs one [Measurement] against the real sensors. Each flow emits
 * [MeasureEvent]s, ends with a [MeasureEvent.Result] and fails with a
 * [MeasureException] when there is no result to give. Collecting stops the
 * sensor, so leaving the screen cancels the measurement.
 */
class MeasurementRunner(private val context: Context) {

    fun run(measurement: Measurement): Flow<MeasureEvent> = when (measurement) {
        Measurement.HEART_RATE -> heartRate()
        Measurement.HRV -> hrv()
        Measurement.AIR_PRESSURE -> airPressure()
        Measurement.SPO2 -> spo2()
    }

    private fun heartRate(): Flow<MeasureEvent> = flow {
        val readings = mutableListOf<Double>()
        var offBody = false
        var settled: Double? = null
        withTimeoutOrNull(Measurement.HEART_RATE.durationSeconds * 1000L) {
            sensorHeartRate()
                .transformWhile { sample ->
                    when (sample) {
                        is HeartRateSample.Reading -> {
                            offBody = false
                            if (sample.bpm > 0) {
                                readings += sample.bpm
                                emit(MeasureEvent.Live(sample.bpm))
                            }
                            settled = settledHeartRate(readings)
                        }
                        is HeartRateSample.Waiting -> {
                            offBody = sample.offBody
                            emit(MeasureEvent.Waiting(sample.offBody))
                        }
                    }
                    settled == null
                }
                .collect { emit(it) }
        }
        val value = settled
            ?: throw MeasureException(if (offBody) MeasureError.OFF_BODY else MeasureError.NO_SIGNAL)
        emit(MeasureEvent.Result(value))
    }

    /** Every usable heart rate while collected, with no end and no result; the sensor stops with the collector. */
    fun liveHeartRate(): Flow<Double> =
        sensorHeartRate().mapNotNull { (it as? HeartRateSample.Reading)?.bpm?.takeIf { bpm -> bpm > 0 } }

    private fun hrv(): Flow<MeasureEvent> =
        if (WearSettings.hrvSource(context) == WearSettings.Source.SAMSUNG_PLATFORM && SamsungHeartRateFeed.shared(context).isAvailable) {
            samsungHrv()
        } else if (context.getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_HEART_BEAT) != null) {
            heartBeatHrv()
        } else {
            rawPpgHrv()
        }

    /**
     * The HRV source chosen on the watch is the Samsung platform: its own
     * beat-to-beat intervals for the measurement's span, read and cleaned as
     * a night window's ([SamsungIbiSource]). The screen is on, so the platform
     * hands the points over soon after the span ends.
     */
    private fun samsungHrv(): Flow<MeasureEvent> = flow {
        val thread = HandlerThread("SpotHrvSamsung").apply { start() }
        val handler = Handler(thread.looper)
        val source = SamsungIbiSource(SamsungHeartRateFeed.shared(context))
        try {
            val started = suspendCancellableCoroutine { done ->
                handler.post { done.resume(source.begin(handler, System.currentTimeMillis())) }
            }
            if (!started) throw MeasureException(MeasureError.NOT_SUPPORTED)
            delay(Measurement.HRV.durationSeconds * 1000L)
            val end = System.currentTimeMillis()
            val window = suspendCancellableCoroutine { done ->
                handler.post { source.finish(end) { done.resume(it) } }
                done.invokeOnCancellation { handler.post { source.cancel() } }
            }
            val result = window.hrv ?: throw MeasureException(MeasureError.NO_SIGNAL)
            emit(MeasureEvent.Result(result.rmssdMillis))
        } finally {
            handler.post { source.cancel() }
            thread.quitSafely()
        }
    }

    /** A watch with no single beats: the raw pulse sensor and the accelerometer, as the night windows do. */
    private fun rawPpgHrv(): Flow<MeasureEvent> = flow {
        val outcome = RawPpgWindow(context).measure(Measurement.HRV.durationSeconds * 1000L)
            ?: throw MeasureException(MeasureError.NOT_SUPPORTED)
        if (outcome.moved) throw MeasureException(MeasureError.MOVED)
        val result = outcome.result ?: throw MeasureException(MeasureError.NO_SIGNAL)
        emit(MeasureEvent.Result(result.rmssdMillis))
    }

    private fun heartBeatHrv(): Flow<MeasureEvent> = flow {
        val beats = mutableListOf<Long>()
        withTimeoutOrNull(Measurement.HRV.durationSeconds * 1000L) {
            sensorEvents(Sensor.TYPE_HEART_BEAT).collect { event ->
                beats += event.timestamp
                rrIntervalsMillis(beats.takeLast(2)).lastOrNull()?.let { rr ->
                    if (rr in 300.0..2000.0) emit(MeasureEvent.Live(60_000.0 / rr))
                }
            }
        }
        val value = rmssd(beats, HrvBeatsNeededForReading) ?: throw MeasureException(MeasureError.NO_SIGNAL)
        emit(MeasureEvent.Result(value))
    }

    /**
     * One reading of the Samsung platform's SpO2 tracker: the first "completed"
     * point since the start; a moving arm ends it with its own message.
     */
    private fun spo2(): Flow<MeasureEvent> = flow {
        val feed = SamsungHeartRateFeed.shared(context)
        if (!feed.isAvailable) throw MeasureException(MeasureError.NOT_SUPPORTED)
        val thread = HandlerThread("SpotSpo2").apply { start() }
        val handler = Handler(thread.looper)
        val startedAt = System.currentTimeMillis()
        var moved = false
        var subscriber: SamsungHeartRateFeed.RawSubscriber? = null
        try {
            val point = withTimeoutOrNull(Measurement.SPO2.durationSeconds * 1000L) {
                suspendCancellableCoroutine { done ->
                    val reader = object : SamsungHeartRateFeed.RawSubscriber {
                        override fun onRawPoints(points: List<RawPoint>) {
                            val decoded = points.map { it.toSpo2Point() }
                            if (decoded.any { it.status == Spo2Point.STATUS_MOVING }) moved = true
                            decoded.firstOrNull { it.isComplete && it.epochMillis >= startedAt - STALE_SPO2_MILLIS }
                                ?.let { if (done.isActive) done.resume(it) }
                        }

                        override fun onError() {
                            if (done.isActive) done.resume(null)
                        }
                    }
                    subscriber = reader
                    if (!feed.subscribeRaw(SamsungHealthPlatform.TRACKER_SPO2, reader, handler) && done.isActive) done.resume(null)
                }
            }
            when {
                point != null -> emit(MeasureEvent.Result(point.percent.toDouble(), secondary = point.bpm.toDouble()))
                moved -> throw MeasureException(MeasureError.MOVED)
                else -> throw MeasureException(MeasureError.NO_SIGNAL)
            }
        } finally {
            subscriber?.let { feed.unsubscribeRaw(SamsungHealthPlatform.TRACKER_SPO2, it) }
            thread.quitSafely()
        }
    }

    private fun airPressure(): Flow<MeasureEvent> = flow {
        val readings = mutableListOf<Double>()
        withTimeoutOrNull(Measurement.AIR_PRESSURE.durationSeconds * 1000L) {
            sensorEvents(Sensor.TYPE_PRESSURE)
                .transformWhile { event ->
                    val hpa = event.values[0].toDouble()
                    readings += hpa
                    emit(MeasureEvent.Live(hpa))
                    readings.size < PressureReadingsNeeded
                }
                .collect { emit(it) }
        }
        if (readings.size < PressureReadingsNeeded) throw MeasureException(MeasureError.NO_SIGNAL)
        val hpa = readings.average()
        val altitude = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, hpa.toFloat())
        emit(MeasureEvent.Result(value = hpa, secondary = altitude.toDouble()))
    }

    /**
     * The heart rate sensor, unbatched, as the recorder reads it: no contact
     * means off the wrist, an unreliable reading means still settling.
     */
    private fun sensorHeartRate(): Flow<HeartRateSample> =
        sensorEvents(Sensor.TYPE_HEART_RATE).mapNotNull { event ->
            val bpm = event.values.firstOrNull() ?: return@mapNotNull null
            // The cached reading handed to a new listener carries the time the sensor last ran.
            if (SensorTime.isStale(SensorTime.epochMillisOf(event.timestamp))) return@mapNotNull null
            when {
                event.accuracy == SensorManager.SENSOR_STATUS_NO_CONTACT -> HeartRateSample.Waiting(offBody = true)
                isUsableHeartRate(event.accuracy, bpm.toInt()) -> HeartRateSample.Reading(bpm.toDouble())
                else -> HeartRateSample.Waiting(offBody = false)
            }
        }

    private fun sensorEvents(type: Int): Flow<SensorEvent> = callbackFlow {
        val manager = context.getSystemService(SensorManager::class.java)
        val sensor = manager?.getDefaultSensor(type) ?: throw MeasureException(MeasureError.NOT_SUPPORTED)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                trySend(event)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        val registered = try {
            manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_FASTEST)
        } catch (e: SecurityException) {
            throw MeasureException(MeasureError.PERMISSION_DENIED, e)
        }
        if (!registered) throw MeasureException(MeasureError.FAILED)
        awaitClose { manager.unregisterListener(listener) }
    }

    private sealed interface HeartRateSample {
        data class Reading(val bpm: Double) : HeartRateSample
        data class Waiting(val offBody: Boolean) : HeartRateSample
    }

    private companion object {
        /** A completed reading from before the start would be one the platform kept. */
        const val STALE_SPO2_MILLIS = 5_000L

        const val PressureReadingsNeeded = 5
    }
}
