package tech.mmarca.openwatch.features.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import tech.mmarca.openwatch.data.repository.BleDeviceRepository
import tech.mmarca.openwatch.devices.garmin.GarminRealtimeState
import tech.mmarca.openwatch.devices.garmin.GarminRealtimeStore
import tech.mmarca.openwatch.domain.model.BleSensorDevice
import tech.mmarca.openwatch.features.watches.DeviceSyncController

/** The watch tile at the top of the home screen: the watch being synced, else the most recently synced. */
data class WatchTileState(
    val deviceId: String,
    val name: String,
    val batteryPercent: Int?,
    val lastSyncedAt: Instant?,
    /** More watches than the one shown, for a "+2" style suffix. */
    val additionalCount: Int = 0,
    val isSyncing: Boolean = false,
    /** Live beats per minute over a held link, or null when nothing fresh is streaming. */
    val liveHeartRateBpm: Int? = null,
    /** Today's step count as the watch counts it, live over the same link. */
    val liveSteps: Int? = null,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    deviceRepository: BleDeviceRepository,
    private val syncController: DeviceSyncController,
    realtimeStore: GarminRealtimeStore,
) : ViewModel() {

    val watchTile: StateFlow<WatchTileState?> = combine(
        deviceRepository.devicesFlow,
        syncController.state,
        realtimeStore.readings,
    ) { devices, sync, live ->
        devices.toWatchTileState(syncingDeviceId = sync.syncingDeviceId, live = live)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun syncWatch(deviceId: String) {
        syncController.syncDevice(deviceId)
    }
}

/** Bike computers excluded: the tile is for a wrist. */
internal fun List<BleSensorDevice>.toWatchTileState(
    syncingDeviceId: String? = null,
    live: GarminRealtimeState = GarminRealtimeState(),
    now: Instant = Instant.now(),
): WatchTileState? {
    val watches = filter { it.isWatch }
    // The watch being synced owns the tile; failing that, the most recently synced.
    val primary = watches.firstOrNull { it.id == syncingDeviceId }
        ?: watches.maxByOrNull { it.lastSyncedAt?.toEpochMilli() ?: Long.MIN_VALUE }
        ?: return null
    return WatchTileState(
        deviceId = primary.id,
        name = primary.displayName,
        batteryPercent = primary.batteryPercent,
        lastSyncedAt = primary.lastSyncedAt,
        additionalCount = watches.size - 1,
        isSyncing = primary.id == syncingDeviceId,
        // Only one watch can hold a link, so the live values belong to it or nothing.
        liveHeartRateBpm = live.freshHeartRate(now),
        liveSteps = live.freshSteps(now),
    )
}
