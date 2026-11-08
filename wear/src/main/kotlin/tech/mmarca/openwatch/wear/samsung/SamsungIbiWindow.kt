package tech.mmarca.openwatch.wear.samsung

import kotlin.math.abs

/**
 * One heart rate point from the Samsung Health Platform: its time, the rate
 * and its status (1 is a measured rate), and the beat-to-beat intervals it
 * carries, oldest first, each with the platform's status (0 is a good
 * interval, anything else is flagged).
 *
 * A point's intervals have no times of their own. They are the beats up to
 * the point, so the last one ends at [epochMillis] and each earlier one ends
 * where the next began. The first point after a start can carry minutes of
 * history (535 intervals on the Galaxy Watch7), and with the screen off the
 * points arrive in batches long after their time; both are why a window is
 * cut by these reconstructed times, never by arrival.
 */
internal class IbiPoint(
    val epochMillis: Long,
    val intervalsMillis: IntArray,
    val statuses: IntArray,
    val bpm: Int = 0,
    val heartRateStatus: Int = 0,
) {
    init {
        require(intervalsMillis.size == statuses.size)
    }

    /** Whether the point carries a measured rate. */
    val hasHeartRate: Boolean
        get() = heartRateStatus == HEART_RATE_MEASURED && bpm > 0

    private companion object {
        const val HEART_RATE_MEASURED = 1
    }
}

/** Turns the points of a window into the interval run `HrvRmssd.ofIntervals` reads. Pure. */
internal object SamsungIbiWindow {

    /**
     * Two points whose reconstructed runs meet within this are one rhythm; a
     * wider gap or overlap is a break, and no difference is taken across it.
     */
    const val MAX_JOIN_MILLIS = 300L

    /**
     * The intervals that ended inside [startMillis, endMillis], in time order,
     * with `NaN` for each flagged one and for each break between points.
     */
    fun intervals(points: List<IbiPoint>, startMillis: Long, endMillis: Long): DoubleArray {
        val out = ArrayList<Double>()
        var previousEnd: Long? = null
        for (point in points.sortedBy { it.epochMillis }) {
            val ends = endTimes(point)
            val firstStart = point.epochMillis - point.intervalsMillis.sum()
            var joined = previousEnd != null && abs(firstStart - previousEnd) <= MAX_JOIN_MILLIS
            for (i in point.intervalsMillis.indices) {
                if (ends[i] < startMillis || ends[i] > endMillis) {
                    joined = false
                    continue
                }
                if (!joined && out.isNotEmpty() && !out.last().isNaN()) out += Double.NaN
                joined = true
                out += if (point.statuses[i] == STATUS_GOOD) point.intervalsMillis[i].toDouble() else Double.NaN
            }
            if (point.intervalsMillis.isNotEmpty()) previousEnd = point.epochMillis
        }
        return out.toDoubleArray()
    }

    private fun endTimes(point: IbiPoint): LongArray {
        val ends = LongArray(point.intervalsMillis.size)
        var end = point.epochMillis
        for (i in ends.indices.reversed()) {
            ends[i] = end
            end -= point.intervalsMillis[i]
        }
        return ends
    }

    private const val STATUS_GOOD = 0
}
