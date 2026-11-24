package tech.mmarca.openwatch.features.watches.wearos

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Duration
import java.time.Instant
import tech.mmarca.openwatch.core.presentation.ScreenError
import tech.mmarca.openwatch.core.presentation.resolve
import tech.mmarca.openwatch.devices.core.DeviceFeature
import tech.mmarca.openwatch.devices.core.sync.AutoSyncInterval
import tech.mmarca.openwatch.devices.core.sync.DeviceSyncController
import tech.mmarca.openwatch.devices.core.sync.DeviceSyncUiState
import tech.mmarca.openwatch.devices.wearos.WearOsAppStatus
import tech.mmarca.openwatch.devices.wearos.WearOsCompanionStatus
import tech.mmarca.openwatch.devices.wearos.messageRes
import tech.mmarca.openwatch.devices.wearos.R as WearOsR
import tech.mmarca.openwatch.core.ui.R as UiR
import tech.mmarca.openwatch.features.device.R as DeviceR
import tech.mmarca.openwatch.domain.model.BleDeviceKind
import tech.mmarca.openwatch.domain.model.BleSensorDevice
import tech.mmarca.openwatch.domain.model.DeviceIntegration
import tech.mmarca.openwatch.features.watches.SyncOutcomeSummary
import tech.mmarca.openwatch.features.watches.WatchDevicePanel
import tech.mmarca.openwatch.features.watches.WatchDeviceScreen
import tech.mmarca.openwatch.features.watches.WatchDeviceUiState
import tech.mmarca.openwatch.features.watches.toScreenError
import tech.mmarca.openwatch.ui.components.InlineGrantPrompt
import tech.mmarca.openwatch.ui.components.OpenWatchCard
import tech.mmarca.openwatch.ui.components.OpenWatchOutlinedButton
import tech.mmarca.openwatch.ui.components.PermissionCallout
import tech.mmarca.openwatch.ui.components.SectionHeader
import tech.mmarca.openwatch.ui.components.SwitchRow
import tech.mmarca.openwatch.ui.components.allGranted
import tech.mmarca.openwatch.ui.components.formatDeviceTime
import tech.mmarca.openwatch.ui.components.rememberRuntimePermissionRequester
import tech.mmarca.openwatch.ui.theme.OpenWatchTheme
import tech.mmarca.openwatch.ui.theme.Spacing
import tech.mmarca.openwatch.wearlink.WearLinkPreferences as Preferences

/**
 * The Wear OS half of the device destination: the only place that knows the panel's ViewModel.
 * It streams the live heart rate while the screen is open, owns the Bluetooth grant request,
 * and draws [content] with the panel.
 */
@Composable
fun WearOsWatchPanelRoute(
    device: BleSensorDevice,
    sync: DeviceSyncUiState,
    onSync: () -> Unit,
    onAllowSyncGrants: (Set<String>) -> Unit,
    viewModel: WearOsWatchPanelViewModel = hiltViewModel(),
    content: @Composable (WatchDevicePanel) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val liveHeartRateBpm by viewModel.liveHeartRateBpm.collectAsStateWithLifecycle()

    val runtimePermissions = rememberRuntimePermissionRequester { granted ->
        viewModel.onRuntimePermissionResult(granted.allGranted())
    }
    LaunchedEffect(state.permissionRequest) {
        val request = state.permissionRequest ?: return@LaunchedEffect
        runtimePermissions.request(listOf(request.permission))
        viewModel.onPermissionRequestHandled()
    }
    // A sync may have handled a night: what it keeps of the skin temperature baseline is read
    // again when one starts or ends, here or, by its time, while the screen was away.
    LaunchedEffect(sync.isSyncingDevice(device.id), device.lastSyncedAt) {
        viewModel.refreshSkinBaseline()
    }

    content(
        wearOsWatchPanel(
            device = device,
            state = state,
            liveHeartRateBpm = liveHeartRateBpm,
            sync = sync,
            actions = WearOsWatchPanelActions(
                onSync = onSync,
                onAllowSyncGrants = onAllowSyncGrants,
                onCheckStatus = viewModel::checkWearOsStatus,
                onGrantBluetooth = viewModel::requestBluetoothConnect,
                onAssociate = viewModel::associateWearOs,
                onLoadSettings = viewModel::loadWatchSettings,
                onSetSetting = viewModel::setWatchSetting,
            ),
        ),
    )
}

