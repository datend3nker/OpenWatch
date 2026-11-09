package tech.mmarca.openwatch.wearlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import tech.mmarca.openwatch.wearlink.WorkoutMetrics.Beat
import tech.mmarca.openwatch.wearlink.WorkoutMetrics.Body

class WorkoutMetricsTest {

    private val start = 1_794_000_000_000L
    private fun minutes(n: Int) = n * 60_000L
    private fun steady(bpm: Int, count: Int, from: Long = start) = List(count) { Beat(from + it * 1000L, bpm) }
    private fun workout(minutes: Int, route: List<WearLinkWorkout.RoutePoint> = emptyList(), pauses: List<WearLinkWorkout.Pause> = emptyList()) =
        WearLinkWorkout(start, start + minutes(minutes), 79, null, null, null, pauses, route)

    @Test
    fun `keytel for a man at 150 bpm`() {
        val spans = steady(150, 60).map { it to 1000L }
        // (-55.0969 + 0.6309*150 + 0.1988*70 + 0.2017*30) / 4.184 = 14.22 kcal/min
        assertEquals(14.22, WorkoutMetrics.keytelKcal(spans, Body(70.0, 175.0, 30, false))!!, 0.01)
    }

    @Test
    fun `mifflin for a woman`() {
        // 10*60 + 6.25*165 - 5*30 - 161 = 1320.25 kcal/day
        assertEquals(1320.25 / 1440, WorkoutMetrics.mifflinKcalPerMinute(Body(60.0, 165.0, 30, true))!!, 1e-9)
    }

    @Test
    fun `active energy is keytel less rest, and needs height`() {
        val spans = steady(150, 60).map { it to 1000L }
        val body = Body(70.0, 175.0, 30, false)
        val rest = WorkoutMetrics.mifflinKcalPerMinute(body)!!
        assertEquals(14.222 - rest, WorkoutMetrics.activeKcal(spans, body)!!, 0.01)
        assertNull(WorkoutMetrics.activeKcal(spans, Body(70.0, null, 30, false)))
    }

    @Test
    fun `trimp of ten minutes at half the reserve, a man`() {
        val spans = listOf(Beat(start, 120) to minutes(10))
        // 10 * 0.5 * 0.64 * e^(0.96) = 8.35
        assertEquals(8.35, WorkoutMetrics.banisterTrimp(spans, 50, 190, false)!!, 0.01)
    }

    @Test
    fun `summary counts only active readings, gaps capped`() {
        val pause = WearLinkWorkout.Pause(start + 30_000, start + 60_000)
        val beats = steady(100, 30) + steady(160, 30, start + 30_000) + steady(120, 30, start + 60_000)
        val summary = WorkoutMetrics.summarize(workout(2, pauses = listOf(pause)).copy(endMillis = start + 90_000), beats, HeartRateZones.Basis(190, 50), Body(), null)
        assertEquals(110, summary.averageBpm)
        assertEquals(120, summary.maxBpm)
        assertNull(summary.totalKcal)
    }

    @Test
    fun `a straight kilometre is one split`() {
        // 0.009 degrees of latitude is about 1000.8 m.
        val route = List(11) { WearLinkWorkout.RoutePoint(start + it * 30_000L, it * 9_000, 0, null, null) }
        val splits = WorkoutMetrics.splits(workout(6, route))
        assertEquals(1, splits.size)
        assertEquals(299L, splits[0].activeSeconds)
    }

    @Test
    fun `vo2max from a steady run`() {
        // 200 m/min for 10 minutes at 150 bpm, rest 50, max 190: reserve 0.714,
        // VO2 = 3.5 + 0.2 * 200 = 43.5, VO2max = 40 / 0.714 + 3.5 = 59.5
        val metresPerFix = 200.0
        val step = (metresPerFix / 111_195.0 * 1e7).toInt()
        val route = List(11) { WearLinkWorkout.RoutePoint(start + minutes(it), it * step, 0, null, null) }
        val beats = steady(150, 600)
        val vo2 = WorkoutMetrics.vo2Max(workout(10, route), beats, HeartRateZones.Basis(190, 50), WorkoutSport.RUNNING)
        assertNotNull(vo2)
        assertEquals(59.5, vo2!!, 0.5)
        assertNull(WorkoutMetrics.vo2Max(workout(10, route), beats, HeartRateZones.Basis(190, 50), WorkoutSport.VOLLEYBALL))
    }

    @Test
    fun `recovery is the fall after one and two minutes`() {
        val stop = start + minutes(10)
        val beats = steady(160, 10, stop - 9_000) + steady(130, 11, stop + 55_000) + steady(110, 11, stop + 115_000)
        assertEquals(30 to 50, WorkoutMetrics.recovery(beats, stop))
    }

    @Test
    fun `acsm zones on the reserve`() {
        val basis = HeartRateZones.Basis(190, 50)
        assertEquals(1, HeartRateZones.zoneOf(92.0, basis))
        assertEquals(4, HeartRateZones.zoneOf(176.0, basis))
    }
}
