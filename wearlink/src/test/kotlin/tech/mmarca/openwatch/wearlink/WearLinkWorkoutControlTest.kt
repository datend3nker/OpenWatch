package tech.mmarca.openwatch.wearlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tech.mmarca.openwatch.wearlink.WearLinkWorkoutControl.Command
import tech.mmarca.openwatch.wearlink.WearLinkWorkoutControl.Control
import tech.mmarca.openwatch.wearlink.WearLinkWorkoutControl.Phase
import tech.mmarca.openwatch.wearlink.WearLinkWorkoutControl.State

class WearLinkWorkoutControlTest {

    @Test
    fun `a control and a state round-trip, a session with spaces and umlauts included`() {
        val start = Control(Command.START, "Fahrt am Würmufer 1", exerciseType = 8)
        val line = WearLinkWorkoutControl.formatControl(start)
        assertEquals(4, line.split(' ').size)
        assertEquals(start, WearLinkWorkoutControl.parseControl(line))
        assertEquals(Control(Command.STOP, "s"), WearLinkWorkoutControl.parseControl(WearLinkWorkoutControl.formatControl(Control(Command.STOP, "s"))))

        val state = State("Fahrt am Würmufer 1", Phase.STOPPED, 1_700_000_000_000L, WearLinkWorkoutControl.REASON_STOPPED_ON_DEVICE)
        assertEquals(state, WearLinkWorkoutControl.parseState(WearLinkWorkoutControl.formatState(state)))
        assertEquals(State(null, Phase.NONE, 0L), WearLinkWorkoutControl.parseState(WearLinkWorkoutControl.formatState(State(null, Phase.NONE, 0L))))
    }

    @Test
    fun `a malformed control is refused`() {
        assertNull(WearLinkWorkoutControl.parseControl("WO_CTL start czE"))
        assertNull(WearLinkWorkoutControl.parseControl("WO_CTL jump czE"))
        assertNull(WearLinkWorkoutControl.parseControl("WO_CTL stop czE 8"))
        assertNull(WearLinkWorkoutControl.parseControl("WO_CTL stop !!"))
        assertNull(WearLinkWorkoutControl.parseControl(WearLinkWorkoutControl.formatControl(Control(Command.STOP, "x".repeat(129)))))
        assertNull(WearLinkWorkoutControl.parseState("WS - flying 0 -"))
    }

    @Test
    fun `a control reaches the watch's handler and its state comes back, an old watch answers unsupported`() {
        val token = WearLinkToken.generate()
        val loopback = Loopback().also { it.trust.tokens[it.phoneAddress] = token }
        val link = loopback.connect()
        val session = (WearLinkClient.hello(link.clientConnection, WearLinkProtocol.Hello(WearLinkProtocol.VERSION, setOf("hr"), token, "Pixel")) as WearLinkOutcome.Ok).value

        val answer = session.workoutControl(Control(Command.START, "ride-1", exerciseType = 8))

        assertEquals(WearLinkOutcome.Ok(State("ride-1", Phase.FAILED, 0L, WearLinkWorkoutControl.REASON_UNSUPPORTED)), answer)
        session.close()
    }

    @Test
    fun `a stopped workout's summary rides on its state, every part optional`() {
        val full = WearLinkWorkoutControl.State(
            "ride-1", WearLinkWorkoutControl.Phase.STOPPED, 9_000L, "stopped_on_device",
            WearLinkWorkoutControl.Summary(listOf(60, 600, 1200, 300, 0), listOf(98, 118, 137, 157, 177), 61.234, 24, 41),
        )
        assertEquals(full, WearLinkWorkoutControl.parseState(WearLinkWorkoutControl.formatState(full)))
        val bare = full.copy(summary = WearLinkWorkoutControl.Summary())
        assertEquals(bare, WearLinkWorkoutControl.parseState(WearLinkWorkoutControl.formatState(bare)))
        assertEquals(null, WearLinkWorkoutControl.parseState("WS cmlkZS0x stopped 9000 - 1,x - - - -"))
    }

    @Test
    fun `a watch carries the phase the phone knows, and needs it`() {
        val watch = WearLinkWorkoutControl.Control(WearLinkWorkoutControl.Command.WATCH, "ride-1", known = WearLinkWorkoutControl.Phase.PAUSED)
        assertEquals(watch, WearLinkWorkoutControl.parseControl(WearLinkWorkoutControl.formatControl(watch)))
        assertEquals(null, WearLinkWorkoutControl.parseControl("WO_CTL watch cmlkZS0x"))
    }
}
