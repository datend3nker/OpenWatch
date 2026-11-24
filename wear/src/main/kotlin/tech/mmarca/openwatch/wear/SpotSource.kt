package tech.mmarca.openwatch.wear

import android.os.Handler

/**
 * A measurement the watch takes at night on request, or streams while asked:
 * SpO2's spot readings ([Spo2Reading]), the skin temperature's points
 * ([SkinReading]). The recorder ([Spo2Recorder], [SkinTemperatureRecorder])
 * owns the schedule, the wake lock and the store; a source delivers between
 * [start] and [stop], on the handler it was started with.
 *
 * The Samsung Health Platform is the one implementation
 * (`samsung/SamsungSpotSource`), chosen in [WearGraph]: the red and infrared
 * channels and the skin sensor are closed to ordinary apps, so no raw sensor
 * stands behind this interface yet.
 */
internal interface SpotSource<T> {

    /** Whether this watch can serve the source at all. */
    val isAvailable: Boolean

    /** Starts delivering to [sink]. False when the source refused. */
    fun start(handler: Handler, sink: Sink<T>): Boolean

    /** Stops delivering; safe to call when not started. */
    fun stop()

    interface Sink<T> {
        fun onReadings(readings: List<T>)

        /** The source failed (a missing permission, a platform error). */
        fun onError()
    }
}

/** One SpO2 reading: [percent] once the measurement completed with a plausible value, [moving] when the arm spoiled it. */
internal class Spo2Reading(val epochMillis: Long, val percent: Int?, val bpm: Int, val moving: Boolean)

/** One ECG sample: its voltage (NaN for none) and, on the points that say so, whether both electrodes are touched. */
internal class EcgSample(val millivolts: Float, val touching: Boolean?)

/** One body composition point as [BodyCompositionRun] reads it, and its [result] on the point that carries one. */
internal class BodyCompositionReading(val point: BodyCompositionRun.Point, val result: BodyComposition?)

/** One skin temperature point, skin and ambient; [measured] is false for a point the sensor flagged. */
internal class SkinReading(val epochMillis: Long, val skinCelsius: Float, val ambientCelsius: Float, val measured: Boolean)
