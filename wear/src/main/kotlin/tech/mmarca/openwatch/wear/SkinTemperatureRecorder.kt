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
 */
internal class SkinTemperatureRecorder(
    private val store: MetricStore,
    private val feed: SamsungHeartRateFeed,
) : SamsungHeartRateFeed.RawSubscriber {

    private var thread: HandlerThread? = null

    val isAvailable: Boolean
        get() = feed.isAvailable

    val isRunning: Boolean
        get() = thread != null

    /** Starts or stops the recorder. Main thread. */
    fun setEnabled(enabled: Boolean) {
        if (enabled && feed.isAvailable) start() else stop()
    }

    private fun start() {
        if (thread != null) return
        val worker = HandlerThread("SkinTemperatureRecorder").apply { start() }
        if (!feed.subscribeRaw(SamsungHealthPlatform.TRACKER_SKIN_TEMPERATURE, this, Handler(worker.looper))) {
            worker.quitSafely()
            return
        }
        thread = worker
        Log.i(TAG, "Skin temperature on")
    }

    fun stop() {
        val worker = thread ?: return
        feed.unsubscribeRaw(SamsungHealthPlatform.TRACKER_SKIN_TEMPERATURE, this)
        worker.quitSafely()
        thread = null
        Log.i(TAG, "Skin temperature off")
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
