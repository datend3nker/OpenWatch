package tech.mmarca.openwatch.devices.core.sync

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import tech.mmarca.openwatch.data.repository.contract.DeviceRegistry
import tech.mmarca.openwatch.domain.model.BleSensorDevice
import tech.mmarca.openwatch.core.performance.ApplicationScope

/**
 * Where each device's sync has got to, and how its last one ended. One sync per device at a
 * time; devices sync side by side, each radio held by its own lease. Device-agnostic.
 */
data class DeviceSyncUiState(
    /** Each running sync's last tick, by device id. */
    val running: Map<String, DeviceSyncProgress> = emptyMap(),
    /** What each device's last completed run wrote and left out; for a failed one, what it wrote first. */
    val last: Map<String, DeviceSyncResult.Succeeded> = emptyMap(),
    /** Why each device's last requested run failed; a background one ends without it. */
    val failures: Map<String, SyncFailure> = emptyMap(),
    /**
     * How each device's last run ended, requested or not, and when: what an app connected over
     * OpenLink is told (`lastSyncResult`). Only this process's; [DeviceSyncController.clear] keeps it.
     */
    val attempts: Map<String, SyncAttempt> = emptyMap(),
) {
    val isSyncing: Boolean get() = running.isNotEmpty()

    /** The devices syncing now. */
    val syncingDeviceIds: Set<String> get() = running.keys

    fun isSyncingDevice(deviceId: String): Boolean = deviceId in running

    /** The last run's outcome for [deviceId]; none while it syncs. Only this session's: nothing persists it. */
    fun lastFor(deviceId: String): DeviceSyncResult.Succeeded? = last[deviceId].takeIf { deviceId !in running }

    /** Why the last run for [deviceId] failed; none while it syncs. */
    fun failureFor(deviceId: String): SyncFailure? = failures[deviceId].takeIf { deviceId !in running }
}

/** How a device's sync ended ([result]), and when ([atMillis]). */
data class SyncAttempt(val result: DeviceSyncResult, val atMillis: Long)

/**
 * Runs a device sync through the integration that owns the device. A
 * singleton with its own scope: the sync outlives the screen, and every
 * watch surface reads the same state.
 */
@Singleton
class DeviceSyncController @Inject constructor(
    private val deviceRepository: DeviceRegistry,
    private val syncPort: DeviceSyncPort,
    @param:ApplicationScope private val scope: CoroutineScope,
    private val wakeLock: SyncWakeLock = SyncWakeLock.None,
) {

    private val _state = MutableStateFlow(DeviceSyncUiState())
    val state: StateFlow<DeviceSyncUiState> = _state.asStateFlow()

    /**
     * Syncs [deviceId], one at a time, on the controller's own scope, and returns at once:
     * for a screen or another app over OpenLink, which must not wait. A device no port claims,
     * or a radio already busy, is null. A [SyncTrigger.BACKGROUND] failure ends idle, with no banner.
     */
    fun syncDevice(
        deviceId: String,
        trigger: SyncTrigger = SyncTrigger.REQUESTED,
    ): Deferred<DeviceSyncResult>? {
        val device = claim(deviceId) ?: return null
        // No job holds the phone awake for this one; the wake lock does.
        return scope.async { wakeLock.holding { runClaimed(device, trigger) } }
    }

    /**
     * Syncs [deviceId] in the caller's own coroutine: for the scheduled worker, so the sync
     * is the job's work. The job's wake lock then covers it, and when the system stops the
     * job, the sync is cancelled with it rather than left running unowned. Every integration
     * resumes from what it saved. Null as for [syncDevice].
     */
    suspend fun runSync(deviceId: String, trigger: SyncTrigger = SyncTrigger.BACKGROUND): DeviceSyncResult? {
        val device = claim(deviceId) ?: return null
        return runClaimed(device, trigger)
    }

    /**
     * Takes [deviceId]'s sync; null when no port claims the device or its sync already runs.
     * Claimed before any launch, so the device reads busy at once. A screen, the schedule and
     * another app over OpenLink call this from different threads: the claim is one
     * compare-and-set, so only one of two callers at the same moment gets it. Another device's
     * sync runs beside it; each radio is held by its own lease.
     */
    private fun claim(deviceId: String): BleSensorDevice? {
        val device = deviceRepository.devices.firstOrNull { it.id == deviceId } ?: return null
        if (!syncPort.canSync(device)) return null
        while (true) {
            val current = _state.value
            if (current.isSyncingDevice(deviceId)) return null
            val claimed = current.copy(
                running = current.running + (deviceId to DeviceSyncProgress(DeviceSyncPhase.HANDSHAKE)),
                failures = current.failures - deviceId,
            )
            if (_state.compareAndSet(current, claimed)) return device
        }
    }

    private suspend fun runClaimed(device: BleSensorDevice, trigger: SyncTrigger): DeviceSyncResult {
        val deviceId = device.id
        // The port owns the sequence. The catch is defensive: an uncaught exception would
        // leave the radio reading busy forever.
        val result = try {
            syncPort.sync(
                device,
                trigger = trigger,
                onProgress = { progress ->
                    _state.update { current ->
                        if (!current.isSyncingDevice(deviceId)) current else current.copy(running = current.running + (deviceId to progress))
                    }
                },
            )
        } catch (error: CancellationException) {
            // Stopped part-way, as by the system ending the job: what came was saved.
            val stopped = SyncAttempt(DeviceSyncResult.Failed(SyncFailure.Interrupted), System.currentTimeMillis())
            _state.update { it.copy(running = it.running - deviceId, attempts = it.attempts + (deviceId to stopped)) }
            throw error
        } catch (error: Exception) {
            DeviceSyncResult.Failed(SyncFailure.Unknown(error))
        }
        val attempt = SyncAttempt(result, System.currentTimeMillis())
        // In the same update as the end: a connected app sees the device stop syncing and its result together.
        _state.update { current ->
            val idle = current.copy(
                running = current.running - deviceId,
                failures = current.failures - deviceId,
                attempts = current.attempts + (deviceId to attempt),
            )
            when (result) {
                is DeviceSyncResult.Succeeded -> idle.copy(last = idle.last + (deviceId to result))
                // A scheduled run out of range at 3am leaves no banner for later.
                is DeviceSyncResult.Failed -> if (trigger == SyncTrigger.BACKGROUND) {
                    idle.copy(last = idle.last - deviceId)
                } else {
                    idle.copy(
                        last = result.partial?.let { idle.last + (deviceId to it) } ?: (idle.last - deviceId),
                        failures = idle.failures + (deviceId to result.failure),
                    )
                }
            }
        }
        return result
    }

    /** Clears the finished and failed banners; a running sync keeps its state. */
    fun clear() {
        _state.update { it.copy(last = emptyMap(), failures = emptyMap()) }
    }
}
