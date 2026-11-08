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
 * the heart rate recorder); at its end a flush fetches the points the
 * platform still batches, and the intervals that ended inside the window
 * are kept ([SamsungIbiWindow]).
 */
internal class SamsungIbiSource(private val feed: SamsungHeartRateFeed) : HrvWindowSource, SamsungHeartRateFeed.Subscriber {

    private var handler: Handler? = null
    private var startedAt = 0L
    private val points = ArrayList<IbiPoint>()
    private var failed = false
    private var waiting: (() -> Unit)? = null
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
        waiting = deliver
        if (!feed.flush()) return deliver()
        workerHandler.postAtTime(deliver, FLUSH_TOKEN, SystemClock.uptimeMillis() + FLUSH_TIMEOUT_MILLIS)
    }

    override fun cancel() {
        if (subscribed) feed.unsubscribe(this)
        subscribed = false
        waiting = null
        handler?.removeCallbacksAndMessages(FLUSH_TOKEN)
        points.clear()
        startedAt = 0L
    }

    private companion object {
        val FLUSH_TOKEN = Any()

        /** The flush answer is awaited this long; past it the points in hand are used. */
        const val FLUSH_TIMEOUT_MILLIS = 15_000L

        /** One point a second for five minutes, plus the history the first point carries. */
        const val MAX_POINTS = 2_000
    }
}
