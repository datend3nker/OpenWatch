package tech.mmarca.openwatch.features.watches

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tech.mmarca.openwatch.data.repository.BleDeviceRepository
import tech.mmarca.openwatch.core.presentation.ScreenError
import tech.mmarca.openwatch.core.presentation.toScreenError
import tech.mmarca.openwatch.devices.core.sync.AutoSyncInterval
import tech.mmarca.openwatch.devices.wearos.OnboardWearOsWatchUseCase
import tech.mmarca.openwatch.devices.wearos.WearOsCompanionManager
import tech.mmarca.openwatch.devices.wearos.WearOsCompanionStatus
import tech.mmarca.openwatch.devices.wearos.WearOsLinkSnapshot
import tech.mmarca.openwatch.devices.wearos.WearOsLinkStore
import tech.mmarca.openwatch.domain.model.BleSensorDevice
import tech.mmarca.openwatch.navigation.WATCH_DEVICE_ID_ARG

/** Everything the device view shows about one watch. */
@Immutable
data class WatchDeviceUiState(
    /** Null while loading and after removal — the screen shows its no-data state. */
    val device: BleSensorDevice? = null,
    val sync: DeviceSyncUiState = DeviceSyncUiState(),
    /** How often the watch syncs on its own, or OFF for by-hand only. */
    val autoSync: AutoSyncInterval = AutoSyncInterval.OFF,
    /**
     * Health Connect offers background access and the user has not granted it. A sync that
     * runs with the app closed then saves the watch's data, but nothing built from other
     * apps' records catches up until the app is opened.
     */
    val backgroundReadMissing: Boolean = false,
    /** Wear OS connection and app responsiveness state. */
    val wearOsStatus: WearOsCompanionStatus =
        WearOsCompanionStatus(),
    /** True while a Wear OS status check / ping is in flight. */
    val isCheckingWearOsStatus: Boolean = false,
    /** Why the last Wear OS check failed. [ScreenError.PermissionDenied] gets a grant affordance. */
    val wearOsError: ScreenError? = null,
    /** The watch's own settings as it last reported them; null until read or when it cannot serve them. */
    val watchSettings: Map<String, String>? = null,
    /** True while the watch's settings are read or one is being changed. */
    val isWatchSettingsBusy: Boolean = false,
    /** Why the last read or change of the watch's settings failed. */
    val watchSettingsError: ScreenError? = null,
)

