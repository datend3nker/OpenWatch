package tech.mmarca.openwatch.link

import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.mmarca.openlink.OpenLinkRecordingMode
import tech.mmarca.openlink.OpenLinkRecordingPhase
import tech.mmarca.openlink.OpenLinkRecordingRequest
import tech.mmarca.openlink.OpenLinkRecordingState
import tech.mmarca.openwatch.devices.wearos.WearOsLinkFailure
import tech.mmarca.openwatch.domain.model.BleDeviceKind
import tech.mmarca.openwatch.domain.model.BleSensorDevice
import tech.mmarca.openwatch.domain.model.DeviceIntegration
import tech.mmarca.openwatch.wearlink.WearLinkWorkoutControl
import tech.mmarca.openwatch.wearlink.WearLinkWorkoutControl.Command
import tech.mmarca.openwatch.wearlink.WearLinkWorkoutControl.Phase

@OptIn(ExperimentalCoroutinesApi::class)
class LinkRecordedWorkoutsTest {

    private val owner = "tech.mmarca.openvitals"

    private fun device(id: String, integration: DeviceIntegration) = BleSensorDevice(
        id = id, displayName = id, address = "AA:BB:CC:DD:EE:01", bluetoothName = id, capabilities = emptySet(),
        enabled = true, wheelCircumferenceMm = null, batteryPercent = null, batteryUpdatedAt = null,
        addedAt = Instant.EPOCH, kind = BleDeviceKind.WATCH, integration = integration, lastSyncedAt = null,
    )

    private class FakeWatch(val devices: List<BleSensorDevice>) : LinkRecordedWorkouts.Watch {
        val commands = mutableListOf<Command>()
        var phase = Phase.NONE
        var reason: String? = null
        var reachable = true

        override fun device(deviceId: String) = devices.firstOrNull { it.id == deviceId }

        override suspend fun control(device: BleSensorDevice, control: WearLinkWorkoutControl.Control): WearLinkWorkoutControl.State {
            if (!reachable) throw WearOsLinkFailure.NoAnswer()
            commands += control.command
            when (control.command) {
                Command.START -> phase = Phase.RECORDING
                Command.PAUSE -> phase = Phase.PAUSED
                Command.RESUME -> phase = Phase.RECORDING
                Command.STOP -> { phase = Phase.STOPPED; reason = WearLinkWorkoutControl.REASON_STOPPED_BY_CLIENT }
                Command.STATE -> Unit
            }
            return WearLinkWorkoutControl.State(control.sessionId, phase, 0L, reason)
        }
    }

    private val published = mutableListOf<Pair<OpenLinkRecordingState, String>>()
    private val synced = mutableListOf<String>()
    private val watch = FakeWatch(listOf(device("pixel", DeviceIntegration.WEAROS), device("venu", DeviceIntegration.GARMIN)))

    private fun TestScope.recorded() =
        LinkRecordedWorkouts(watch, { state, owner -> published += state to owner }, { synced += it }, backgroundScope, clock = { currentTime }, pollMillis = 20_000L)

    private fun request(deviceId: String = "pixel") =
        OpenLinkRecordingRequest(sessionId = "ride-1", exerciseType = 8, startedAtMillis = 0L, deviceId = deviceId, mode = OpenLinkRecordingMode.RECORD)

    private val phases get() = published.map { it.first.phase to it.first.reason }

    @Test
    fun `a Garmin cannot record one and an unknown device is none`() = runTest {
        val recorded = recorded()
        recorded.start(request("venu"), owner)
        recorded.start(request("gone"), owner)

        assertEquals(
            listOf(OpenLinkRecordingPhase.FAILED to OpenLinkRecordingState.REASON_UNSUPPORTED, OpenLinkRecordingPhase.FAILED to OpenLinkRecordingState.REASON_NO_DEVICE),
            phases,
        )
        assertTrue(watch.commands.isEmpty())
    }

    @Test
    fun `the owner starts, pauses and stops it, each answered, and the watch is synced after`() = runTest {
        val recorded = recorded()
        recorded.start(request(), owner)
        runCurrent()
        recorded.pause("ride-1")
        runCurrent()
        recorded.stop("ride-1")
        runCurrent()

        assertEquals(
            listOf(
                OpenLinkRecordingPhase.RECORDING to null,
                OpenLinkRecordingPhase.PAUSED to null,
                OpenLinkRecordingPhase.STOPPED to WearLinkWorkoutControl.REASON_STOPPED_BY_CLIENT,
            ),
            phases,
        )
        assertTrue(published.all { it.second == owner })
        assertTrue(recorded.activity.value.isEmpty())
        advanceTimeBy(6_000)
        assertEquals(listOf("pixel"), synced)
    }

    @Test
    fun `a stop on the wrist reaches the owner at the next ask`() = runTest {
        val recorded = recorded()
        recorded.start(request(), owner)
        runCurrent()
        assertTrue(recorded.activity.value.getValue("ride-1").recorded)
        watch.phase = Phase.STOPPED
        watch.reason = WearLinkWorkoutControl.REASON_STOPPED_ON_DEVICE

        advanceTimeBy(20_001)

        assertEquals(OpenLinkRecordingPhase.STOPPED to WearLinkWorkoutControl.REASON_STOPPED_ON_DEVICE, phases.last())
        assertTrue(!recorded.holds("ride-1"))
    }

    @Test
    fun `a watch out of reach fails a start, and a poll waits for the next ask`() = runTest {
        val recorded = recorded()
        watch.reachable = false
        recorded.start(request(), owner)
        runCurrent()
        assertEquals(listOf(OpenLinkRecordingPhase.FAILED to OpenLinkRecordingState.REASON_NOT_CONNECTED), phases)

        watch.reachable = true
        published.clear()
        recorded.start(request(), owner)
        runCurrent()
        watch.reachable = false
        advanceTimeBy(60_001)
        assertEquals(listOf(OpenLinkRecordingPhase.RECORDING to null), phases)
        assertTrue(recorded.holds("ride-1"))
    }
}
