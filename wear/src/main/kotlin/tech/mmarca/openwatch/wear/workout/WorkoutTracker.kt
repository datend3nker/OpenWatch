package tech.mmarca.openwatch.wear.workout

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import tech.mmarca.openwatch.wearlink.WorkoutSport
import tech.mmarca.openwatch.wearlink.AscentCredit
import tech.mmarca.openwatch.wearlink.WearLinkProtocol
import tech.mmarca.openwatch.wearlink.WearLinkWorkout
import tech.mmarca.openwatch.wearlink.WearLinkWorkoutLines

/** One position from the GPS. Speed, altitude and accuracy are null when the fix has none. */
data class WorkoutFix(
    val epochMillis: Long,
    val latitude: Double,
    val longitude: Double,
    val altitudeMetres: Double? = null,
    val accuracyMetres: Double? = null,
    val speedMetresPerSecond: Double? = null,
)

/** What a screen shows of a running workout, as of one moment. */
data class WorkoutSnapshot(
    /** Time without pauses. */
    val activeSeconds: Long,
    val paused: Boolean,
    val distanceMetres: Int?,
    /** Smoothed speed now; null without a recent fix. */
    val speedMetresPerSecond: Double?,
    /** Distance over active time since the start. */
    val averageSpeedMetresPerSecond: Double?,
    val steps: Int?,
    val cadenceStepsPerMinute: Int?,
    val ascentMetres: Int?,
    val floors: Int?,
    val heartRateBpm: Int?,
    val averageHeartRateBpm: Int?,
    val maxHeartRateBpm: Int?,
    /** True for a GPS sport that has no usable fix yet. */
    val waitingForFix: Boolean,
)

/**
 * The arithmetic of one workout: pauses, distance and route from GPS fixes,
 * steps, height climbed and heart rate. Pure, so it is testable off the
 * watch; [WorkoutRecorder] feeds it from the sensors.
 *
 * Distance adds up only between fixes that are accurate enough, that imply
 * a speed the sport can reach, and that moved at least as far as the fix's
 * own error, so a standing wearer does not gain metres. A fix after a pause
 * only sets a new starting point. Height climbed comes from the barometer's
 * minutes ([AscentMinuteCounter]); on foot it counts only with steps around
 * it, like all-day ascent ([AscentCredit]).
 */
