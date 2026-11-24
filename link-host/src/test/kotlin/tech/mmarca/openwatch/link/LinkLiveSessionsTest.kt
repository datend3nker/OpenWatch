package tech.mmarca.openwatch.link

import dagger.Lazy
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Test
import tech.mmarca.openlink.OpenLinkRecordingMode
import tech.mmarca.openlink.OpenLinkRecordingPhase
import tech.mmarca.openlink.OpenLinkRecordingRequest
import tech.mmarca.openlink.OpenLinkRecordingState
import tech.mmarca.openlink.host.OpenLinkHost

class LinkLiveSessionsTest {

    @Test
    fun `a recorded workout is refused as unsupported, to its owner, and nothing streams`() {
        val host = mockk<OpenLinkHost>(relaxed = true)
        val devices = mockk<LinkDevices>(relaxed = true)
        val sessions = LinkLiveSessions(devices, mockk(relaxed = true), mockk(relaxed = true), Lazy { host }, mockk(relaxed = true))
        val state = slot<OpenLinkRecordingState>()
        every { host.publishRecordingState(capture(state), any()) } returns Unit

        sessions.startRecording(
            OpenLinkRecordingRequest(sessionId = "ride-1", exerciseType = 8, startedAtMillis = 1_000L, deviceId = "pixel", mode = OpenLinkRecordingMode.RECORD),
            owner = "tech.mmarca.openvitals",
        )

        verify { host.publishRecordingState(any(), "tech.mmarca.openvitals") }
        assertEquals(OpenLinkRecordingPhase.FAILED, state.captured.phase)
        assertEquals(OpenLinkRecordingState.REASON_UNSUPPORTED, state.captured.reason)
        assertEquals(emptyMap<String, LinkSessionActivity>(), sessions.activity.value)
        verify(exactly = 0) { devices.devices }
    }
}