/** What the Wear OS panel's controls do. Each does nothing by default, for a preview. */
@Immutable
class WearOsWatchPanelActions(
    val onSync: () -> Unit = {},
    val onAllowSyncGrants: (Set<String>) -> Unit = {},
    val onCheckStatus: () -> Unit = {},
    val onGrantBluetooth: () -> Unit = {},
    val onAssociate: () -> Unit = {},
    val onLoadSettings: () -> Unit = {},
    val onSetSetting: (key: String, value: String) -> Unit = { _, _ -> },
)

/**
 * A Wear OS watch's panel: under the header the link's status and the watch's own sync card,
 * which words the sync itself; before the Device section the watch's own settings.
 */
internal fun wearOsWatchPanel(
    device: BleSensorDevice,
    state: WearOsWatchPanelUiState,
    liveHeartRateBpm: Int?,
    sync: DeviceSyncUiState,
    actions: WearOsWatchPanelActions,
): WatchDevicePanel = WatchDevicePanel(
    afterHeader = {
        SectionHeader(stringResource(R.string.settings_watch_wearos_status_section))
        WearOsStatusCard(
            status = state.wearOsStatus,
            error = state.wearOsError,
            isChecking = state.isCheckingWearOsStatus,
            onCheck = actions.onCheckStatus,
            onGrantBluetooth = actions.onGrantBluetooth,
            liveHeartRateBpm = liveHeartRateBpm,
            onAssociate = actions.onAssociate.takeIf { state.wearOsCompanionMissing },
        )
        SectionHeader(stringResource(R.string.settings_watch_wearos_sync_section))
        WearOsSyncCard(device, sync, state.skinNightsToGo, onSync = actions.onSync, onAllowGrants = actions.onAllowSyncGrants)
    },
    afterLiveSensors = {
        SectionHeader(stringResource(R.string.watch_settings_section))
        WearOsWatchSettingsCard(
            settings = state.watchSettings,
            busy = state.isWatchSettingsBusy,
            error = state.watchSettingsError,
            onLoad = actions.onLoadSettings,
            onSet = actions.onSetSetting,
        )
    },
)

