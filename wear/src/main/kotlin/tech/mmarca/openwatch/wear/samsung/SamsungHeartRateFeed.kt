package tech.mmarca.openwatch.wear.samsung

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import tech.mmarca.openwatch.wear.DebugProtocol

/**
 * The one connection this app holds to the Samsung Health Platform, shared
 * by everything that reads it: the heart rate tracker (the heart rate
 * recorder and the HRV window) and the other trackers (skin temperature, SpO2). The
 * platform keeps one client per package, so a second binding of its own
 * would be no gain; one binding with one tracker per type is what it serves.
 *
 * A type runs while at least one subscriber of it is in, the binding while
 * any type runs. Each delivery is handed to every subscriber of its type on
 * that subscriber's own handler. [subscribe], [unsubscribe] and [flush] may
 * be called from any thread.
 */
internal class SamsungHeartRateFeed(private val context: Context) {

    interface Subscriber {
        fun onPoints(points: List<IbiPoint>)

        /** The flush asked for has been answered: every point held until then was delivered. */
        fun onFlushed() = Unit

        /** The platform refused, failed or went away; the subscription stays and may recover. */
        fun onError() = Unit
    }

    /** A reader of one other tracker type (skin temperature, SpO2): the raw points, decoded by the reader. */
    fun interface RawSubscriber {
        fun onRawPoints(points: List<RawPoint>)

        /** The tracker failed (a missing permission, a platform error). */
        fun onError() = Unit
    }

    private val lock = Any()
    private val subscribers = LinkedHashMap<Subscriber, Handler>()
    private val rawSubscribers = LinkedHashMap<String, LinkedHashMap<RawSubscriber, Handler>>()
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var platform: SamsungHealthPlatform? = null
    private val startedAt = HashMap<String, Long>()
    private val delivered = HashSet<String>()
    private var flushAskedAt = 0L

    val isAvailable: Boolean = SamsungHealthPlatform.isInstalled(context)

    /** Joins the heart rate and starts its tracker if this is the first. False when the platform refused the bind. */
    fun subscribe(subscriber: Subscriber, handler: Handler): Boolean = synchronized(lock) {
        subscribers[subscriber] = handler
        if (!ensurePlatform()) {
            subscribers.remove(subscriber)
            return false
        }
        if (subscribers.size == 1) track(SamsungHealthPlatform.TRACKER_HEART_RATE, subscriber.javaClass.simpleName)
        true
    }

    fun unsubscribe(subscriber: Subscriber) = synchronized(lock) {
        if (subscribers.remove(subscriber) == null || subscribers.isNotEmpty()) return
        untrack(SamsungHealthPlatform.TRACKER_HEART_RATE)
    }

    /** Joins tracker [type] and starts it if this is its first reader. False when the platform refused the bind. */
    fun subscribeRaw(type: String, subscriber: RawSubscriber, handler: Handler): Boolean = synchronized(lock) {
        val readers = rawSubscribers.getOrPut(type) { LinkedHashMap() }
        readers[subscriber] = handler
        if (!ensurePlatform()) {
            readers.remove(subscriber)
            return false
        }
        if (readers.size == 1) track(type, subscriber.javaClass.simpleName)
        true
    }

    fun unsubscribeRaw(type: String, subscriber: RawSubscriber) = synchronized(lock) {
        val readers = rawSubscribers[type] ?: return
        if (readers.remove(subscriber) == null || readers.isNotEmpty()) return
        rawSubscribers.remove(type)
        untrack(type)
    }

    /** Asks the platform for the heart rate points it still batches. False when the tracker is not connected. */
    fun flush(): Boolean = synchronized(lock) {
        val client = platform ?: return false
        val workerHandler = handler ?: return false
        // The platform is driven from the feed's own thread; the answer arrives as onFlushed.
        workerHandler.post {
            val asked = client.flush(SamsungHealthPlatform.TRACKER_HEART_RATE)
            flushAskedAt = if (asked) SystemClock.elapsedRealtime() else 0L
            DebugProtocol.event("SamsungFeed", "flush_asked", "ok" to asked)
            if (!asked) forEach { it.onFlushed() }
        }
        true
    }

    // Under the lock.