/** One watch, and everything about it. */
@HiltViewModel
class WatchDeviceViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val deviceRepository: BleDeviceRepository,
    private val syncController: DeviceSyncController,
    private val autoSyncScheduler: WatchAutoSyncScheduler,
    private val onboardWearOsWatch: OnboardWearOsWatchUseCase,
    private val healthConnectManager: tech.mmarca.openwatch.healthconnect.HealthConnectGateway,
    dispatchers: tech.mmarca.openwatch.core.performance.DispatcherProvider,
    private val wearOsCompanionManager: WearOsCompanionManager,
    private val wearOsLinkStore: WearOsLinkStore,
) : ViewModel() {

    val deviceId: String = savedStateHandle.get<String>(WATCH_DEVICE_ID_ARG).orEmpty()

    /** Outlives the view-model: the unbond after a removal must not die with the screen. */
    private val housekeepingScope = CoroutineScope(SupervisorJob() + dispatchers.default)

    private val localState = MutableStateFlow(WatchDeviceUiState())

    val uiState: StateFlow<WatchDeviceUiState> = combine(
        deviceRepository.devicesFlow,
        syncController.state,
        localState,
    ) { devices, sync, local ->
        local.copy(
            device = devices.firstOrNull { it.id == deviceId },
            sync = sync,
            autoSync = autoSyncScheduler.interval(deviceId),
        )
    }
        .stateInViewModel(initial = WatchDeviceUiState())

    /**
     * A Wear OS watch's heart rate as it happens, null while none arrives.
     * Streams only while the screen collects it, so the watch's sensor runs
     * unbatched only while someone looks.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val wearOsLiveHeartRateBpm: StateFlow<Int?> = deviceRepository.devicesFlow
        .map { devices -> devices.firstOrNull { it.id == deviceId }?.takeIf { it.isWearosWatch } }
        .distinctUntilChanged { old, new -> old?.address == new?.address && old?.bluetoothName == new?.bluetoothName }
        .flatMapLatest { device ->
            device?.let { wearOsCompanionManager.liveHeartRate(it.address, it.bluetoothName) } ?: flowOf(null)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(LIVE_STOP_TIMEOUT_MS), null)

    init {
        // The first check runs once the device has loaded; later ones are by hand. The watch's
        // settings are read after it: the watch serves one connection at a time.
        viewModelScope.launch {
            if (uiState.first { it.device != null }.device?.isWearosWatch == true) {
                checkWearOsStatus()
                uiState.first { !it.isCheckingWearOsStatus }
                loadWatchSettings()
            }
        }
        // A sync that ends in a typed failure updates the status row without another ping.
        viewModelScope.launch {
            wearOsLinkStore.snapshots.collect { snapshots ->
                val snapshot = snapshots[deviceId] ?: return@collect
                localState.update { state ->
                    state.copy(
                        wearOsStatus = state.wearOsStatus.copy(
                            appStatus = snapshot.status,
                            watchName = snapshot.watchName ?: state.wearOsStatus.watchName,
                            bondLost = snapshot.bondLost,
                            lastCheckedAt = snapshot.checkedAt,
                        ),
                    )
                }
            }
        }
    }

    /** Checks whether the Wear OS watch is paired and its OpenWatch app answers. */
    fun checkWearOsStatus() {
        val device = uiState.value.device ?: return
        if (!device.isWearosWatch || localState.value.isCheckingWearOsStatus) return
        localState.update { it.copy(isCheckingWearOsStatus = true) }
        viewModelScope.launch {
            try {
                val status = wearOsCompanionManager.checkWearOsWatchStatus(
                    targetAddress = device.address,
                    targetName = device.bluetoothName,
                )
                localState.update { it.copy(wearOsStatus = status, wearOsError = null) }
                wearOsLinkStore.record(
                    device.id,
                    WearOsLinkSnapshot(status.appStatus, watchName = status.watchName, bondLost = status.bondLost, checkedAt = Instant.now()),
                )
            } catch (e: Exception) {
                val error = e.toScreenError(logTag = TAG, logMessage = "Wear OS status check failed")
                localState.update { it.copy(wearOsError = error) }
            } finally {
                localState.update { it.copy(isCheckingWearOsStatus = false) }
            }
        }
    }

    /** Reads the watch's own settings over the link. */
    fun loadWatchSettings() {
        val device = uiState.value.device ?: return
        if (!device.isWearosWatch || localState.value.isWatchSettingsBusy) return
        localState.update { it.copy(isWatchSettingsBusy = true) }
        viewModelScope.launch {
            try {
                val held = wearOsCompanionManager.watchSettings(device.address, device.bluetoothName)
                localState.update { it.copy(watchSettings = held, watchSettingsError = null) }
            } catch (e: Exception) {
                localState.update { it.copy(watchSettingsError = e.toScreenError(logTag = TAG, logMessage = "Watch settings read failed")) }
            } finally {
                localState.update { it.copy(isWatchSettingsBusy = false) }
            }
        }
    }

    /** Changes one of the watch's settings, then reads them all back: the watch is the truth. */
    fun setWatchSetting(key: String, value: String) {
        val device = uiState.value.device ?: return
        if (localState.value.isWatchSettingsBusy) return
        localState.update { state -> state.copy(isWatchSettingsBusy = true, watchSettings = state.watchSettings?.plus(key to value)) }
        viewModelScope.launch {
            try {
                wearOsCompanionManager.setWatchSetting(device.address, device.bluetoothName, key, value)
                val held = wearOsCompanionManager.watchSettings(device.address, device.bluetoothName)
                localState.update { it.copy(watchSettings = held, watchSettingsError = null) }
            } catch (e: Exception) {
                localState.update { it.copy(watchSettingsError = e.toScreenError(logTag = TAG, logMessage = "Watch setting change failed")) }
            } finally {
                localState.update { it.copy(isWatchSettingsBusy = false) }
            }
        }
    }

    /** Picks the auto-sync interval. The scheduler owns the stored choice and the periodic work. */
    fun setAutoSync(interval: AutoSyncInterval) {
        autoSyncScheduler.setInterval(deviceId, interval)
        localState.update { it.copy(autoSync = interval) }
    }

    /** Health Connect's permission string for background reads, for the screen's request. */
    val backgroundReadPermission: String
        get() = androidx.health.connect.client.permission.HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND

    /** Re-reads the background grant: on resume, and after the permission screen returns. */
    fun refreshBackgroundAccess() {
        viewModelScope.launch {
            val missing = runCatching { healthConnectManager.isBackgroundReadGrantMissing() }.getOrDefault(false)
            localState.update { it.copy(backgroundReadMissing = missing) }
        }
    }

    fun syncNow() {
        syncController.syncDevice(deviceId, listenAfter = DeviceSyncController.MANUAL_SYNC_LINGER)
    }

    /** Renames the watch without touching anything else it holds. */
    fun rename(displayName: String) {
        val trimmed = displayName.trim()
        if (trimmed.isEmpty()) return
        deviceRepository.updateDevice(deviceId = deviceId, displayName = trimmed)
    }

    fun setEnabled(enabled: Boolean) {
        deviceRepository.setDeviceEnabled(deviceId, enabled)
    }

    /**
     * Removes the watch: registry row, link token, and the OS-level bond or
     * association, fire and forget.
     */
    fun removeDevice() {
        // Read the device before forgetting it: the OS cleanup needs its address.
        val device = deviceRepository.devices.firstOrNull { it.id == deviceId }
        deviceRepository.removeDevice(deviceId)
        // Before the early return: a schedule left running would wake the radio for nothing.
        autoSyncScheduler.forget(deviceId)
        // Only a watch was associated and given a link token; anything else has nothing at the OS level.
        if (device == null || !device.isWearosWatch) return
        wearOsLinkStore.clear(deviceId, device.address)
        housekeepingScope.launch {
            runCatching { onboardWearOsWatch.forget(device.address) }
        }
    }

    private fun <T> kotlinx.coroutines.flow.Flow<T>.stateInViewModel(initial: T): StateFlow<T> {
        val state = MutableStateFlow(initial)
        viewModelScope.launch {
            collect { value -> state.value = value }
        }
        return state.asStateFlow()
    }
}

private const val TAG = "WatchDeviceViewModel"

/** Keeps the live stream through a rotation; longer, and the watch's sensor runs for nothing. */
private const val LIVE_STOP_TIMEOUT_MS = 5_000L