@Composable
private fun WearOsStatusCard(
    status: WearOsCompanionStatus,
    error: ScreenError?,
    isChecking: Boolean,
    onCheck: () -> Unit,
    onGrantBluetooth: () -> Unit,
    /** The heart rate the watch streams while this screen is open; null while none arrives. */
    liveHeartRateBpm: Int? = null,
    /** Set while the watch has no companion association: the card asks for it. */
    onAssociate: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    if (error == ScreenError.PermissionDenied) {
        PermissionCallout(
            title = stringResource(UiR.string.message_missing_permissions_title),
            body = stringResource(R.string.settings_watch_wearos_bt_permission),
            onGrant = onGrantBluetooth,
        )
        return
    }
    OpenWatchCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (status.isPaired) {
                        Icons.Outlined.CheckCircle
                    } else {
                        Icons.Outlined.RadioButtonUnchecked
                    },
                    contentDescription = null,
                    tint = if (status.isPaired) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(WearOsStatusIconSize),
                )
                Spacer(modifier = Modifier.width(Spacing.sm))
                Text(
                    text = if (status.isPaired) {
                        stringResource(R.string.settings_watch_wearos_bt_paired)
                    } else {
                        stringResource(WearOsR.string.settings_watch_wearos_bt_not_paired)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (status.isAppRunning) {
                        Icons.Outlined.CheckCircle
                    } else {
                        Icons.Outlined.RadioButtonUnchecked
                    },
                    contentDescription = null,
                    tint = if (status.isAppRunning) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(WearOsStatusIconSize),
                )
                Spacer(modifier = Modifier.width(Spacing.sm))
                Text(
                    text = when (status.appStatus) {
                        WearOsAppStatus.APP_RUNNING -> status.watchName
                            ?.let { stringResource(R.string.settings_watch_wearos_linked_to, it) }
                            ?: stringResource(WearOsR.string.settings_watch_wearos_app_running)
                        WearOsAppStatus.NOT_PAIRED -> stringResource(DeviceR.string.settings_watch_not_connected)
                        else -> stringResource(status.appStatus.messageRes())
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (status.isAppRunning) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            liveHeartRateBpm?.let { bpm ->
                Text(
                    text = stringResource(DeviceR.string.settings_watch_live_now, bpm),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (status.bondLost || status.appStatus == WearOsAppStatus.NOT_PAIRED) {
                Text(
                    text = stringResource(R.string.settings_watch_wearos_status_bond_lost),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OpenWatchOutlinedButton(
                    onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) } },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(DeviceR.string.settings_sensors_open_bluetooth))
                }
            }

            if (onAssociate != null) {
                InlineGrantPrompt(
                    message = stringResource(R.string.settings_watch_wearos_companion_missing),
                    actionLabel = stringResource(R.string.settings_watch_wearos_companion_link),
                    onGrant = onAssociate,
                )
            }

            error.resolve()?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            OpenWatchOutlinedButton(
                onClick = onCheck,
                enabled = !isChecking,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isChecking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(WearOsButtonIconSize),
                        strokeWidth = WearOsProgressStroke,
                    )
                    Spacer(modifier = Modifier.width(Spacing.sm))
                    Text(stringResource(R.string.settings_watch_wearos_checking))
                } else {
                    Icon(
                        imageVector = Icons.Outlined.Sync,
                        contentDescription = null,
                        modifier = Modifier.size(WearOsButtonIconSize),
                    )
                    Spacer(modifier = Modifier.width(Spacing.sm))
                    Text(stringResource(R.string.settings_watch_wearos_check_app))
                }
            }
        }
    }
}

/**
 * The sync for a Wear OS watch: what it does, when it last ran, what the
 * last run brought (heart rate samples and estimated nights), what it still
 * waits for, and the button. The sync itself is the shared
 * [DeviceSyncController] flow; this card only words it.
 */
@Composable
private fun WearOsSyncCard(
    device: BleSensorDevice,
    sync: DeviceSyncUiState,
    /** The nights the skin temperature baseline still needs; null when the card has nothing to say of it. */
    skinNightsToGo: Int?,
    onSync: () -> Unit,
    onAllowGrants: (Set<String>) -> Unit,
) {
    val syncingThis = sync.isSyncingDevice(device.id)
    OpenWatchCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(
                text = stringResource(R.string.settings_watch_wearos_sync_body),
                style = MaterialTheme.typography.bodyMedium,
            )
            val syncedAt = device.lastSyncedAt
            Text(
                text = if (syncedAt == null) {
                    stringResource(DeviceR.string.settings_watch_never_synced)
                } else {
                    stringResource(DeviceR.string.settings_watch_last_synced, formatDeviceTime(syncedAt))
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            sync.lastFor(device.id)?.let { SyncOutcomeSummary(it, onAllowGrants) }
            skinNightsToGo?.let { SkinBaselineLine(it) }
            sync.failureFor(device.id)?.toScreenError().resolve()?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            OpenWatchOutlinedButton(
                onClick = onSync,
                // One sync at a time for this watch; another device may sync beside it.
                enabled = !syncingThis,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (syncingThis) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(WearOsButtonIconSize),
                        strokeWidth = WearOsProgressStroke,
                    )
                    Spacer(modifier = Modifier.width(Spacing.sm))
                    Text(stringResource(R.string.settings_watch_wearos_syncing))
                } else {
                    Icon(
                        imageVector = Icons.Outlined.Sync,
                        contentDescription = null,
                        modifier = Modifier.size(WearOsButtonIconSize),
                    )
                    Spacer(modifier = Modifier.width(Spacing.sm))
                    Text(stringResource(R.string.settings_watch_wearos_sync_now))
                }
            }
        }
    }
}