class WorkoutTracker(
    val sport: WorkoutSport,
    val startMillis: Long,
    /** False when the wearer declined location: the workout then has no route or distance. */
    val useGps: Boolean = sport.gps,
) {
    private val pauses = ArrayList<WearLinkWorkout.Pause>()
    private var pausedSince: Long? = null

    private var anchor: WorkoutFix? = null
    private var lastKept: WorkoutFix? = null
    private var distance = 0.0
    private var hadFix = false
    private var speed: Double? = null
    private var speedAt = 0L
    private val points = ArrayList<WearLinkWorkout.RoutePoint>()

    private var stepBaseline: Long? = null
    private var stepBaselineStale = false
    private var lastStepTotal = 0L
    private var steps = 0
    private val stepsByMinute = HashMap<Long, Int>()
    private val ascentByMinute = HashMap<Long, Int>()

    private var hrSum = 0L
    private var hrCount = 0
    private var hrMax = 0
    private var hrNow: Int? = null
    private var hrNowAt = 0L

    val isPaused: Boolean get() = pausedSince != null

    val route: List<WearLinkWorkout.RoutePoint> get() = points

    fun pauseList(): List<WearLinkWorkout.Pause> = pauses.toList()

    fun pause(now: Long) {
        if (pausedSince == null) pausedSince = now.coerceAtLeast(startMillis)
    }

    fun resume(now: Long) {
        val since = pausedSince ?: return
        pausedSince = null
        // The first fix after a pause only sets the new starting point.
        anchor = null
        if (now > since && pauses.size < WearLinkWorkoutLines.MAX_PAUSES) pauses += WearLinkWorkout.Pause(since, now)
    }

    /** Milliseconds between the start and [now] without the pauses. */
    fun activeMillis(now: Long): Long {
        val closed = pauses.sumOf { it.endMillis - it.startMillis }
        val open = pausedSince?.let { (now - it).coerceAtLeast(0) } ?: 0L
        return (now - startMillis - closed - open).coerceAtLeast(0)
    }

    /**
     * Takes one fix. Returns the route point to store, or null when the fix
     * is dropped or only counted. A paused workout drops every fix.
     */
    fun onFix(fix: WorkoutFix): WearLinkWorkout.RoutePoint? {
        if (!useGps || isPaused || fix.epochMillis < startMillis) return null
        val accuracy = fix.accuracyMetres
        if (accuracy != null && accuracy > MAX_ACCURACY_METRES) return null
        hadFix = true
        val last = anchor
        if (last == null) {
            anchor = fix
            return keep(fix)
        }
        val seconds = (fix.epochMillis - last.epochMillis) / 1000.0
        if (seconds <= 0.0) return null
        val metres = distanceBetween(last, fix)
        val implied = metres / seconds
        if (implied > maxSpeed()) {
            // A long gap with a far jump is a lost signal, not a sprint: start over from here.
            if (seconds > RESET_AFTER_SECONDS) anchor = fix
            return null
        }
        val jitter = maxOf(MIN_STEP_METRES, (accuracy ?: 0.0) / 2)
        if (metres < jitter) return null
        distance += metres
        anchor = fix
        noteSpeed(fix, implied)
        return keep(fix)
    }

    private fun noteSpeed(fix: WorkoutFix, derived: Double) {
        val measured = fix.speedMetresPerSecond?.takeIf { it >= 0.0 && it <= maxSpeed() } ?: derived
        speed = speed?.let { it * 0.6 + measured * 0.4 } ?: measured
        speedAt = fix.epochMillis
    }

    private fun keep(fix: WorkoutFix): WearLinkWorkout.RoutePoint? {
        if (points.size >= WearLinkWorkoutLines.MAX_POINTS) return null
        val before = lastKept
        if (before != null) {
            val gap = fix.epochMillis - before.epochMillis
            if (gap < MIN_POINT_GAP_MILLIS) return null
            if (gap < MAX_POINT_GAP_MILLIS && distanceBetween(before, fix) < MIN_POINT_DISTANCE_METRES) return null
        }
        lastKept = fix
        return WearLinkWorkout.RoutePoint(
            epochMillis = fix.epochMillis,
            latitudeE7 = (fix.latitude * 1e7).roundToInt(),
            longitudeE7 = (fix.longitude * 1e7).roundToInt(),
            altitudeDecimetres = fix.altitudeMetres?.let { (it * 10).roundToInt() },
            accuracyDecimetres = fix.accuracyMetres?.let { (it * 10).roundToInt().coerceAtLeast(0) },
        ).also { points += it }
    }

    /** Adopts a point that was stored before a restart, so the route stays whole. */
    fun restorePoint(point: WearLinkWorkout.RoutePoint) {
        if (points.size < WearLinkWorkoutLines.MAX_POINTS) points += point
    }

    /**
     * The step counter's running total at [epochMillis]. The first reading is the baseline.
     * A reading stamped before the start is the sensor's cached value from before the workout:
     * it can be a baseline, never a gain. The first fresh reading after it replaces it, because
     * the steps in between are not known to belong to the workout. While paused the baseline
     * moves with the counter, so steps taken in a pause are not credited on resume.
     */
    fun onStepTotal(epochMillis: Long, total: Long) {
        val base = stepBaseline
        val stale = epochMillis < startMillis
        val last = lastStepTotal
        lastStepTotal = total
        if (base == null || (stepBaselineStale && !stale)) {
            stepBaseline = total
            stepBaselineStale = stale
            return
        }
        if (stale) {
            if (stepBaselineStale) stepBaseline = total
            return
        }
        if (isPaused) {
            if (total >= last) stepBaseline = base + (total - last)
            return
        }
        if (total < base) return
        val now = (total - base).toInt().coerceAtMost(WearLinkWorkoutLines.MAX_STEPS)
        val delta = now - steps
        if (delta <= 0) return
        steps = now
        stepsByMinute.merge(minuteOf(epochMillis), delta, Int::plus)
    }

    /** A minute of ascent from [AscentMinuteCounter]: the row's value replaces the minute's earlier one. */
    fun onAscentMinute(row: WearLinkProtocol.AscentMinute) {
        if (isPaused || row.epochMillis + MINUTE_MILLIS <= startMillis) return
        ascentByMinute[row.epochMillis] = row.decimetres
    }

    fun onHeartRate(epochMillis: Long, bpm: Int) {
        hrNow = bpm
        hrNowAt = epochMillis
        if (isPaused) return
        hrSum += bpm
        hrCount++
        if (bpm > hrMax) hrMax = bpm
    }

    /** Height climbed that counts, in decimetres. On foot a minute counts with the steps around it. */
    fun ascentDecimetres(): Int = ascentByMinute.entries.sumOf { (minute, decimetres) ->
        if (sport.steps) {
            val walked = (stepsByMinute[minute - MINUTE_MILLIS] ?: 0) + (stepsByMinute[minute] ?: 0)
            (AscentCredit.creditedMetres(decimetres / 10.0, walked) * 10).roundToInt()
        } else {
            decimetres
        }
    }

    fun snapshot(now: Long): WorkoutSnapshot {
        val activeMillis = activeMillis(now)
        val activeSeconds = activeMillis / 1000
        val gps = useGps
        val metres = distance.roundToInt()
        val recentSpeed = speed?.takeIf { !isPaused && now - speedAt <= SPEED_STALE_MILLIS }
        val ascent = ascentDecimetres()
        return WorkoutSnapshot(
            activeSeconds = activeSeconds,
            paused = isPaused,
            distanceMetres = metres.takeIf { gps && hadFix },
            speedMetresPerSecond = recentSpeed.takeIf { gps },
            averageSpeedMetresPerSecond = (distance / activeSeconds).takeIf { gps && hadFix && activeSeconds > 0 },
            steps = steps.takeIf { sport.steps && stepBaseline != null },
            cadenceStepsPerMinute = if (sport.steps && activeSeconds >= MIN_CADENCE_SECONDS && steps > 0) {
                (steps * 60.0 / activeSeconds).roundToInt()
            } else {
                null
            },
            ascentMetres = (ascent / 10.0).roundToInt().takeIf { sport.elevation && ascentByMinute.isNotEmpty() },
            floors = (ascent / 10.0 / AscentCredit.METRES_PER_FLOOR).roundToInt().takeIf { sport.floors && ascentByMinute.isNotEmpty() },
            heartRateBpm = hrNow?.takeIf { now - hrNowAt <= HEART_RATE_STALE_MILLIS },
            averageHeartRateBpm = if (hrCount > 0) (hrSum.toDouble() / hrCount).roundToInt() else null,
            maxHeartRateBpm = hrMax.takeIf { hrCount > 0 },
            waitingForFix = gps && !hadFix,
        )
    }

    /**
     * The finished workout as the wire carries it, ending at [requestedEndMillis]. An open pause ends there.
     * The phone refuses a workout longer than [WearLinkWorkoutLines.MAX_DURATION_MILLIS], so the end is
     * cut to that, and the pauses and route points after it go.
     */
    fun finish(requestedEndMillis: Long): WearLinkWorkout {
        val endMillis = limitedEnd(startMillis, requestedEndMillis)
        resume(endMillis)
        val gps = useGps && hadFix
        return WearLinkWorkout(
            startMillis = startMillis,
            endMillis = endMillis,
            exerciseType = sport.exerciseType,
            steps = steps.takeIf { sport.steps && stepBaseline != null },
            distanceMetres = distance.roundToInt().coerceAtMost(WearLinkWorkoutLines.MAX_DISTANCE_METRES).takeIf { gps },
            ascentDecimetres = ascentDecimetres().coerceAtMost(WearLinkWorkoutLines.MAX_ASCENT_DECIMETRES)
                .takeIf { sport.elevation && ascentByMinute.isNotEmpty() },
            pauses = clipPauses(pauses, endMillis),
            route = points.filter { it.epochMillis <= endMillis },
        )
    }

    /** The state a restart needs; see [WorkoutCheckpoint]. */
    fun checkpoint(now: Long): WorkoutCheckpoint = WorkoutCheckpoint(
        sportId = sport.id,
        startMillis = startMillis,
        savedMillis = now,
        useGps = useGps,
        distanceMetres = distance.roundToInt().takeIf { hadFix },
        steps = steps.takeIf { stepBaseline != null },
        ascentDecimetres = ascentDecimetres().takeIf { ascentByMinute.isNotEmpty() },
        pauses = pauses.toList(),
        pausedSince = pausedSince,
    )

    private fun maxSpeed(): Double = if (sport == WorkoutSport.BIKING) MAX_SPEED_BIKE else MAX_SPEED_FOOT

    private fun minuteOf(epochMillis: Long): Long = epochMillis - Math.floorMod(epochMillis, MINUTE_MILLIS)

    companion object {
        /** A fix less sure than this is not used. */
        const val MAX_ACCURACY_METRES = 30.0

        /** Standing still moves the fix by a few metres; less than this is not a step. */
        const val MIN_STEP_METRES = 3.0

        /** Faster than a sprinter's 12 m/s on foot is a glitch. */
        const val MAX_SPEED_FOOT = 12.0

        /** Faster than 25 m/s (90 km/h) on a bike is a glitch. */
        const val MAX_SPEED_BIKE = 25.0

        private const val RESET_AFTER_SECONDS = 60.0
        private const val MIN_POINT_GAP_MILLIS = 5_000L
        private const val MAX_POINT_GAP_MILLIS = 30_000L
        private const val MIN_POINT_DISTANCE_METRES = 3.0
        private const val SPEED_STALE_MILLIS = 10_000L
        private const val HEART_RATE_STALE_MILLIS = 30_000L
        private const val MIN_CADENCE_SECONDS = 20
        private const val MINUTE_MILLIS = 60_000L
        private const val EARTH_RADIUS_METRES = 6_371_000.0

        /** [endMillis], but no later than the longest workout the link carries. */
        fun limitedEnd(startMillis: Long, endMillis: Long): Long =
            minOf(endMillis, startMillis + WearLinkWorkoutLines.MAX_DURATION_MILLIS)

        /** The pauses that begin before [endMillis], the last one cut to end there. */
        fun clipPauses(pauses: List<WearLinkWorkout.Pause>, endMillis: Long): List<WearLinkWorkout.Pause> =
            pauses.filter { it.startMillis < endMillis }.map { it.copy(endMillis = minOf(it.endMillis, endMillis)) }

        /** Great-circle distance in metres. */
        fun distanceBetween(a: WorkoutFix, b: WorkoutFix): Double {
            val lat1 = Math.toRadians(a.latitude)
            val lat2 = Math.toRadians(b.latitude)
            val dLat = lat2 - lat1
            val dLon = Math.toRadians(b.longitude - a.longitude)
            val h = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
            return 2 * EARTH_RADIUS_METRES * atan2(sqrt(h), sqrt(1 - h))
        }
    }
}
