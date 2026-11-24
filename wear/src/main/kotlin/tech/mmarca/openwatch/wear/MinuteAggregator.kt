package tech.mmarca.openwatch.wear

import java.util.TreeMap
import kotlin.math.abs
import kotlin.math.sqrt
import tech.mmarca.openwatch.wearlink.WearLinkProtocol

/**
 * Folds accelerometer readings, heart rate samples, the worn and charging
 * state and screen wake-ups into one `SM` row per clock minute, exactly as
 * `docs/engineering/sleep-minute-features.md` specifies.
 *
 * Readings arrive in g. Pure Kotlin, so a test can feed it. A minute closes
 * once [Config.closeLagMillis] have passed since its start, by which time
 * the sensor hub has flushed every batch that belongs to it.
 *
 * A closed minute is never opened again. The Samsung platform hands its
 * heart rate over up to ten minutes late, long after the minute closed; such
 * a sample is kept aside ([takeLateHeartRates]) and joins the row already
 * stored ([withHeartRate]). Opened again, the minute closed a second time
 * with the pulse and no acceleration and replaced the stored row: in the
 * week to 2026-11-14 that emptied up to 78 % of a night's minutes.
 */
class MinuteAggregator(
    /** The watch's UTC offset at a minute, seconds. */
    private val offsetSecondsAt: (epochMillis: Long) -> Int,
    /** Whether heart rate is being recorded, read when a minute closes. */
    private val isHeartRateRecording: () -> Boolean,
    /** Whether the wake-up motion sensors watched the whole minute that starts at the given time ([WakeClock.watchedMotion]). */
    private val watchedMotion: (minuteStartMillis: Long) -> Boolean = { false },
    private val config: Config = Config(),
) {

    data class Config(
        /** In g. Below this a magnitude change is sensor noise, not a movement. */
        val jerkThresholdG: Double = 0.051,
        /** How much movement one counted reading is worth, at the nominal rate. */
        val movementPerCount: Double = 0.2,
        /** Fewer rate-normalised counts than this is a still minute. */
        val minCounts: Double = 3.0,
        /** The movement a minute saturates at. */
        val maxMovement: Double = 30.0,
        /** Fewer samples than this and the hub dropped part of the minute. */
        val sparseSamples: Int = 150,
        /** A minute closes this long after it started, once every batch for it has arrived. */
        val closeLagMillis: Long = 90_000L,
    )

    private class Bucket(start: Long) {
        val features = AccelerationMinuteFeatures(start)
        var counts = 0
        val heartRates = ArrayList<Double>(8)
        var charging = false
        var offBody = false
        var screenOn = false
        var heartRateNoContact = false
    }

    private val buckets = TreeMap<Long, Bucket>()

    /** The start of the newest minute [close] handed out; nothing at or before it gets a bucket again. */
    private var closedThrough = Long.MIN_VALUE
    private val lateHeartRates = TreeMap<Long, ArrayList<Int>>()
    private var lastMagnitude = Double.NaN
    private var lastAngle: Double? = null
    private var worn = true
    private var charging = false

    fun onAcceleration(epochMillis: Long, xG: Double, yG: Double, zG: Double) {
        val bucket = bucketAt(epochMillis) ?: return
        bucket.features.add(epochMillis, xG, yG, zG)
        val magnitude = sqrt(xG * xG + yG * yG + zG * zG)
        if (!lastMagnitude.isNaN() && abs(magnitude - lastMagnitude) > config.jerkThresholdG) bucket.counts++
        lastMagnitude = magnitude
        if (!worn) bucket.offBody = true
        if (charging) bucket.charging = true
    }

    /** A stored heart rate sample; never a no-contact reading. */
    fun onHeartRate(epochMillis: Long, bpm: Int) {
        val bucket = bucketAt(epochMillis)
        if (bucket != null) bucket.heartRates += bpm.toDouble() else lateHeartRates.getOrPut(minuteOf(epochMillis)) { ArrayList(8) } += bpm
    }

    /**
     * The heart rate samples that came for minutes already closed, by minute
     * start, oldest first; handed out once. The caller adds them to the rows
     * it stored ([withHeartRate]), or stores [heartRateOnly] where there is none.
     */
    fun takeLateHeartRates(): Map<Long, List<Int>> {
        if (lateHeartRates.isEmpty()) return emptyMap()
        val taken = TreeMap<Long, List<Int>>(lateHeartRates)
        lateHeartRates.clear()
        return taken
    }

    /** The row for a closed minute nothing was stored for, from late heart rate samples alone. */
    fun heartRateOnly(minuteStartMillis: Long, rates: List<Int>): WearLinkProtocol.SleepMinute {
        val bucket = Bucket(minuteStartMillis).also {
            it.charging = charging
            it.offBody = !worn
        }
        for (bpm in rates) bucket.heartRates += bpm.toDouble()
        return row(minuteStartMillis, bucket)
    }

    /** The off-body sensor's verdict. Sticky until the next one. */
    fun onWorn(epochMillis: Long, worn: Boolean) {
        this.worn = worn
        if (!worn) bucketAt(epochMillis)?.offBody = true
    }

    /** The charger plugged or unplugged. Sticky until the next one. */
    fun onCharging(epochMillis: Long, charging: Boolean) {
        this.charging = charging
        if (charging) bucketAt(epochMillis)?.charging = true
    }

    /** The screen became interactive: the wearer looked at the watch, so the minute is awake. */
    fun onScreenOn(epochMillis: Long) {
        bucketAt(epochMillis)?.screenOn = true
    }

    /** The heart rate sensor reported no contact, an unreliable reading or 0 bpm. */
    fun onHeartRateContact(epochMillis: Long, contact: Boolean) {
        if (!contact) bucketAt(epochMillis)?.heartRateNoContact = true
    }

    /** Closes and returns every minute old enough as of [nowEpochMillis], oldest first. */
    fun close(nowEpochMillis: Long): List<WearLinkProtocol.SleepMinute> {
        val closed = ArrayList<WearLinkProtocol.SleepMinute>()
        val iterator = buckets.entries.iterator()
        while (iterator.hasNext()) {
            val (start, bucket) = iterator.next()
            if (nowEpochMillis - start < config.closeLagMillis) break
            closed += row(start, bucket)
            closedThrough = maxOf(closedThrough, start)
            iterator.remove()
        }
        return closed
    }

    private fun row(start: Long, bucket: Bucket): WearLinkProtocol.SleepMinute {
        val accel = bucket.features.close()
        val n = accel.sampleCount
        val countsNorm = if (n > 0) bucket.counts * AccelerationMinuteFeatures.NOMINAL_SAMPLES.toDouble() / n else 0.0
        val movement = if (countsNorm < config.minCounts) 0.0 else minOf(config.maxMovement, config.movementPerCount * countsNorm)

        val diffs = ArrayList<Double>(accel.angles.size)
        var previous = lastAngle
        for (angle in accel.angles) {
            if (previous != null) diffs += abs(angle - previous)
            previous = angle
        }
        if (accel.angles.isNotEmpty()) lastAngle = accel.angles.last()

        var flags = 0
        if (bucket.charging) flags = flags or WearLinkProtocol.FLAG_CHARGING
        if (bucket.offBody) flags = flags or WearLinkProtocol.FLAG_OFF_BODY
        if (bucket.screenOn) flags = flags or WearLinkProtocol.FLAG_SCREEN_ON
        if (bucket.heartRateNoContact) flags = flags or WearLinkProtocol.FLAG_HR_NO_CONTACT
        val heartRateRecording = isHeartRateRecording()
        if (heartRateRecording) flags = flags or WearLinkProtocol.FLAG_HR_RECORDING
        if (n < config.sparseSamples) flags = flags or WearLinkProtocol.FLAG_SPARSE
        if (watchedMotion(start)) flags = flags or WearLinkProtocol.FLAG_MOTION_WATCHED

        val hn = bucket.heartRates.size
        val kind = when {
            bucket.charging || bucket.offBody || (heartRateRecording && hn == 0) -> WearLinkProtocol.MinuteKind.UNMEASURABLE
            bucket.screenOn -> WearLinkProtocol.MinuteKind.AWAKE
            else -> WearLinkProtocol.MinuteKind.RAW
        }
        val (heartMean, heartSd) = AccelerationMinuteFeatures.meanSd(bucket.heartRates)

        return WearLinkProtocol.SleepMinute(
            epochMillis = start,
            kind = kind,
            offsetSeconds = offsetSecondsAt(start),
            flags = flags,
            sampleCount = n,
            movement10 = round(movement * 10),
            bpm = if (hn > 0) round(heartMean) else null,
            heartRateSd10 = if (hn > 0) round(heartSd * 10) else null,
            heartRateSamples = hn,
            meanMilliG = IntArray(3) { round(accel.meanG[it] * 1000) },
            sdMilliG = IntArray(3) { round(accel.sdG[it] * 1000) },
            zAngleMin = accel.angles.minOrNull()?.let(::round),
            zAngleMax = accel.angles.maxOrNull()?.let(::round),
            zAngleDelta10 = if (diffs.isEmpty()) null else round(diffs.sum() / diffs.size * 10),
        )
    }

    private fun minuteOf(epochMillis: Long): Long = epochMillis - epochMillis % MINUTE_MILLIS

    /** The open minute holding [epochMillis], or null when that minute has closed. */
    private fun bucketAt(epochMillis: Long): Bucket? {
        val start = minuteOf(epochMillis)
        if (start <= closedThrough) return null
        return buckets.getOrPut(start) {
            Bucket(start).also {
                it.charging = charging
                it.offBody = !worn
            }
        }
    }

    companion object {
        const val MINUTE_MILLIS = 60_000L

        /** `floor(x + 0.5)`, the one rounding the specification allows. */
        fun round(value: Double): Int = Math.round(value).toInt()

        /**
         * [stored] with the late samples [rates] added to its heart rate: the
         * mean and the deviation pooled with the samples it already counted,
         * from the rounded numbers the row holds. A minute that was
         * unmeasurable only for want of a pulse gets the kind its flags give.
         */
        fun withHeartRate(stored: WearLinkProtocol.SleepMinute, rates: List<Int>): WearLinkProtocol.SleepMinute {
            if (rates.isEmpty()) return stored
            val before = stored.heartRateSamples.takeIf { stored.bpm != null } ?: 0
            val meanBefore = stored.bpm?.toDouble() ?: 0.0
            val sdBefore = (stored.heartRateSd10 ?: 0) / 10.0
            val n = before + rates.size
            val mean = (before * meanBefore + rates.sum()) / n
            val squares = before * (sdBefore * sdBefore + meanBefore * meanBefore) + rates.sumOf { it.toDouble() * it }
            val sd = sqrt(maxOf(0.0, squares / n - mean * mean))
            val kind = when {
                stored.hasFlag(WearLinkProtocol.FLAG_CHARGING) || stored.hasFlag(WearLinkProtocol.FLAG_OFF_BODY) -> WearLinkProtocol.MinuteKind.UNMEASURABLE
                stored.hasFlag(WearLinkProtocol.FLAG_SCREEN_ON) -> WearLinkProtocol.MinuteKind.AWAKE
                else -> WearLinkProtocol.MinuteKind.RAW
            }
            return stored.copy(kind = kind, bpm = round(mean), heartRateSd10 = round(sd * 10), heartRateSamples = n)
        }
    }
}