    private fun ensurePlatform(): Boolean {
        if (platform != null) return true
        val worker = HandlerThread("SamsungHeartRateFeed").apply { start() }
        val workerHandler = Handler(worker.looper)
        val client = SamsungHealthPlatform(context, workerHandler) { failed(null) }
        if (!client.start()) {
            worker.quitSafely()
            DebugProtocol.event("SamsungFeed", "bind_refused")
            return false
        }
        thread = worker
        handler = workerHandler
        platform = client
        return true
    }

    private fun track(type: String, by: String) {
        val client = platform ?: return
        startedAt[type] = SystemClock.elapsedRealtime()
        delivered -= type
        val tracker = when (type) {
            SamsungHealthPlatform.TRACKER_HEART_RATE -> SamsungHealthPlatform.Tracker(
                onPoints = { raw -> deliverHeartRate(raw.map { it.toHeartRatePoint() }) },
                onFlushed = ::flushed,
                onError = { failed(type) },
            )
            else -> SamsungHealthPlatform.Tracker(
                onPoints = { raw -> deliverRaw(type, raw) },
                onFlushed = {},
                onError = { failed(type) },
            )
        }
        handler?.post { client.track(type, tracker) }
        DebugProtocol.event("SamsungFeed", "started", "type" to type, "subscriber" to by)
    }

    private fun untrack(type: String) {
        val client = platform ?: return
        val workerHandler = handler ?: return
        DebugProtocol.event("SamsungFeed", "stopped", "type" to type, "ranMs" to SystemClock.elapsedRealtime() - (startedAt[type] ?: 0L))
        val last = subscribers.isEmpty() && rawSubscribers.isEmpty()
        val worker = thread
        workerHandler.post {
            client.untrack(type)
            if (last) client.stop()
        }
        if (last) {
            platform = null
            handler = null
            thread = null
            worker?.quitSafely()
        }
    }

    // The callbacks below run on the feed's own thread.

    private fun firstDelivery(type: String, points: Int, intervals: Int) {
        if (!delivered.add(type)) return
        DebugProtocol.event(
            "SamsungFeed", "first_delivery",
            "type" to type,
            "afterMs" to SystemClock.elapsedRealtime() - (startedAt[type] ?: 0L),
            "points" to points,
            "intervals" to intervals,
        )
    }

    private fun deliverHeartRate(points: List<IbiPoint>) {
        if (points.isEmpty()) return
        val now = System.currentTimeMillis()
        firstDelivery(SamsungHealthPlatform.TRACKER_HEART_RATE, points.size, points.sumOf { it.intervalsMillis.size })
        DebugProtocol.count("samsung.deliveries")
        DebugProtocol.count("samsung.points", points.size.toLong())
        DebugProtocol.count("samsung.intervals", points.sumOf { it.intervalsMillis.size }.toLong())
        points.minOfOrNull { it.epochMillis }?.let { DebugProtocol.max("samsung.lagMs.max", now - it) }
        forEach { it.onPoints(points) }
    }

    private fun deliverRaw(type: String, points: List<RawPoint>) {
        if (points.isEmpty()) return
        firstDelivery(type, points.size, 0)
        DebugProtocol.count("samsung.points.$type", points.size.toLong())
        val targets = synchronized(lock) { rawSubscribers[type]?.toList().orEmpty() }
        for ((subscriber, target) in targets) target.post { subscriber.onRawPoints(points) }
    }

    private fun flushed() {
        val asked = flushAskedAt
        DebugProtocol.event("SamsungFeed", "flush_done", "afterMs" to if (asked == 0L) null else SystemClock.elapsedRealtime() - asked)
        forEach { it.onFlushed() }
    }

    /** A tracker's failure ([type]), or the binding's (null), which hits every tracker. */
    private fun failed(type: String?) {
        DebugProtocol.event("SamsungFeed", "error", "type" to type)
        if (type == null || type == SamsungHealthPlatform.TRACKER_HEART_RATE) forEach { it.onError() }
        if (type != SamsungHealthPlatform.TRACKER_HEART_RATE) {
            val readers = synchronized(lock) {
                rawSubscribers.filterKeys { type == null || it == type }.values.flatMap { it.toList() }
            }
            for ((subscriber, target) in readers) target.post { subscriber.onError() }
        }
    }

    private fun forEach(action: (Subscriber) -> Unit) {
        val targets = synchronized(lock) { subscribers.toList() }
        for ((subscriber, target) in targets) target.post { action(subscriber) }
    }
}
