package tech.mmarca.openwatch.link

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import tech.mmarca.openlink.OpenLinkConnection
import tech.mmarca.openlink.OpenLinkDevice
import tech.mmarca.openlink.OpenLinkDeviceKind
import tech.mmarca.openlink.OpenLinkIntegration
import tech.mmarca.openlink.OpenLinkSampleType
import tech.mmarca.openlink.OpenLinkSyncResult
import tech.mmarca.openwatch.data.repository.BleDeviceRepository
import tech.mmarca.openwatch.devices.core.sync.DeviceSyncOutcome
import tech.mmarca.openwatch.devices.wearos.WearOsAppStatus
import tech.mmarca.openwatch.devices.wearos.WearOsLinkSnapshot
import tech.mmarca.openwatch.devices.wearos.WearOsLinkStore
import tech.mmarca.openwatch.devices.xiaomi.ScaleListenerStatus
import tech.mmarca.openwatch.devices.xiaomi.XiaomiScaleConfig
import tech.mmarca.openwatch.devices.xiaomi.XiaomiScaleListener
import tech.mmarca.openwatch.devices.xiaomi.XiaomiScaleStore
import tech.mmarca.openwatch.domain.model.BleSensorDevice
import tech.mmarca.openwatch.features.watches.DeviceSyncController
import tech.mmarca.openwatch.features.watches.SyncAttempt

/**
 * The paired devices as OpenLink shows them: watches and bike computers from the device
 * registry, with the sync state and the Wear OS link status, and the scale. Kept as a
 * StateFlow so the binder thread reads it from memory.
 */
@Singleton
class LinkDevices @Inject constructor(
    deviceRepository: BleDeviceRepository,
    syncController: DeviceSyncController,
    wearOsLinkStore: WearOsLinkStore,
    scaleStore: XiaomiScaleStore,
    scaleListener: XiaomiScaleListener,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val devices: StateFlow<List<OpenLinkDevice>> = combine(
        deviceRepository.devicesFlow.combine(syncController.lastAttempts, ::Pair),
        syncController.state,
        wearOsLinkStore.snapshots,
        scaleStore.config,
        scaleListener.status,
    ) { (registered, attempts), sync, wearOs, scale, scaleStatus ->
        linkDevices(registered, sync.syncingDeviceId, wearOs, scale, scaleStatus, attempts)
    }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, emptyList())
}

/** Pure, for the tests: what OpenLink shows of OpenWatch's devices. */
internal fun linkDevices(
    registered: List<BleSensorDevice>,
    syncingDeviceId: String?,
    wearOs: Map<String, WearOsLinkSnapshot>,
    scale: XiaomiScaleConfig,
    scaleStatus: ScaleListenerStatus,
    attempts: Map<String, SyncAttempt> = emptyMap(),
): List<OpenLinkDevice> {
    val watches = registered.filter { it.isWatch || it.isBikeComputer }.mapNotNull { device ->
        val integration = when {
            device.isWearosWatch -> OpenLinkIntegration.WEAROS
            device.isGarminGfdi -> OpenLinkIntegration.GARMIN
            else -> return@mapNotNull null
        }
        val live = when (integration) {
            OpenLinkIntegration.GARMIN -> setOf(OpenLinkSampleType.HEART_RATE, OpenLinkSampleType.STEPS)
            else -> setOf(OpenLinkSampleType.HEART_RATE)
        }
        OpenLinkDevice(
            id = device.id,
            displayName = device.displayName,
            kind = if (device.isBikeComputer) OpenLinkDeviceKind.BIKE_COMPUTER else OpenLinkDeviceKind.WATCH,
            integration = integration,
            connection = when (integration) {
                OpenLinkIntegration.WEAROS -> wearOs[device.id]?.let {
                    if (it.status == WearOsAppStatus.APP_RUNNING) OpenLinkConnection.CONNECTED else OpenLinkConnection.DISCONNECTED
                } ?: OpenLinkConnection.UNKNOWN
                // Nothing tracks whether a Garmin link is up between syncs.
                else -> OpenLinkConnection.UNKNOWN
            },
            batteryPercent = device.batteryPercent,
            batteryUpdatedAtMillis = device.batteryUpdatedAt?.toEpochMilli(),
            lastSyncedAtMillis = device.lastSyncedAt?.toEpochMilli(),
            syncing = device.id == syncingDeviceId,
            supportsSync = true,
            supportsRecording = true,
            liveSampleTypes = live,
            lastSyncResult = attempts[device.id]?.outcome?.let(::linkSyncResult),
            lastSyncAttemptAtMillis = attempts[device.id]?.at?.toEpochMilli(),
        )
    }
    val scaleDevice = scale.address?.takeIf { scale.isSetUp }?.let { address ->
        OpenLinkDevice(
            id = SCALE_ID_PREFIX + address,
            displayName = scale.name ?: "S400",
            kind = OpenLinkDeviceKind.SCALE,
            integration = OpenLinkIntegration.XIAOMI,
            connection = if (scaleStatus == ScaleListenerStatus.LISTENING) OpenLinkConnection.CONNECTED else OpenLinkConnection.DISCONNECTED,
        )
    }
    return watches + listOfNotNull(scaleDevice)
}

private fun linkSyncResult(outcome: DeviceSyncOutcome): OpenLinkSyncResult = when (outcome) {
    DeviceSyncOutcome.SUCCEEDED -> OpenLinkSyncResult.SUCCEEDED
    DeviceSyncOutcome.INTERRUPTED -> OpenLinkSyncResult.INTERRUPTED
    DeviceSyncOutcome.PERMISSION_MISSING -> OpenLinkSyncResult.PERMISSION_MISSING
    DeviceSyncOutcome.DISCONNECTED -> OpenLinkSyncResult.DISCONNECTED
    DeviceSyncOutcome.FAILED -> OpenLinkSyncResult.FAILED
}

internal const val SCALE_ID_PREFIX = "xiaomi-scale:"
