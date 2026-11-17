package tech.mmarca.openwatch.wearlink

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tech.mmarca.healthcalculations.insights.HeartRateRecoveryQuality
import tech.mmarca.healthcalculations.insights.HeartRateZones
import tech.mmarca.healthcalculations.insights.estimateWorkoutEnergy
import tech.mmarca.healthcalculations.model.ExerciseSessionRecord
import tech.mmarca.healthcalculations.preferences.BodyProfile
import tech.mmarca.openwatch.wearlink.WorkoutMetrics.Beat

/**
 * What [WorkoutMetrics] decides itself: the active time, the whole kilometres, what it
 * hands health-calculations. The formulas are tested in health-calculations.
 */
class WorkoutMetricsTest {

    private val start = 1_794_000_000_000L
    private val day = LocalDate.of(2026, 11, 15)
    private fun minutes(n: Int) = n * 60_000L
    private fun steady(bpm: Int, count: Int, from: Long = start) = List(count) { Beat(from + it * 1000L, bpm) }
    private fun workout(
        minutes: Int,
        route: List<WearLinkWorkout.RoutePoint> = emptyList(),
        pauses: List<WearLinkWorkout.Pause> = emptyList(),
        exerciseType: Int = ExerciseSessionRecord.EXERCISE_TYPE_WALKING,
    ) = WearLinkWorkout(start, start + minutes(minutes), exerciseType, null, null, null, pauses, route)

    /** One fix every 30 s, due north, [stepE7] latitude units apart. */
    private fun northwards(fixes: Int, stepE7: Int, everyMillis: Long = 30_000L) =
        List(fixes) { WearLinkWorkout.RoutePoint(start + it * everyMillis, it * stepE7, 0, null, null) }

    @Test
    fun `the active time is the session less its pauses`() {
        val pause = WearLinkWorkout.Pause(start + minutes(2), start + minutes(4))
        val windows = WorkoutMetrics.activeWindows(workout(10, pauses = listOf(pause)))
        assertEquals(listOf(0L to 2L, 4L to 10L), windows.map { (it.start.toEpochMilli() - start) / 60_000 to (it.end.toEpochMilli() - start) / 60_000 })
    }

    @Test
    fun `a pause counts for nothing in the mean, the maximum or the zones`() {
        // 100 bpm, paused at 160 bpm, then 120 bpm; the one second from 29 to 30 s counts at 130.
        val pause = WearLinkWorkout.Pause(start + 30_000, start + 60_000)
        val beats = steady(100, 30) + steady(160, 30, start + 30_000) + steady(120, 30, start + 60_000)
        val summary = WorkoutMetrics.summarize(
            workout(2, pauses = listOf(pause)).copy(endMillis = start + 90_000), beats, HeartRateZones.Basis(200), BodyProfile(), day,
        )
        assertEquals(110, summary.averageBpm)
        assertEquals(120, summary.maxBpm)
        assertEquals(59L, summary.zoneSeconds.sum())
        assertNull(summary.totalKcal)
    }

    @Test
    fun `the energy is health-calculations' estimate over the active minutes`() {
        val pause = WearLinkWorkout.Pause(start + minutes(2), start + minutes(4))
        val profile = BodyProfile(weightKg = 70.0)
        assertEquals(
            estimateWorkoutEnergy(ExerciseSessionRecord.EXERCISE_TYPE_WALKING, 8.0, null, profile, day),
            WorkoutMetrics.energy(workout(10, pauses = listOf(pause)), emptyList(), profile, day),
        )
    }

    @Test
    fun `a straight kilometre is one split, its time without the pause`() {
        // 0.0009 degrees of latitude is about 100.08 m: ten steps, one whole kilometre.
        val route = northwards(11, 9_000)
        val split = WorkoutMetrics.splits(workout(6, route)).single()
        assertEquals(0, split.index)
        assertEquals(299L, split.activeSeconds)
        val pause = WearLinkWorkout.Pause(start + 160_000, start + 170_000)
        assertEquals(289L, WorkoutMetrics.splits(workout(6, route, listOf(pause))).single().activeSeconds)
    }

    @Test
    fun `the part kilometre at the end is no split, and a sport that does not travel has none`() {
        // Fifteen steps of about 100 m: one whole kilometre and half of the next.
        val route = northwards(16, 9_000)
        assertEquals(listOf(0), WorkoutMetrics.splits(workout(9, route)).map { it.index })
        assertEquals(emptyList<WorkoutMetrics.Split>(), WorkoutMetrics.splits(workout(9, route, exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_YOGA)))
    }

    @Test
    fun `vo2max of a steady run, and none for another sport`() {
        // 200 m/min at 150 bpm, rest 50, max 190: 0.2 x 200 / (100 / 140) + 3.5.
        val step = (200.0 / 111_195.0 * 1e7).toInt()
        val route = northwards(11, step, everyMillis = 60_000L)
        val beats = steady(150, 600)
        val run = workout(10, route, exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_RUNNING)
        assertEquals(59.5, WorkoutMetrics.summarize(run, beats, HeartRateZones.Basis(190, 50), BodyProfile(), day).vo2Max!!, 0.5)
        val volleyball = run.copy(exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_VOLLEYBALL)
        assertNull(WorkoutMetrics.summarize(volleyball, beats, HeartRateZones.Basis(190, 50), BodyProfile(), day).vo2Max)
    }

    @Test
    fun `recovery is the fall from the peak, judged on the effort`() {
        val stop = start + minutes(10)
        val beats = steady(160, 60, stop - 59_000) + steady(130, 11, stop + 55_000) + steady(110, 11, stop + 115_000)
        // Age 30: about 187 bpm predicted, so 160 is well below near-maximal.
        val recovery = WorkoutMetrics.recovery(beats, stop, BodyProfile(birthYear = 1996), restingBpm = 55, day = day)
        assertEquals(30, recovery.oneMinuteBpm)
        assertEquals(50, recovery.twoMinutesBpm)
        assertEquals(160, recovery.peakBpm)
        assertEquals(HeartRateRecoveryQuality.NOT_COMPARABLE, recovery.quality)
    }

    @Test
    fun `a heart rate that did not fall gives no recovery`() {
        val stop = start + minutes(10)
        val beats = steady(160, 60, stop - 59_000) + steady(165, 11, stop + 55_000)
        val recovery = WorkoutMetrics.recovery(beats, stop, BodyProfile(birthYear = 1996), restingBpm = 55, day = day)
        assertEquals(HeartRateRecoveryQuality.INVALID, recovery.quality)
        assertNull(recovery.oneMinuteBpm)
    }
}
