package tech.mmarca.openwatch.wear.samsung

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import tech.mmarca.openwatch.wear.DebugProtocol

/**
 * The one connection this app holds to the Samsung Health Platform, shared
 * by everything that reads it: the heart rate tracker (the heart rate
 * recorder and the HRV window) and the skin temperature tracker. The
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

    fun interface SkinSubscriber {
        fun onSkinPoints(points: List<SkinPoint>)
    }

    private val lock = Any()
    private val subscribers = LinkedHashMap<Subscriber, Handler>()
    private val skinSubscribers = LinkedHashMap<SkinSubscriber, Handler>()
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

    /** Joins the skin temperature and starts its tracker if this is the first. */
    fun subscribeSkin(subscriber: SkinSubscriber, handler: Handler): Boolean = synchronized(lock) {
        skinSubscribers[subscriber] = handler
        if (!ensurePlatform()) {
            skinSubscribers.remove(subscriber)
            return false
        }
        if (skinSubscribers.size == 1) track(SamsungHealthPlatform.TRACKER_SKIN_TEMPERATURE, subscriber.javaClass.simpleName)
        true
    }

    fun unsubscribeSkin(subscriber: SkinSubscriber) = synchronized(lock) {
        if (skinSubscribers.remove(subscriber) == null || skinSubscribers.isNotEmpty()) return
        untrack(SamsungHealthPlatform.TRACKER_SKIN_TEMPERATURE)
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
        val client = SamsungHealthPlatform(context, workerHandler, ::failed)
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
            )
            else -> SamsungHealthPlatform.Tracker(
                onPoints = { raw -> deliverSkin(raw.map { it.toSkinPoint() }) },
                onFlushed = {},
            )
        }
        handler?.post { client.track(type, tracker) }
        DebugProtocol.event("SamsungFeed", "started", "type" to type, "subscriber" to by)
    }

    private fun untrack(type: String) {
        val client = platform ?: return
        val workerHandler = handler ?: return
        DebugProtocol.event("SamsungFeed", "stopped", "type" to type, "ranMs" to SystemClock.elapsedRealtime() - (startedAt[type] ?: 0L))
        val last = subscribers.isEmpty() && skinSubscribers.isEmpty()
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

    private fun deliverSkin(points: List<SkinPoint>) {
        if (points.isEmpty()) return
        firstDelivery(SamsungHealthPlatform.TRACKER_SKIN_TEMPERATURE, points.size, 0)
        DebugProtocol.count("samsung.skinPoints", points.size.toLong())
        val targets = synchronized(lock) { skinSubscribers.toList() }
        for ((subscriber, target) in targets) target.post { subscriber.onSkinPoints(points) }
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
        for ((subscriber, target) in targets) target.post { action(subscriber) }
    }
}
