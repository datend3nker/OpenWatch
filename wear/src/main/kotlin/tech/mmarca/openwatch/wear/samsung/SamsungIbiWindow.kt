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
 *
 * One kind of batch breaks that rule and is put right before anyone reads it
 * ([SamsungIbiWindow.placeLump]): the points a flush brings carry every
 * interval of the batch on its first point.
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

    /**
     * The good intervals that ended inside the window with the time of their
     * middle, for the breathing rate, which reads the intervals as a time
     * series and needs no unbroken run.
     */
    fun timedIntervals(points: List<IbiPoint>, startMillis: Long, endMillis: Long): Pair<DoubleArray, DoubleArray> {
        val times = ArrayList<Double>()
        val values = ArrayList<Double>()
        for (point in points.sortedBy { it.epochMillis }) {
            val ends = endTimes(point)
            for (i in point.intervalsMillis.indices) {
                if (ends[i] < startMillis || ends[i] > endMillis || point.statuses[i] != STATUS_GOOD) continue
                times += ends[i] - point.intervalsMillis[i] / 2.0
                values += point.intervalsMillis[i].toDouble()
            }
        }
        return times.toDoubleArray() to values.toDoubleArray()
    }

    /**
     * The one point of [delivery] that carries all of its intervals without
     * being its last, or null. A batch the platform hands over by itself
     * spreads its intervals over its points, at most a few each; the batch a
     * flush brings has them all on its first point. Seen on a Galaxy Watch7
     * in the nights of 2026-11-10 to 13: a flushed batch of five minutes
     * carried as many intervals as one of ten carried twice over (0.84 a
     * point either way), so they are the batch's own beats, not the minutes
     * before it.
     */
    fun lumpCarrier(delivery: List<IbiPoint>): IbiPoint? {
        if (delivery.size < LUMP_MIN_POINTS) return null
        val carrier = delivery.singleOrNull { it.intervalsMillis.isNotEmpty() } ?: return null
        return carrier.takeIf { it.epochMillis < delivery.maxOf { point -> point.epochMillis } }
    }

    /**
     * [delivery] with a lump's intervals moved to its last point, so they are
     * timed back from the end of the batch they were measured in; timed back
     * from the first point they would all fall before it, and a window read
     * from a flushed batch had no interval at all. Only when the intervals
     * add up to the batch's length within [LUMP_TOLERANCE]: a shorter sum
     * means beats are missing somewhere and their times cannot be told, a
     * longer one is history from before the batch. Every other delivery, and
     * such a lump, is returned as it came (the same list).
     */
    fun placeLump(delivery: List<IbiPoint>): List<IbiPoint> {
        val carrier = lumpCarrier(delivery) ?: return delivery
        val last = delivery.maxBy { it.epochMillis }
        val span = last.epochMillis - delivery.minOf { it.epochMillis }
        val sum = carrier.intervalsMillis.sumOf { it.toLong() }
        if (abs(sum - span) > LUMP_TOLERANCE * span) return delivery
        return delivery.map { point ->
            when {
                point === carrier -> IbiPoint(point.epochMillis, IntArray(0), IntArray(0), point.bpm, point.heartRateStatus)
                point === last -> IbiPoint(point.epochMillis, carrier.intervalsMillis, carrier.statuses, point.bpm, point.heartRateStatus)
                else -> point
            }
        }
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

    /** Half a minute of points: a short delivery with one carrier is a live point or the history a start brings. */
    private const val LUMP_MIN_POINTS = 30

    /** How far a lump's intervals may differ from its batch's length and still be timed by it. This app's own choice. */
    private const val LUMP_TOLERANCE = 0.1
}
