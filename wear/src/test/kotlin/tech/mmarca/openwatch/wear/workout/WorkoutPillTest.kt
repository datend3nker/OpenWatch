package tech.mmarca.openwatch.wear.workout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tech.mmarca.openwatch.wearlink.WorkoutSport

class WorkoutPillTest {

    private fun snapshot(paused: Boolean, seconds: Long = 90) = WorkoutSnapshot(
        activeSeconds = seconds, paused = paused, distanceMetres = null, speedMetresPerSecond = null,
        averageSpeedMetresPerSecond = null, steps = null, cadenceStepsPerMinute = null, ascentMetres = null,
        floors = null, heartRateBpm = null, averageHeartRateBpm = null, maxHeartRateBpm = null, waitingForFix = false,
    )

    private val sport = WorkoutSport.entries.first()

    @Test
    fun `no workout, no pill`() {
        assertNull(WorkoutUiState().pillKey())
    }

    @Test
    fun `a finished workout has no pill`() {
        val finished = WorkoutUiState(sport = sport, running = false, snapshot = snapshot(paused = false), finished = true)
        assertNull(finished.pillKey())
    }

    @Test
    fun `a running workout shows the sport and runs`() {
        val state = WorkoutUiState(sport = sport, running = true, snapshot = snapshot(paused = false))
        assertEquals(WorkoutPillKey(sport, paused = false), state.pillKey())
    }

    @Test
    fun `a workout that has no snapshot yet counts as running`() {
        val state = WorkoutUiState(sport = sport, running = true)
        assertEquals(WorkoutPillKey(sport, paused = false), state.pillKey())
    }

    @Test
    fun `a paused workout says so`() {
        val state = WorkoutUiState(sport = sport, running = true, snapshot = snapshot(paused = true))
        assertEquals(WorkoutPillKey(sport, paused = true), state.pillKey())
    }

    @Test
    fun `the stopwatch counts from the active time and stops while paused`() {
        val now = 1_000_000L
        assertEquals(now - 90_000, stopwatchBaseMillis(WorkoutPillKey(sport, paused = false), 90, now))
        assertNull(stopwatchBaseMillis(WorkoutPillKey(sport, paused = true), 90, now))
    }
}
