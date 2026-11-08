package tech.mmarca.openwatch.wear.samsung

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import tech.mmarca.openwatch.wear.DebugProtocol

/**
 * The one connection this app holds to the Samsung Health Platform's heart
 * rate tracker, shared by everything that reads it. The platform keys its
 * clients by package: a second tracker from the same app would replace the
 * first one's listener, so the heart rate recorder and the HRV window
 * subscribe here instead of each binding.
 *
 * The tracker runs while at least one subscriber is in. Each delivery is
 * handed to every subscriber on that subscriber's own handler. Not thread
 * safe beyond that: [subscribe], [unsubscribe] and [flush] lock.
 */
internal class SamsungHeartRateFeed(private val context: Context) {

    interface Subscriber {
        fun onPoints(points: List<IbiPoint>)

        /** The flush asked for has been answered: every point held until then was delivered. */
        fun onFlushed() = Unit

        /** The platform refused, failed or went away; the subscription stays and may recover. */
        fun onError() = Unit
    }

    private val lock = Any()
    private val subscribers = LinkedHashMap<Subscriber, Handler>()
    private var thread: HandlerThread? = null
    private var platform: SamsungHealthPlatform? = null
    private var startedAt = 0L
    private var delivered = false
    private var flushAskedAt = 0L

    val isAvailable: Boolean = SamsungHealthPlatform.isInstalled(context)

    /** Joins the feed and starts the tracker if this is the first subscriber. False when the platform refused the bind. */
    fun subscribe(subscriber: Subscriber, handler: Handler): Boolean = synchronized(lock) {
        subscribers[subscriber] = handler
        if (platform != null) return true
        val worker = HandlerThread("SamsungHeartRateFeed").apply { start() }
        val workerHandler = Handler(worker.looper)
        val client = SamsungHealthPlatform(context, workerHandler, ::deliver, ::flushed, ::failed)
        startedAt = SystemClock.elapsedRealtime()
        delivered = false
        if (!client.start()) {
            worker.quitSafely()
            subscribers.remove(subscriber)
            DebugProtocol.event("SamsungFeed", "bind_refused")
            return false
        }
        thread = worker
        platform = client
        DebugProtocol.event("SamsungFeed", "started", "subscriber" to subscriber.javaClass.simpleName)
        true
    }

    fun unsubscribe(subscriber: Subscriber) = synchronized(lock) {
        if (subscribers.remove(subscriber) == null || subscribers.isNotEmpty()) return
        platform?.stop()
        platform = null
        thread?.quitSafely()
        thread = null
        DebugProtocol.event("SamsungFeed", "stopped", "ranMs" to SystemClock.elapsedRealtime() - startedAt)
    }

    /** Asks the platform for the points it still batches. False when the tracker is not connected. */
    fun flush(): Boolean = synchronized(lock) {
        val asked = platform?.flush() == true
        flushAskedAt = if (asked) SystemClock.elapsedRealtime() else 0L
        DebugProtocol.event("SamsungFeed", "flush_asked", "ok" to asked)
        asked
    }

    // The callbacks below run on the feed's own thread.

    private fun deliver(points: List<IbiPoint>) {
        val now = System.currentTimeMillis()
        if (!delivered && points.isNotEmpty()) {
            delivered = true
            DebugProtocol.event(
                "SamsungFeed", "first_delivery",
                "afterMs" to SystemClock.elapsedRealtime() - startedAt,
                "points" to points.size,
                "intervals" to points.sumOf { it.intervalsMillis.size },
            )
        }
        DebugProtocol.count("samsung.deliveries")
        DebugProtocol.count("samsung.points", points.size.toLong())
        DebugProtocol.count("samsung.intervals", points.sumOf { it.intervalsMillis.size }.toLong())
        points.minOfOrNull { it.epochMillis }?.let { DebugProtocol.max("samsung.lagMs.max", now - it) }
        forEach { subscriber -> subscriber.onPoints(points) }
    }

    private fun flushed() {
        val asked = flushAskedAt
        DebugProtocol.event("SamsungFeed", "flush_done", "afterMs" to if (asked == 0L) null else SystemClock.elapsedRealtime() - asked)
        forEach { it.onFlushed() }
    }

    private fun failed() {
        DebugProtocol.event("SamsungFeed", "error")
        forEach { it.onError() }
    }

    private fun forEach(action: (Subscriber) -> Unit) {
        val targets = synchronized(lock) { subscribers.toList() }
        for ((subscriber, handler) in targets) handler.post { action(subscriber) }
    }
}
