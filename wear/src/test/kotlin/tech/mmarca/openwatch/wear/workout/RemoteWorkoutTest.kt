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

    @Test
    fun `a stopped workout carries the summary of the workout it began, read at each ask`() {
        val summaries = mutableMapOf<Long, WearLinkWorkoutControl.Summary>()
        val remote = RemoteWorkout(store, controls, workout, summaryOf = { summaries[it] }) { now }
        remote.control(Control(Command.START, "ride-1", exerciseType = 8))
        workout.value = WorkoutUiState(
            sport = WorkoutSport.BIKING, running = true, startMillis = 777L,
            snapshot = WorkoutSnapshot(60, false, null, null, null, null, null, null, null, 120, 118, 130, waitingForFix = false),
        )
        now += 10_000
        assertEquals("no summary while it runs", null, remote.control(Control(Command.STATE, "ride-1")).summary)

        summaries[777L] = WearLinkWorkoutControl.Summary(zoneSeconds = listOf(60, 0, 0, 0, 0), trimp = 4.0)
        val stopped = remote.control(Control(Command.STOP, "ride-1"))
        assertEquals(listOf(60, 0, 0, 0, 0), stopped.summary?.zoneSeconds)

        // The recovery comes two minutes later; the next ask has it.
        summaries[777L] = summaries.getValue(777L).copy(recovery1MinBpm = 22)
        assertEquals(22, remote.control(Control(Command.STATE, "ride-1")).summary?.recovery1MinBpm)
    }

    @Test
    fun `a watch answers as soon as the wrist pauses the workout, or after its five seconds`() {
        var looks = 0
        val remote = RemoteWorkout(store, controls, workout, pause = { millis ->
            now += millis
            // The wearer presses pause on the watch two seconds in.
            if (++looks == 8) running(paused = true)
        }) { now }
        remote.control(Control(Command.START, "ride-1", exerciseType = 8))
        running()
        now += 10_000

        val paused = remote.control(Control(Command.WATCH, "ride-1", known = Phase.RECORDING))
        assertEquals(Phase.PAUSED, paused.phase)
        assertEquals(8, looks)

        val quiet = remote.control(Control(Command.WATCH, "ride-1", known = Phase.PAUSED))
        assertEquals("nothing changed: the current state after five seconds", Phase.PAUSED, quiet.phase)
        assertEquals(8 + 20, looks)
        assertEquals(Phase.NONE, remote.control(Control(Command.WATCH, "other", known = Phase.RECORDING)).phase)
    }
}
