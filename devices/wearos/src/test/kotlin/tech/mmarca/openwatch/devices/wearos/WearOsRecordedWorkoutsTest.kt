package tech.mmarca.openwatch.devices.wearos

import io.mockk.coEvery
import io.mockk.mockk
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.mmarca.openwatch.core.security.testSeal
import tech.mmarca.openwatch.devices.FakeSharedPreferences
import tech.mmarca.openwatch.devices.core.RecordedWorkoutCommand
import tech.mmarca.openwatch.devices.core.RecordedWorkoutPhase
import tech.mmarca.openwatch.domain.model.BleDeviceKind
import tech.mmarca.openwatch.domain.model.BleSensorDevice
import tech.mmarca.openwatch.domain.model.DeviceIntegration
import tech.mmarca.openwatch.wearlink.WearLinkWorkoutControl

class WearOsRecordedWorkoutsTest {

    private val watch = BleSensorDevice(
        id = "w", displayName = "Galaxy Watch8", address = "7F:12:34:56:78:9A", bluetoothName = "Galaxy Watch8", capabilities = emptySet(),
        enabled = true, wheelCircumferenceMm = null, batteryPercent = null, batteryUpdatedAt = null,
        addedAt = Instant.EPOCH, kind = BleDeviceKind.WATCH, integration = DeviceIntegration.WEAROS, lastSyncedAt = null,
    )
    private val linkStore = WearOsLinkStore(FakeSharedPreferences(), testSeal())
    private val manager = mockk<WearOsCompanionManager>()
    private val port = WearOsRecordedWorkouts(manager, linkStore)

    @Test
    fun `a route is offered only once the watch's hello announced its GPS receiver`() {
        assertTrue(port.canRecord(watch))
        assertFalse("not reached yet", port.recordsRoute(watch))
        linkStore.setCapabilities(watch.address, setOf(WearLinkWorkoutControl.CAPABILITY))
        assertFalse(port.recordsRoute(watch))
        linkStore.setCapabilities(watch.address, setOf(WearLinkWorkoutControl.CAPABILITY, WearLinkWorkoutControl.CAPABILITY_GPS))
        assertTrue(port.recordsRoute(watch))
    }

    @Test
    fun `the watch's answer comes back with its summary, and a link that fails is no answer`() = runTest {
        val summary = WearLinkWorkoutControl.Summary(zoneSeconds = listOf(10, 20, 0, 0, 0), trimp = 3.5)
        coEvery { manager.controlWorkout(any(), any(), any(), any()) } returns
            WearLinkWorkoutControl.State("s", WearLinkWorkoutControl.Phase.STOPPED, 5L, "stopped_by_client", summary)
        val state = port.control(watch, RecordedWorkoutCommand.STOP, "s")!!
        assertEquals(RecordedWorkoutPhase.STOPPED, state.phase)
        assertEquals(listOf(10, 20, 0, 0, 0), state.summary?.zoneSeconds)

        coEvery { manager.controlWorkout(any(), any(), any(), any()) } throws WearOsLinkFailure.NoAnswer()
        assertNull(port.control(watch, RecordedWorkoutCommand.STATE, "s", background = true))
    }
}
