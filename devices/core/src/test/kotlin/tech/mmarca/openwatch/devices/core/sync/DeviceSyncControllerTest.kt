package tech.mmarca.openwatch.devices.core.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tech.mmarca.openwatch.data.repository.contract.FakeDeviceRegistry
import tech.mmarca.openwatch.domain.model.BleDeviceKind
import tech.mmarca.openwatch.domain.model.BleSensorCapability
import tech.mmarca.openwatch.domain.model.BleSensorDevice
import tech.mmarca.openwatch.domain.model.DeviceIntegration

/**
 * Row-state discipline: one sync at a time, progress scoped to the syncing device,
 * failures as a banner, non-claimable devices a no-op. The pull, import and stamp are in `GarminWatchSyncService`.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DeviceSyncControllerTest {

    private fun files(count: Int) = DeviceSyncResult.Succeeded(written = mapOf(SyncTally.FILES to count))

    private lateinit var repo: FakeDeviceRegistry
    private lateinit var port: FakeDeviceSyncPort

    @Before
    fun setUp() {
        repo = FakeDeviceRegistry()
        // A Garmin watch syncs; a plain sensor does not.
        port = FakeDeviceSyncPort(canSync = { it.integration == DeviceIntegration.GARMIN }).apply {
            progressToReport = DeviceSyncProgress(phase = DeviceSyncPhase.DOWNLOADING, total = 2, done = 1)
        }
    }

    private fun addWatch(address: String = "E0:48:24:D5:F7:10"): BleSensorDevice = repo.addDevice(
        displayName = "vívoactive 5",
        address = address,
        bluetoothName = "vívoactive 5",
        capabilities = emptySet(),
        kind = BleDeviceKind.WATCH,
        integration = DeviceIntegration.GARMIN,
    )

    private fun kotlinx.coroutines.test.TestScope.controller() =
        DeviceSyncController(repo, port, backgroundScope)

    @Test
    fun `a scheduled sync runs in the caller and is cancelled with it, freeing the radio`() = runTest {
        val watch = addWatch()
        port.gate = CompletableDeferred()
        val controller = controller()

        // The worker's coroutine: when the system stops the job, this is what is cancelled.
        val job = launch { controller.runSync(watch.id) }
        runCurrent()
        assertTrue(controller.state.value.isSyncingDevice(watch.id))

        job.cancel()
        runCurrent()

        assertFalse(controller.state.value.isSyncing)
        assertTrue(job.isCancelled)
        // The radio is free for the next run.
        assertNotNull(controller.syncDevice(watch.id))
    }

    @Test
    fun `a sync no job holds awake runs under the wake lock, a scheduled one does not`() = runTest {
        val watch = addWatch()
        var held = 0
        val lock = object : SyncWakeLock {
            override suspend fun <T> holding(block: suspend () -> T): T {
                held++
                return block()
            }
        }
        val controller = DeviceSyncController(repo, port, backgroundScope, lock)

        controller.syncDevice(watch.id)!!.join()
        controller.runSync(watch.id)

        assertEquals(1, held)
        assertEquals(2, port.calls)
    }

    @Test
    fun `a scheduled sync returns its result to the caller`() = runTest {
        val watch = addWatch()
        port.result = files(4)

        val result = controller().runSync(watch.id)

        assertEquals(files(4), result)
    }

    @Test
    fun `a successful sync ends idle with the file count`() = runTest {
        val watch = addWatch()
        port.result = files(3)
        val controller = controller()

        controller.syncDevice(watch.id)!!.join()

        assertEquals(1, port.calls)
        assertEquals(watch.id, port.seenDevice?.id)
        assertFalse(controller.state.value.isSyncing)
        assertTrue(controller.state.value.running.isEmpty())
        assertEquals(files(3), controller.state.value.lastFor(watch.id))
        // Scoped, so another watch's card does not show this outcome.
        assertNull("another watch's card shows none of it", controller.state.value.lastFor("other"))
        assertNull(controller.state.value.failureFor(watch.id))
    }

    @Test
    fun `the radio reads busy the instant the tap returns`() = runTest {
        val watch = addWatch()
        port.gate = CompletableDeferred()
        val controller = controller()

        val job = controller.syncDevice(watch.id)

        assertNotNull(job)
        assertTrue(controller.state.value.isSyncing)
        assertTrue(controller.state.value.isSyncingDevice(watch.id))
        port.gate!!.complete(Unit)
        job!!.join()
    }

    @Test
    fun `reports progress scoped to the syncing device`() = runTest {
        val watch = addWatch()
        val other = repo.addDevice(
            displayName = "Other watch",
            address = "AA:BB:CC:DD:EE:FF",
            bluetoothName = "Other",
            capabilities = emptySet(),
            kind = BleDeviceKind.WATCH,
            integration = DeviceIntegration.GARMIN,
        )
        port.gate = CompletableDeferred()
        val controller = controller()

        val job = controller.syncDevice(watch.id)!!
        runCurrent()

        assertTrue(controller.state.value.isSyncingDevice(watch.id))
        assertFalse(controller.state.value.isSyncingDevice(other.id))
        assertEquals(DeviceSyncProgress(DeviceSyncPhase.DOWNLOADING, total = 2, done = 1), controller.state.value.running[watch.id])
        port.gate!!.complete(Unit)
        job.join()
    }

    @Test
    fun `refuses a second sync while one is running`() = runTest {
        val watch = addWatch()
        port.gate = CompletableDeferred()
        val controller = controller()

        val first = controller.syncDevice(watch.id)
        val second = controller.syncDevice(watch.id)

        // One sync per device: the second call is dropped rather than queued.
        assertNotNull(first)
        assertNull(second)
        port.gate!!.complete(Unit)
        first!!.join()
        assertEquals(1, port.calls)
    }

    @Test
    fun `another device syncs beside a running one, each with its own state`() = runTest {
        val watch = addWatch()
        val other = addWatch(address = "E0:48:24:D5:F7:11")
        port.gate = CompletableDeferred()
        val controller = controller()

        val first = controller.syncDevice(watch.id)
        val second = controller.syncDevice(other.id)

        // Each radio has its own lease, so one slow sync no longer starves the rest.
        assertNotNull(first)
        assertNotNull(second)
        assertEquals(setOf(watch.id, other.id), controller.state.value.syncingDeviceIds)
        port.gate!!.complete(Unit)
        first!!.join()
        second!!.join()
        assertEquals(2, port.calls)
        assertNotNull(controller.state.value.lastFor(watch.id))
        assertNotNull(controller.state.value.lastFor(other.id))
    }

    @Test
    fun `of callers on several threads at once, exactly one gets the radio`() = runTest {
        val watch = addWatch()
        port.gate = CompletableDeferred()
        val controller = controller()
        val start = java.util.concurrent.CountDownLatch(1)
        val claims = java.util.concurrent.atomic.AtomicInteger()

        val threads = List(8) {
            Thread {
                start.await()
                if (controller.syncDevice(watch.id) != null) claims.incrementAndGet()
            }
        }
        threads.forEach(Thread::start)
        start.countDown()
        threads.forEach(Thread::join)

        assertEquals(1, claims.get())
        port.gate!!.complete(Unit)
    }

    @Test
    fun `a failure surfaces its message and ends idle`() = runTest {
        val watch = addWatch()
        port.result = DeviceSyncResult.Failed(SyncFailure.Unreachable)
        val controller = controller()

        controller.syncDevice(watch.id)!!.join()

        assertFalse(controller.state.value.isSyncing)
        assertEquals(SyncFailure.Unreachable, controller.state.value.failureFor(watch.id))
        assertNull("another watch's card shows none of it", controller.state.value.failureFor("other"))
        assertNull(controller.state.value.lastFor(watch.id))
    }

    @Test
    fun `a run that failed part-way shows what it wrote beside why it stopped`() = runTest {
        val watch = addWatch()
        port.result = DeviceSyncResult.Failed(SyncFailure.Interrupted, partial = files(2))
        val controller = controller()

        controller.syncDevice(watch.id)!!.join()

        assertEquals(files(2), controller.state.value.lastFor(watch.id))
        assertEquals(SyncFailure.Interrupted, controller.state.value.failureFor(watch.id))
    }

    @Test
    fun `ignores a device the port does not claim`() = runTest {
        val sensor = repo.addDevice(
            displayName = "Chest strap",
            address = "11:22:33:44:55:66",
            bluetoothName = "TICKR",
            capabilities = setOf(BleSensorCapability.HEART_RATE),
        )
        val controller = controller()

        assertNull(controller.syncDevice(sensor.id))
        assertEquals(0, port.calls)
        assertFalse(controller.state.value.isSyncing)
    }

    @Test
    fun `ignores an unknown device id`() = runTest {
        val controller = controller()

        assertNull(controller.syncDevice("does-not-exist"))
        assertEquals(0, port.calls)
    }

    @Test
    fun `clear resets the banner but never a running sync`() = runTest {
        val watch = addWatch()
        port.result = files(2)
        val controller = controller()
        controller.syncDevice(watch.id)!!.join()
        assertNotNull(controller.state.value.lastFor(watch.id))

        controller.clear()

        assertNull(controller.state.value.lastFor(watch.id))
        assertNull(controller.state.value.failureFor(watch.id))

        // A running sync's state must survive a stray clear().
        port.gate = CompletableDeferred()
        val job = controller.syncDevice(watch.id)!!
        controller.clear()
        assertTrue(controller.state.value.isSyncing)
        port.gate!!.complete(Unit)
        job.join()
    }

    @Test
    fun `a port that throws still leaves the radio free`() = runTest {
        val watch = addWatch()
        port.thrown = IllegalStateException("the port broke its contract")
        val controller = controller()

        controller.syncDevice(watch.id)!!.join()

        // A sync never throws. If one does, the radio must not read busy forever.
        assertFalse(controller.state.value.isSyncing)
        assertTrue(controller.state.value.failureFor(watch.id) is SyncFailure.Unknown)
        assertNotNull(controller.syncDevice(watch.id))
    }

    @Test
    fun `a silent failure ends idle without a banner`() = runTest {
        val watch = addWatch()
        port.result = DeviceSyncResult.Failed(SyncFailure.Unreachable)
        val controller = controller()

        // A scheduled sync out of range at 3am must not leave a red message for later.
        controller.syncDevice(watch.id, SyncTrigger.BACKGROUND)!!.join()

        assertFalse(controller.state.value.isSyncing)
        assertNull(controller.state.value.failureFor(watch.id))
    }

    @Test
    fun `a silent success still reports the files it took`() = runTest {
        val watch = addWatch()
        port.result = files(4)
        val controller = controller()

        controller.syncDevice(watch.id, SyncTrigger.BACKGROUND)!!.join()

        assertEquals(files(4), controller.state.value.lastFor(watch.id))
    }

    @Test
    fun `the outcome comes back to the caller that awaits it`() = runTest {
        val watch = addWatch()
        port.result = DeviceSyncResult.Failed(SyncFailure.RadioBusy)
        val controller = controller()

        // What the scheduled run reads to decide between a retry and waiting.
        val result = controller.syncDevice(watch.id, SyncTrigger.BACKGROUND)!!.await()

        assertEquals(DeviceSyncResult.Failed(SyncFailure.RadioBusy), result)
    }

    @Test
    fun `a tapped sync reaches the port as requested, the worker's as background`() = runTest {
        val watch = addWatch()
        val controller = controller()

        controller.syncDevice(watch.id)!!.join()
        assertEquals(SyncTrigger.REQUESTED, port.seenTrigger)

        controller.runSync(watch.id)
        assertEquals(SyncTrigger.BACKGROUND, port.seenTrigger)
    }

    @Test
    fun `every run's result is kept for OpenLink, a background failure too, and a clear keeps it`() = runTest {
        val watch = addWatch()
        val controller = controller()
        port.result = DeviceSyncResult.Failed(SyncFailure.Unreachable)

        controller.runSync(watch.id, SyncTrigger.BACKGROUND)

        assertEquals(DeviceSyncResult.Failed(SyncFailure.Unreachable), controller.state.value.attempts.getValue(watch.id).result)
        assertNull("a background failure leaves no banner", controller.state.value.failureFor(watch.id))
        controller.clear()
        assertTrue(watch.id in controller.state.value.attempts)
    }
}
