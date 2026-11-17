package tech.mmarca.openwatch.wear.workout

import tech.mmarca.openwatch.wearlink.WearLinkProtocol
import tech.mmarca.openwatch.wearlink.WorkoutSport

/**
 * Sees a walk or a run in the step minutes the watch keeps anyway, and a ride in those and the heart rate
 * ([detectRide]), so no sensor has to run for it.
 *
 * A run of minutes with a step rate of at least [WALK_FLOOR] that has lasted [MIN_MINUTES] and is still on is a
 * walk; with a median of [RUN_CADENCE] or more it is a run. One slower minute inside the run (a crossing) does
 * not break it, if it still has [DIP_FLOOR] steps and a minute above the floor follows. The newest minute still
 * being written is left out. The numbers are judgement, not fitted to data: the proposal
 * (`docs/proposals/wear-auto-activity.md`) says they are to be tuned on the wearer's own days.
 */
internal object ActivityDetector {

    /** [heartRateBpm]: the median of the minutes' heart rates, for a ride. */
    data class Detection(val sport: WorkoutSport, val startMillis: Long, val minutes: Int, val steps: Int, val heartRateBpm: Int? = null)

    /** Steps a minute that make a minute of a walk: a brisk pace. */
    const val WALK_FLOOR = 90

    /** Steps a minute below which the run of minutes breaks. */
    const val DIP_FLOOR = 30

    /** Steps a minute, median over the run, from which it is a run and not a walk. */
    const val RUN_CADENCE = 140

    /** Minutes the activity must have lasted before the watch offers it; Samsung waits ten as well. */
    const val MIN_MINUTES = 10

    /** The run must reach this close to now: an activity that ended minutes ago is not offered. */
    const val MAX_IDLE_MINUTES = 2

    private const val MINUTE_MILLIS = 60_000L

    fun detect(rows: List<WearLinkProtocol.StepMinute>, nowMillis: Long): Detection? {
        val newest = nowMillis - Math.floorMod(nowMillis, MINUTE_MILLIS) - MINUTE_MILLIS
        val byMinute = rows.filter { it.epochMillis <= newest }.associate { it.epochMillis to it.steps }
        val last = (0..MAX_IDLE_MINUTES).map { newest - it * MINUTE_MILLIS }.firstOrNull { (byMinute[it] ?: 0) >= WALK_FLOOR } ?: return null

        var first = last
        var minute = last
        val qualifying = ArrayList<Int>()
        qualifying += byMinute.getValue(last)
        while (true) {
            val before = minute - MINUTE_MILLIS
            val steps = byMinute[before] ?: 0
            when {
                steps >= WALK_FLOOR -> {
                    qualifying += steps
                    first = before
                }
                // One slower minute is a crossing if a good minute lies before it.
                steps >= DIP_FLOOR && (byMinute[before - MINUTE_MILLIS] ?: 0) >= WALK_FLOOR -> Unit
                else -> break
            }
            minute = before
        }
        val minutes = ((last - first) / MINUTE_MILLIS).toInt() + 1
        if (minutes < MIN_MINUTES) return null
        val cadence = qualifying.sorted()[qualifying.size / 2]
        val steps = (first..last step MINUTE_MILLIS).sumOf { byMinute[it] ?: 0 }
        return Detection(if (cadence >= RUN_CADENCE) WorkoutSport.RUNNING else WorkoutSport.WALKING, first, minutes, steps)
    }

    /**
     * A ride, as the proposal has it: the heart rate up while the steps stay low. A minute of a ride has fewer
     * than [DIP_FLOOR] steps and a median heart rate of at least [moderateBpm], the lower bound of zone 3 (the
     * ACSM's moderate intensity, from the wearer's maximum and resting heart rate). A minute without a heart rate
     * sample is no minute of a ride, so power save, with a sample every ten minutes, sees none. One minute that
     * misses (a stop at a light) does not break it if a minute of the ride lies before it. Indoors and outdoors
     * look the same here; the offer is a ride, recorded without GPS.
     */
    fun detectRide(
        steps: List<WearLinkProtocol.StepMinute>,
        heartRate: List<WearLinkProtocol.HeartRateSample>,
        nowMillis: Long,
        moderateBpm: Int,
    ): Detection? {
        val newest = nowMillis - Math.floorMod(nowMillis, MINUTE_MILLIS) - MINUTE_MILLIS
        val stepsBy = steps.filter { it.epochMillis <= newest }.associate { it.epochMillis to it.steps }
        val bpmBy = heartRate
            .groupBy { it.epochMillis - Math.floorMod(it.epochMillis, MINUTE_MILLIS) }
            .filterKeys { it <= newest }
            .mapValues { (_, samples) -> samples.map { it.bpm }.sorted()[samples.size / 2] }
        fun riding(minute: Long) = (bpmBy[minute] ?: 0) >= moderateBpm && (stepsBy[minute] ?: 0) < DIP_FLOOR

        val last = (0..MAX_IDLE_MINUTES).map { newest - it * MINUTE_MILLIS }.firstOrNull(::riding) ?: return null
        var first = last
        var minute = last
        while (true) {
            val before = minute - MINUTE_MILLIS
            when {
                riding(before) -> first = before
                riding(before - MINUTE_MILLIS) -> Unit
                else -> break
            }
            minute = before
        }
        val minutes = ((last - first) / MINUTE_MILLIS).toInt() + 1
        if (minutes < MIN_MINUTES) return null
        val span = first..last step MINUTE_MILLIS
        val rates = span.filter(::riding).map { bpmBy.getValue(it) }.sorted()
        return Detection(WorkoutSport.BIKING, first, minutes, span.sumOf { stepsBy[it] ?: 0 }, rates[rates.size / 2])
    }
}
