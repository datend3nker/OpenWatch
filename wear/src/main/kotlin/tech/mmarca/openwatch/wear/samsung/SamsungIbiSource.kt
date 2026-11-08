package tech.mmarca.openwatch.wear.samsung

import android.os.Handler
import android.os.SystemClock
import tech.mmarca.openwatch.wear.DebugProtocol
import tech.mmarca.openwatch.wear.HrvRmssd
import tech.mmarca.openwatch.wear.HrvWindowSource
import tech.mmarca.openwatch.wear.NightWindowResult
import tech.mmarca.openwatch.wear.RespiratoryRate

/**
 * The beat-to-beat intervals the Samsung Health Platform computes itself,
 * for a night-time HRV window, read from the shared [SamsungHeartRateFeed].
 * The source subscribes only for the window (the feed may already run for
 * the heart rate recorder). With the screen off the platform hands its
 * points over in batches up to ten minutes late, and a flush does not hurry
 * them (on a Galaxy Watch7 the flush answered in 0.2 s and the window's own
 * points were not among those delivered). So after the window the source
 * stays subscribed until a point stamped after the window's end arrives,
 * at most [BATCH_WAIT_MILLIS], and keeps the intervals that ended inside the
 * window ([SamsungIbiWindow]).
 */
internal class SamsungIbiSource(private val feed: SamsungHeartRateFeed) : HrvWindowSource, SamsungHeartRateFeed.Subscriber {

    private var handler: Handler? = null
    private var startedAt = 0L
    private val points = ArrayList<IbiPoint>()
    private var failed = false
    private var waiting: (() -> Unit)? = null
    private var waitingFor = 0L
    private var subscribed = false

    /** Whether the last window ended with a platform error or no point at all: the platform is not serving. */
    var lastWindowFailed: Boolean = false
        private set

    override val isAvailable: Boolean
        get() = feed.isAvailable

    override fun begin(handler: Handler, startMillis: Long): Boolean {
        cancel()
        this.handler = handler
        startedAt = startMillis
        failed = false
        subscribed = feed.subscribe(this, handler)
        return subscribed
    }

    override fun onPoints(points: List<IbiPoint>) {
        if (subscribed && this.points.size < MAX_POINTS) this.points += points
        // The window is covered once a point from after its end has come in.
        if (waitingFor != 0L && points.any { it.epochMillis >= waitingFor }) waiting?.invoke()
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
            workerHandler.removeCallbacksAndMessages(FLUSH_TOKEN)
            val intervals = SamsungIbiWindow.intervals(points, startedAt, endMillis)
            val hadError = failed
            lastWindowFailed = hadError || points.isEmpty()
            val result = if (hadError) null else HrvRmssd.ofIntervals(intervals)
            val (times, values) = SamsungIbiWindow.timedIntervals(points, startedAt, endMillis)
            val breathing = if (hadError) null else RespiratoryRate.fromIntervalSeries(times, values)
            DebugProtocol.event(
                "NightHrv", "samsung_window",
                "startedAt" to startedAt,
                "waitMs" to SystemClock.elapsedRealtime() - askedAt,
                "points" to points.size,
                "intervals" to intervals.size,
                "flagged" to intervals.count { it.isNaN() },
                "error" to hadError,
                "rmssd" to result?.rmssdMillis,
                "beats" to result?.beatCount,
                "rr" to breathing?.breathsPerMinute,
                "rrShare" to breathing?.peakShare,
            )
            cancel()
            onResult(NightWindowResult(result, breathing?.breathsPerMinute, if (breathing != null) 1 else 0))
        }
        if (points.any { it.epochMillis >= endMillis }) return deliver()
        waiting = deliver
        waitingFor = endMillis
        // A flush may still bring the newest points sooner; the answer itself is not waited for.
        feed.flush()
        workerHandler.postAtTime(deliver, FLUSH_TOKEN, SystemClock.uptimeMillis() + BATCH_WAIT_MILLIS)
    }

    override fun cancel() {
        if (subscribed) feed.unsubscribe(this)
        subscribed = false
        waiting = null
        waitingFor = 0L
        handler?.removeCallbacksAndMessages(FLUSH_TOKEN)
        points.clear()
        startedAt = 0L
    }

    private companion object {
        val FLUSH_TOKEN = Any()

        /** The platform's batches came up to ten minutes late; past this the points in hand are used. */
        const val BATCH_WAIT_MILLIS = 12L * 60 * 1000

        /** One point a second for the window and the wait, plus the history the first point carries. */
        const val MAX_POINTS = 3_000
    }
}
