package tech.mmarca.openwatch.devices.wearos

import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.mmarca.openwatch.wearlink.WorkoutMetrics

class WearOsWorkoutMetricsTest {

    private val start = Instant.parse("2026-11-09T19:35:00Z")
    private val workout = WearOsWorkout(start, start.plusSeconds(3600), 78, null, null, null, emptyList(), emptyList())
    private val all = setOf(
        WearOsWorkoutMetrics.activeEnergyPermission, WearOsWorkoutMetrics.totalEnergyPermission, WearOsWorkoutMetrics.vo2Permission,
    )

    private fun summary(active: Double?, total: Double?, vo2: Double? = null) = WorkoutMetrics.Summary(
        averageBpm = 120, maxBpm = 160, zoneSeconds = listOf(600L, 1200L, 1800L, 0L, 0L), trimp = 85.4,
        activeKcal = active, totalKcal = total, splits = emptyList(), paceSecondsPerKm = null, vo2Max = vo2,
    )

    @Test
    fun `active energy wins, and the whole energy only stands in for it`() {
        val withActive = WearOsWorkoutMetrics.records(workout, summary(450.0, 520.0), ZoneOffset.UTC, all)
        assertEquals(450.0, (withActive.single() as ActiveCaloriesBurnedRecord).energy.inKilocalories, 0.01)
        val totalOnly = WearOsWorkoutMetrics.records(workout, summary(null, 520.0), ZoneOffset.UTC, all)
        assertTrue(totalOnly.single() is TotalCaloriesBurnedRecord)
    }

    @Test
    fun `a missing grant leaves only that record out`() {
        val records = WearOsWorkoutMetrics.records(workout, summary(450.0, 520.0, 48.0), ZoneOffset.UTC, setOf(WearOsWorkoutMetrics.vo2Permission))
        assertTrue(records.single() is Vo2MaxRecord)
    }

    @Test
    fun `the notes name the figures`() {
        assertEquals(
            "Heart rate avg 120, max 160 bpm. Zones (min) 10/20/30/0/0. TRIMP 85. Recovery 1 min 25 bpm",
            WearOsWorkoutMetrics.notes(summary(null, null), recoveryBpm = 25),
        )
    }
}
