package tech.mmarca.openwatch.wear.samsung

import android.content.Context
import android.os.Handler
import android.util.Log
import tech.mmarca.openwatch.wear.HrvRmssd
import tech.mmarca.openwatch.wear.HrvWindowResult
import tech.mmarca.openwatch.wear.HrvWindowSource

/**
 * The beat-to-beat intervals the Samsung Health Platform computes itself,
 * for a night-time HRV window ([SamsungHealthPlatform]). The tracker runs
 * only for the window; at its end a flush fetches the points the platform
 * still batches, and the intervals that ended inside the window are kept
 * ([SamsungIbiWindow]).
 */
internal class SamsungIbiSource(private val context: Context) : HrvWindowSource {

    private var platform: SamsungHealthPlatform? = null
    private var handler: Handler? = null
    private var startedAt = 0L
    private val points = ArrayList<IbiPoint>()
    private var failed = false
    private var waiting: (() -> Unit)? = null

    override val isAvailable: Boolean = SamsungHealthPlatform.isInstalled(context)

    override fun begin(handler: Handler, startMillis: Long): Boolean {
        cancel()
        this.handler = handler
        startedAt = startMillis
        failed = false
        val client = SamsungHealthPlatform(
            context,
            handler,
            onPoint = { if (platform != null && points.size < MAX_POINTS) points += it },
            onFlushed = { waiting?.invoke() },
            onError = { failed = true },
        )
        platform = client
        if (client.start()) return true
        platform = null
        return false
    }

    override fun finish(endMillis: Long, onResult: (HrvWindowResult?) -> Unit) {
        val client = platform
        val workerHandler = handler
        if (client == null || workerHandler == null) return onResult(null)
        var done = false
        val deliver = {
            if (!done) {
                done = true
                workerHandler.removeCallbacksAndMessages(FLUSH_TOKEN)
                val intervals = SamsungIbiWindow.intervals(points, startedAt, endMillis)
                val hadError = failed
                cancel()
                if (hadError) Log.i(TAG, "Window had a platform error")
                onResult(if (hadError) null else HrvRmssd.ofIntervals(intervals))
            }
        }
        waiting = deliver
        if (!client.flush()) return deliver()
        workerHandler.postAtTime(deliver, FLUSH_TOKEN, android.os.SystemClock.uptimeMillis() + FLUSH_TIMEOUT_MILLIS)
    }

    override fun cancel() {
        platform?.stop()
        platform = null
        waiting = null
        handler?.removeCallbacksAndMessages(FLUSH_TOKEN)
        points.clear()
        startedAt = 0L
    }

    private companion object {
        const val TAG = "SamsungIbiSource"
        val FLUSH_TOKEN = Any()

        /** The flush answers within a second on the Galaxy Watch7; past this the points in hand are used. */
        const val FLUSH_TIMEOUT_MILLIS = 15_000L

        /** One point a second for five minutes, plus the history the first point carries. */
        const val MAX_POINTS = 2_000
    }
}