/**
 * How many more nights the skin temperature baseline needs before a night is written against
 * it. The count only: after a sync that handled such a night, the run's summary above says in
 * a sentence why nothing was written, and this line is its short companion; between syncs it
 * stands alone, since the summary is this session's and the nights remembered are not.
 */
@Composable
private fun SkinBaselineLine(nightsToGo: Int) {
    Text(
        text = pluralStringResource(R.plurals.settings_watch_wearos_skin_baseline_nights_to_go, nightsToGo, nightsToGo),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The inset these rows keep inside the card. */
private val SettingPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.xs)

/**
 * The watch's own settings, read from and written to the watch over the
 * link (`GET_PREF`, `SET_PREF`): the same switches its recording settings
 * have, each with the same line on what it does. The Samsung ones appear
 * only when the watch reports the Samsung platform. Each switch is one
 * setting; the watch answers with all of them again.
 */
@Composable
private fun WearOsWatchSettingsCard(
    settings: Map<String, String>?,
    busy: Boolean,
    error: ScreenError?,
    onLoad: () -> Unit,
    onSet: (key: String, value: String) -> Unit,
) {
    OpenWatchCard {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.sm)) {
            if (settings == null) {
                Text(
                    text = stringResource(if (error != null) R.string.watch_settings_unreachable else R.string.watch_settings_not_read),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.lg),
                )
                TextButton(onClick = onLoad, enabled = !busy, modifier = Modifier.padding(horizontal = Spacing.sm)) {
                    Text(stringResource(R.string.watch_settings_read))
                }
                return@Column
            }
            fun on(key: String) = settings[key] == Preferences.ON
            SwitchRow(
                title = stringResource(R.string.watch_settings_power_save),
                body = stringResource(R.string.watch_settings_power_save_body),
                checked = settings[Preferences.HEART_RATE_DAY] == "600",
                enabled = !busy,
                contentPadding = SettingPadding,
                onCheckedChange = { onSet(Preferences.HEART_RATE_DAY, if (it) "600" else "10") },
            )
            SwitchRow(
                title = stringResource(R.string.watch_settings_synced),
                body = stringResource(R.string.watch_settings_synced_body),
                checked = settings[Preferences.SLEEP_HOLD] == Preferences.SYNCED,
                enabled = !busy,
                contentPadding = SettingPadding,
                onCheckedChange = { onSet(Preferences.SLEEP_HOLD, if (it) Preferences.SYNCED else Preferences.LOCK) },
            )
            if (on(Preferences.SAMSUNG_AVAILABLE)) {
                Text(
                    text = stringResource(R.string.watch_settings_samsung_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                )
                SwitchRow(
                    title = stringResource(R.string.watch_settings_hr_samsung),
                    body = stringResource(R.string.watch_settings_hr_source_explained),
                    checked = settings[Preferences.HEART_RATE_SOURCE] == Preferences.SAMSUNG,
                    enabled = !busy,
                    contentPadding = SettingPadding,
                    onCheckedChange = { onSet(Preferences.HEART_RATE_SOURCE, if (it) Preferences.SAMSUNG else Preferences.SENSOR) },
                )
                SwitchRow(
                    title = stringResource(R.string.watch_settings_hrv_samsung),
                    body = stringResource(R.string.watch_settings_hrv_source_explained),
                    // The green PPG is the platform's too; only the watch can choose it, since it asks for a grant there.
                    checked = settings[Preferences.HRV_SOURCE] in setOf(Preferences.SAMSUNG, Preferences.SAMSUNG_PPG),
                    enabled = !busy,
                    contentPadding = SettingPadding,
                    onCheckedChange = { onSet(Preferences.HRV_SOURCE, if (it) Preferences.SAMSUNG else Preferences.SENSOR) },
                )
                SwitchRow(
                    title = stringResource(R.string.watch_settings_skin),
                    body = stringResource(
                        if (on(Preferences.SKIN_TEMPERATURE) && !on(Preferences.SKIN_GRANTED)) R.string.watch_settings_allow_on_watch else R.string.watch_settings_skin_explained,
                    ),
                    checked = on(Preferences.SKIN_TEMPERATURE),
                    enabled = !busy,
                    contentPadding = SettingPadding,
                    onCheckedChange = { onSet(Preferences.SKIN_TEMPERATURE, if (it) Preferences.ON else Preferences.OFF) },
                )
                SwitchRow(
                    title = stringResource(R.string.watch_settings_spo2),
                    body = stringResource(
                        if (on(Preferences.SPO2) && !on(Preferences.SPO2_GRANTED)) R.string.watch_settings_allow_on_watch else R.string.watch_settings_spo2_explained,
                    ),
                    checked = on(Preferences.SPO2),
                    enabled = !busy,
                    contentPadding = SettingPadding,
                    onCheckedChange = { onSet(Preferences.SPO2, if (it) Preferences.ON else Preferences.OFF) },
                )
            }
            if (error != null) {
                Text(
                    text = stringResource(R.string.watch_settings_unreachable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = Spacing.lg),
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun WearOsWatchPreview() {
    val watch = BleSensorDevice(
        id = "wearos-1",
        displayName = "Galaxy Watch8",
        address = "4A:F1:89:49:3C:85",
        bluetoothName = "Galaxy Watch8 (89FZ)",
        capabilities = emptySet(),
        enabled = true,
        wheelCircumferenceMm = null,
        addedAt = Instant.parse("2026-06-23T08:00:00Z"),
        kind = BleDeviceKind.WATCH,
        integration = DeviceIntegration.WEAROS,
        lastSyncedAt = Instant.now().minus(Duration.ofMinutes(40)),
    )
    OpenWatchTheme {
        WatchDeviceScreen(
            state = WatchDeviceUiState(
                device = watch,
                family = DeviceIntegration.WEAROS,
                features = setOf(DeviceFeature.SYNC, DeviceFeature.LIVE_HEART_RATE),
                autoSync = AutoSyncInterval.EVERY_2_HOURS,
                backgroundReadMissing = true,
            ),
            panel = wearOsWatchPanel(
                device = watch,
                state = WearOsWatchPanelUiState(
                    wearOsStatus = WearOsCompanionStatus(
                        isPaired = true,
                        appStatus = WearOsAppStatus.APP_RUNNING,
                        watchName = "Galaxy Watch8",
                    ),
                    watchSettings = mapOf(
                        Preferences.HEART_RATE_DAY to "10",
                        Preferences.SLEEP_HOLD to Preferences.SYNCED,
                        Preferences.SKIN_TEMPERATURE to Preferences.ON,
                    ),
                    skinNightsToBaseline = 2,
                ),
                liveHeartRateBpm = 71,
                sync = DeviceSyncUiState(),
                actions = WearOsWatchPanelActions(),
            ),
            onRename = {},
            onDetectSensors = {},
            onSelectAutoSync = {},
            onAllowBackgroundRead = {},
            onEnabledChange = {},
            onRemove = {},
        )
    }
}

private val WearOsStatusIconSize = 20.dp
private val WearOsButtonIconSize = 18.dp
private val WearOsProgressStroke = 2.dp
