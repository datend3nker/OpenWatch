package tech.mmarca.openwatch.link

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import tech.mmarca.openlink.OpenLinkConnection
import tech.mmarca.openlink.OpenLinkDevice
import tech.mmarca.openlink.OpenLinkDeviceKind
import tech.mmarca.openlink.OpenLinkIntegration
import tech.mmarca.openlink.OpenLinkRecordingFeature
import tech.mmarca.openlink.OpenLinkSampleType
import tech.mmarca.openlink.OpenLinkSyncResult
import tech.mmarca.openwatch.core.performance.ApplicationScope
import tech.mmarca.openwatch.data.repository.BleDeviceRepository
import tech.mmarca.openwatch.devices.core.DeviceFamilies
import tech.mmarca.openwatch.devices.core.DeviceFeature
import tech.mmarca.openwatch.devices.core.RecordedWorkoutPort
import tech.mmarca.openwatch.devices.core.sync.DeviceSyncController
import tech.mmarca.openwatch.devices.core.sync.DeviceSyncResult
import tech.mmarca.openwatch.devices.core.sync.SkipReason
import tech.mmarca.openwatch.devices.core.sync.SyncAttempt
import tech.mmarca.openwatch.devices.core.sync.SyncFailure
import tech.mmarca.openwatch.domain.model.BleSensorDevice

/**
 * The paired devices as OpenLink shows them: every registered device a family owns, with what
 * the family says they can do and whether their link is up, and the sync state. Each goes by
 * its registry id, which says nothing about the device. Kept as a StateFlow so the binder
 * thread reads it from memory.
 */
@Singleton
class LinkDevices @Inject constructor(
    deviceRepository: BleDeviceRepository,
    syncController: DeviceSyncController,
    families: DeviceFamilies,
    recordedWorkouts: Set<@JvmSuppressWildcards RecordedWorkoutPort>,
    @ApplicationScope appScope: CoroutineScope,
) {
    private val scope = appScope

    val devices: StateFlow<List<OpenLinkDevice>> = combine(
        deviceRepository.devicesFlow,
        syncController.state,
        families.connections,
    ) { registered, sync, connections ->
        linkDevices(registered, sync.syncingDeviceIds, families, connections, sync.attempts) { device ->
            recordedWorkouts.firstOrNull { it.canRecord(device) }?.exerciseTypes.orEmpty()
        }
    }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, emptyList())
}

/** Pure, for the tests: what OpenLink shows of OpenWatch's devices. */
internal fun linkDevices(
    registered: List<BleSensorDevice>,
    syncingDeviceIds: Set<String>,
    families: DeviceFamilies,
    connections: Map<String, Boolean>,
    attempts: Map<String, SyncAttempt> = emptyMap(),
    /** The sports [device] records a workout of for an app (`RECORD`); empty where it records none. */
    recordedExerciseTypes: (BleSensorDevice) -> Set<Int> = { emptySet() },
): List<OpenLinkDevice> =
    registered.mapNotNull { device ->
        val family = families.of(device) ?: return@mapNotNull null
        val features = family.features(device)
        OpenLinkDevice(
            id = device.id,
            displayName = device.displayName,
            // Open sets: a new family or kind goes by its own persisted name, and a client on
            // an older library leaves it out.
            kind = OpenLinkDeviceKind(device.kind.storageName),
            integration = OpenLinkIntegration(family.id),
            connection = when (connections[device.id]) {
                true -> OpenLinkConnection.CONNECTED
                false -> OpenLinkConnection.DISCONNECTED
                null -> OpenLinkConnection.UNKNOWN
            },
            batteryPercent = device.batteryPercent,
            batteryUpdatedAtMillis = device.batteryUpdatedAt?.toEpochMilli(),
            lastSyncedAtMillis = device.lastSyncedAt?.toEpochMilli(),
            syncing = device.id in syncingDeviceIds,
            supportsSync = DeviceFeature.SYNC in features,
            // A recording keeps a device's live readings flowing: one that streams none
            // (a scale, a cuff, a meter) has nothing to record.
            supportsRecording = features.any { it == DeviceFeature.LIVE_HEART_RATE || it == DeviceFeature.LIVE_STEPS },
            liveSampleTypes = buildSet {
                if (DeviceFeature.LIVE_HEART_RATE in features) add(OpenLinkSampleType.HEART_RATE)
                if (DeviceFeature.LIVE_STEPS in features) add(OpenLinkSampleType.STEPS)
            },
            // Whether the device has GPS the phone does not know, so it is not offered.
            recordingFeatures = if (recordedExerciseTypes(device).isEmpty()) emptySet() else RECORDED_WORKOUT_FEATURES,
            exerciseTypes = recordedExerciseTypes(device),
            lastSyncResult = attempts[device.id]?.result?.let(::linkSyncResult),
            lastSyncAttemptAtMillis = attempts[device.id]?.atMillis,
        )
    }

/**
 * How a sync ended, as OpenLink names it: a missing grant, also for a series a missing grant left
 * out, is [OpenLinkSyncResult.PERMISSION_MISSING]; a device out of reach [OpenLinkSyncResult.DISCONNECTED];
 * a link lost after part was written [OpenLinkSyncResult.INTERRUPTED].
 */
internal fun linkSyncResult(result: DeviceSyncResult): OpenLinkSyncResult = when (result) {
    is DeviceSyncResult.Succeeded ->
        if (result.skipped.any { it.reason == SkipReason.PERMISSION }) OpenLinkSyncResult.PERMISSION_MISSING else OpenLinkSyncResult.SUCCEEDED
    is DeviceSyncResult.Failed -> when (result.failure) {
        SyncFailure.HealthConnectPermission, SyncFailure.BluetoothPermission -> OpenLinkSyncResult.PERMISSION_MISSING
        SyncFailure.Unreachable, SyncFailure.BluetoothOff -> OpenLinkSyncResult.DISCONNECTED
        SyncFailure.Interrupted -> OpenLinkSyncResult.INTERRUPTED
        else -> OpenLinkSyncResult.FAILED
    }
}

private val RECORDED_WORKOUT_FEATURES = setOf(OpenLinkRecordingFeature.HEART_RATE, OpenLinkRecordingFeature.PAUSE, OpenLinkRecordingFeature.AUTO_PAUSE)
