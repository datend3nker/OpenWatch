package tech.mmarca.openwatch.wear.workout

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test
import tech.mmarca.openwatch.wearlink.WearLinkWorkoutControl
import tech.mmarca.openwatch.wearlink.WearLinkWorkoutControl.Command
import tech.mmarca.openwatch.wearlink.WearLinkWorkoutControl.Control
import tech.mmarca.openwatch.wearlink.WearLinkWorkoutControl.Phase
import tech.mmarca.openwatch.wearlink.WorkoutSport

class RemoteWorkoutTest {

    private var now = 1_000_000L
    private val workout = MutableStateFlow(WorkoutUiState())
    private val commands = mutableListOf<String>()

    private val store = object : RemoteWorkout.Store {
        var held: RemoteWorkout.Held? = null
        override fun read() = held
        override fun write(held: RemoteWorkout.Held?) { this.held = held }
    }

    private val controls = object : RemoteWorkout.Controls {
        override fun start(sport: WorkoutSport) { commands += "start ${sport.name}" }
        override fun pause() { commands += "pause" }
        override fun resume() { commands += "resume" }
        override fun stop() { commands += "stop" }
    }

    private val remote = RemoteWorkout(store, controls, workout) { now }

    private fun running(paused: Boolean = false) {
        workout.value = WorkoutUiState(
            sport = WorkoutSport.BIKING, running = true,
            snapshot = WorkoutSnapshot(60, paused, null, null, null, null, null, null, null, 120, 118, 130, waitingForFix = false),
        )
    }

    private fun send(command: Command, session: String = "ride-1") = remote.control(Control(command, session, exerciseType = 8))

    @Test
    fun `the phone starts, pauses, resumes and stops the workout of its session`() {
        assertEquals(Phase.RECORDING, send(Command.START).phase)
        running()
        now += 10_000
        assertEquals(Phase.PAUSED, send(Command.PAUSE).phase)
        running(paused = true)
        now += 10_000
        assertEquals(Phase.RECORDING, send(Command.RESUME).phase)
        running()
        now += 10_000
        val stopped = send(Command.STOP)

        assertEquals(Phase.STOPPED, stopped.phase)
        assertEquals(WearLinkWorkoutControl.REASON_STOPPED_BY_CLIENT, stopped.reason)
        assertEquals(listOf("start BIKING", "pause", "resume", "stop"), commands)
    }

    @Test
    fun `a stop on the watch is seen at the next ask, a command not yet taken is not mistaken for one`() {
        send(Command.START)
        // The service has not started it yet: still recording, not stopped.
        assertEquals(Phase.RECORDING, send(Command.STATE).phase)
        running()
        now += 60_000
        workout.value = WorkoutUiState()

        val seen = send(Command.STATE)

        assertEquals(Phase.STOPPED, seen.phase)
        assertEquals(WearLinkWorkoutControl.REASON_STOPPED_ON_DEVICE, seen.reason)
        assertEquals(now, seen.atMillis)
    }

    @Test
    fun `a start while another workout runs is busy, a repeated start answers the same, another session sees none`() {
        running()
        assertEquals(WearLinkWorkoutControl.REASON_BUSY, send(Command.START).reason)

        workout.value = WorkoutUiState()
        send(Command.START)
        assertEquals(Phase.RECORDING, send(Command.START).phase)
        assertEquals(listOf("start BIKING"), commands)
        assertEquals(Phase.NONE, send(Command.STATE, session = "other").phase)
        assertEquals(Phase.NONE, send(Command.STOP, session = "other").phase)
    }
}
