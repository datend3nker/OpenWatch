package tech.mmarca.openwatch.wear

import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import kotlin.math.roundToInt
import tech.mmarca.openwatch.wear.samsung.RawPoint
import tech.mmarca.openwatch.wear.samsung.SamsungHealthPlatform
import tech.mmarca.openwatch.wear.samsung.SamsungHeartRateFeed
import tech.mmarca.openwatch.wear.samsung.toSkinPoint
import tech.mmarca.openwatch.wear.samsung.SkinPoint
import tech.mmarca.openwatch.wearlink.WearLinkProtocol

/**
 * Skin temperature at night from the Samsung Health Platform, one
 * [WearMetrics.SKIN_TEMPERATURE] row per clock minute: the mean of the
 * minute's good points, skin and ambient.
 *
 * Runs only while sleep is held and the wearer switched it on
 * ([WearSettings.skinTemperature]); a watch without the platform has
 * no skin temperature, since the sensor itself is closed to ordinary apps.
 * In the bench test on a Galaxy Watch7 the tracker delivered nothing; the
 * debug protocol counts what arrives (`samsung.skinPoints`, `skin.*`).
 *
 * The platform hands the points over in batches of an hour, so the last
 * stretch of a night was still held when the hold ended (07:31 to 08:24 on
 * 2026-11-10). Stopping therefore asks for a flush first and leaves when it
 * is answered, or after [FLUSH_TIMEOUT_MILLIS]; whether the platform answers
 * for this tracker is read off the debug protocol (`flush_done`, type skin).
 */
internal class SkinTemperatureRecorder(
    private val store: MetricStore,
    private val feed: SamsungHeartRateFeed,
) : SamsungHeartRateFeed.RawSubscriber {

    private val lock = Any()
    private var thread: HandlerThread? = null

    /** A stop waits for the flush; a start meanwhile cancels it. Under [lock]. */
    private var stopping = false

    val isAvailable: Boolean
        get() = feed.isAvailable

    val isRunning: Boolean
        get() = thread != null

    /** Starts or stops the recorder. Main thread. */
    fun setEnabled(enabled: Boolean) {
        if (enabled && feed.isAvailable) start() else stop()
    }

    private fun start() {
        synchronized(lock) {
            if (thread != null) {
                stopping = false
                return
            }
            val worker = HandlerThread("SkinTemperatureRecorder").apply { start() }
            if (!feed.subscribeRaw(SamsungHealthPlatform.TRACKER_SKIN_TEMPERATURE, this, Handler(worker.looper))) {
                worker.quitSafely()
                return
            }
            thread = worker
        }
        Log.i(TAG, "Skin temperature on")
    }

    /** Asks for the points still batched, then leaves. */
    fun stop() {
        val worker = synchronized(lock) {
            val running = thread ?: return
            if (stopping) return
            stopping = true
            running
        }
        if (feed.flush(SamsungHealthPlatform.TRACKER_SKIN_TEMPERATURE)) {
            Handler(worker.looper).postDelayed({ leave(worker) }, FLUSH_TIMEOUT_MILLIS)
        } else {
            leave(worker)
        }
    }

    override fun onFlushed() {
        val worker = synchronized(lock) { thread } ?: return
        leave(worker)
    }

    private fun leave(worker: HandlerThread) {
        synchronized(lock) {
            if (!stopping || thread !== worker) return
            stopping = false
            thread = null
            feed.unsubscribeRaw(SamsungHealthPlatform.TRACKER_SKIN_TEMPERATURE, this)
        }
        worker.quitSafely()
        Log.i(TAG, "Skin temperature off")
    }

    override fun onError() {
        DebugProtocol.event("Skin", "tracker_error")
    }

    override fun onRawPoints(raw: List<RawPoint>) {
        val points = raw.map { it.toSkinPoint() }
        val rows = minutes(points)
        DebugProtocol.count("skin.points", points.size.toLong())
        DebugProtocol.count("skin.rows", rows.size.toLong())
        if (rows.isEmpty()) return
        store.putAll(WearMetrics.SKIN_TEMPERATURE, rows)
        store.prune(WearMetrics.SKIN_TEMPERATURE, System.currentTimeMillis())
    }

    companion object {
        private const val TAG = "SkinTemperatureRecorder"
        private const val MINUTE_MILLIS = 60_000L

        /** How long a stop waits for the platform's answer to the flush. */
        private const val FLUSH_TIMEOUT_MILLIS = 15_000L

        /** The platform's status for a measured point. */
        private const val STATUS_MEASURED = 0

        /** The minutes the good points fall in, each the mean of its points. Pure. */
        internal fun minutes(points: List<SkinPoint>): List<WearLinkProtocol.SkinMinute> =
            points.filter { it.status == STATUS_MEASURED && !it.objectCelsius.isNaN() && !it.ambientCelsius.isNaN() }
                .groupBy { it.epochMillis - it.epochMillis % MINUTE_MILLIS }
                .mapNotNull { (minute, inMinute) ->
                    val skin = (inMinute.map { it.objectCelsius }.average() * 100).roundToInt()
                    val ambient = (inMinute.map { it.ambientCelsius }.average() * 100).roundToInt()
                    val row = WearLinkProtocol.SkinMinute(minute, skin, ambient)
                    row.takeIf { WearLinkProtocol.parseSkinMinute(WearLinkProtocol.formatSkinMinute(it)) != null }
                }
                .sortedBy { it.epochMillis }
    }
}
