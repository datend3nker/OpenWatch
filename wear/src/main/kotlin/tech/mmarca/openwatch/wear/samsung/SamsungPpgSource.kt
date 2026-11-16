package tech.mmarca.openwatch.wear.samsung

import android.os.Handler
import android.os.SystemClock
import tech.mmarca.healthcalculations.signal.NightHrv
import tech.mmarca.openwatch.wear.DebugProtocol
import tech.mmarca.openwatch.wear.HrvWindowSource
import tech.mmarca.openwatch.wear.NightWindowResult

/**
 * The green PPG of the Samsung Health Platform's continuous tracker for a
 * night-time HRV window: the same signal as the raw sensor
 * (`PpgWindowSource`), handed over by the platform instead, and analysed the
 * same way ([NightHrv]). The tracker runs only for the window. The platform
 * batches its points, so at the window's end a flush asks for the ones it
 * still holds; its answer, or a point from after the end, closes the window,
 * and so does [FLUSH_WAIT_MILLIS].
 *
 * Written from the platform's code, not yet run on a watch: the sample rate
 * the analysis needs (25 Hz) and the flush with the screen off are to be
 * measured.
 */
internal class SamsungPpgSource(private val feed: SamsungHeartRateFeed) : HrvWindowSource, SamsungHeartRateFeed.RawSubscriber {

    private var handler: Handler? = null
    private var startedAt = 0L
    private var times = DoubleArray(INITIAL_CAPACITY)
    private var values = DoubleArray(INITIAL_CAPACITY)
    private var count = 0
    private var failed = false
    private var waiting: (() -> Unit)? = null
    private var waitingFor = 0L
    private var subscribed = false

    /** Whether the last window ended with a platform error or no sample at all: the platform is not serving. */
    var lastWindowFailed: Boolean = false
        private set

    override val isAvailable: Boolean
        get() = feed.isAvailable

    override fun begin(handler: Handler, startMillis: Long): Boolean {
        cancel()
        this.handler = handler
        startedAt = startMillis
        failed = false
        subscribed = feed.subscribeRaw(
            SamsungHealthPlatform.TRACKER_PPG, this, handler,
            ppgColours = listOf(SamsungHealthPlatform.PPG_GREEN),
        )
        return subscribed
    }

    override fun onRawPoints(points: List<RawPoint>) {
        if (!subscribed) return
        var coversEnd = false
        for (raw in points) {
            val point = raw.toPpgPoint()
            if (waitingFor != 0L && point.epochMillis >= waitingFor) coversEnd = true
            // A reading the platform kept from before the window, or one past it.
            if (point.epochMillis < startedAt || (waitingFor != 0L && point.epochMillis > waitingFor)) continue
            if (count >= MAX_SAMPLES) continue
            if (count == times.size) {
                times = times.copyOf(count * 2)
                values = values.copyOf(count * 2)
            }
            times[count] = point.epochMillis.toDouble()
            values[count] = point.green.toDouble()
            count++
        }
        if (coversEnd) waiting?.invoke()
    }

    override fun onFlushed() {
        waiting?.invoke()
    }

    override fun onError() {
        failed = true
    }

    override fun finish(endMillis: Long, onResult: (NightWindowResult) -> Unit) {
        val workerHandler = handler
        if (!subscribed || workerHandler == null) return onResult(NightWindowResult(null))
        val askedAt = SystemClock.elapsedRealtime()
        var done = false
        val deliver = deliver@{
            if (done) return@deliver
            done = true
            val t = times.copyOf(count)
            val v = values.copyOf(count)
            val window = startedAt
            val hadError = failed
            lastWindowFailed = hadError || t.isEmpty()
            cancel()
            val result = if (hadError) null else NightHrv.analyze(t, v)
            val breathing = if (hadError) null else NightHrv.respiration(t, v)
            DebugProtocol.event(
                "NightHrv", "samsung_ppg_window",
                "startedAt" to window,
                "waitMs" to SystemClock.elapsedRealtime() - askedAt,
                "samples" to t.size,
                "error" to hadError,
                "rmssd" to result?.rmssdMillis,
                "beats" to result?.beatCount,
                "rr" to breathing?.value,
            )
            onResult(NightWindowResult(result, breathing?.value, breathing?.routes ?: 0))
        }
        waiting = deliver
        waitingFor = endMillis
        if (!feed.flush(SamsungHealthPlatform.TRACKER_PPG)) return deliver()
        workerHandler.postAtTime(deliver, FLUSH_TOKEN, SystemClock.uptimeMillis() + FLUSH_WAIT_MILLIS)
    }

    override fun cancel() {
        if (subscribed) feed.unsubscribeRaw(SamsungHealthPlatform.TRACKER_PPG, this)
        subscribed = false
        waiting = null
        waitingFor = 0L
        handler?.removeCallbacksAndMessages(FLUSH_TOKEN)
        count = 0
        startedAt = 0L
    }

    private companion object {
        val FLUSH_TOKEN = Any()

        /** A sensor's flush answers within a second where it answers; past this the samples in hand are used. */
        const val FLUSH_WAIT_MILLIS = 15_000L

        /** 25 Hz for five minutes is 7 500; more than this is not kept. */
        const val MAX_SAMPLES = 25 * 330
        const val INITIAL_CAPACITY = 8_192
    }
}
