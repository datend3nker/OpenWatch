package tech.mmarca.openwatch.features.watches

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.mmarca.openwatch.R
import tech.mmarca.openwatch.devices.core.sync.AutoSyncInterval
import tech.mmarca.openwatch.domain.model.BleSensorDevice
import tech.mmarca.openwatch.ui.components.DeclareAppBar
import tech.mmarca.openwatch.ui.components.DeviceAvatar
import tech.mmarca.openwatch.ui.components.formatDeviceTime
import tech.mmarca.openwatch.ui.components.InlineGrantPrompt
import tech.mmarca.openwatch.ui.components.OpenWatchCard
import tech.mmarca.openwatch.ui.components.rememberHealthConnectPermissionLauncher
import tech.mmarca.openwatch.ui.theme.Spacing
import tech.mmarca.openwatch.ui.components.OpenWatchIconButton
import tech.mmarca.openwatch.ui.components.ScreenAppBar

/**
 * One watch, and everything about it. Order: status, actions, configuration,
 * with removal at the bottom where a mis-tap cannot reach it.
 */
@Composable
fun WatchDeviceScreen(
    viewModel: WatchDeviceViewModel,
    onRemoved: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val device = state.device

    DeclareAppBar(ScreenAppBar(title = device?.displayName))
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshBackgroundAccess() }
    val backgroundAccessLauncher = rememberHealthConnectPermissionLauncher(
        onResult = viewModel::refreshBackgroundAccess,
    )
    // A sync right after a grant writes what it now may, and replaces the card's "not written" line. Not after a
    // refusal: that would hold the watch link for a run that ends where it began.
    val syncGrantLauncher = rememberHealthConnectPermissionLauncher(onGranted = viewModel::syncNow)

    if (device == null) {
        // Removed while this screen was open, or opened from a stale entry.
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.no_data))
        }
        return
    }

    // Nearby devices (API 31+): without it the phone cannot see the bond or open the link.
    val bluetoothPermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) viewModel.checkWearOsStatus() }

    var showRenameDialog by rememberSaveable { mutableStateOf(false) }
    var showRemoveDialog by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        StatusCard(
            device = device,
            onRename = { showRenameDialog = true },
        )

        val liveHeartRateBpm by viewModel.wearOsLiveHeartRateBpm.collectAsStateWithLifecycle()
        SectionHeader(stringResource(R.string.settings_watch_wearos_status_section))
        WearOsStatusCard(
            status = state.wearOsStatus,
            error = state.wearOsError,
            isChecking = state.isCheckingWearOsStatus,
            onCheck = viewModel::checkWearOsStatus,
            onGrantBluetooth = {
                bluetoothPermission.launch(android.Manifest.permission.BLUETOOTH_CONNECT)
            },
            liveHeartRateBpm = liveHeartRateBpm,
        )
        SectionHeader(stringResource(R.string.settings_watch_wearos_sync_section))
        WearOsSyncCard(device, state.sync, onSync = viewModel::syncNow, onAllowGrants = syncGrantLauncher.launch)

        SectionHeader(stringResource(R.string.watch_settings_section))
        WearOsWatchSettingsCard(
            settings = state.watchSettings,
            busy = state.isWatchSettingsBusy,
            error = state.watchSettingsError,
            onLoad = viewModel::loadWatchSettings,
            onSet = viewModel::setWatchSetting,
        )

        SectionHeader(stringResource(R.string.settings_watch_section_device))
        // First: the schedule decides whether this screen is ever visited again.
        AutoSyncCard(
            selected = state.autoSync,
            backgroundReadMissing = state.backgroundReadMissing,
            onSelect = { interval ->
                // Switching it on is the moment to ask: from now on syncs run with the app closed.
                val switchingOn = state.autoSync == AutoSyncInterval.OFF && interval != AutoSyncInterval.OFF
                viewModel.setAutoSync(interval)
                if (switchingOn && state.backgroundReadMissing) {
                    backgroundAccessLauncher.launch(setOf(viewModel.backgroundReadPermission))
                }
            },
            onAllowBackgroundRead = {
                backgroundAccessLauncher.launch(setOf(viewModel.backgroundReadPermission))
            },
        )
        OpenWatchCard {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            ) {
                Text(
                    text = stringResource(R.string.settings_watch_enabled),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = device.enabled,
                    onCheckedChange = { viewModel.setEnabled(it) },
                )
            }
        }
        // Last, and its own card: a destructive action wants distance.
        OpenWatchCard {
            Text(
                text = stringResource(R.string.settings_watch_remove),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showRemoveDialog = true }
                    .padding(Spacing.lg),
            )
        }
    }

    if (showRenameDialog) {
        RenameDialog(
            initialName = device.displayName,
            onSave = { name ->
                viewModel.rename(name)
                showRenameDialog = false
            },
            onDismiss = { showRenameDialog = false },
        )
    }
    if (showRemoveDialog) {
        ConfirmRemoveWatchDialog(
            deviceName = device.displayName,
            onConfirm = {
                showRemoveDialog = false
                viewModel.removeDevice()
                onRemoved()
            },
            onDismiss = { showRemoveDialog = false },
        )
    }
}

@Composable
private fun StatusCard(
    device: BleSensorDevice,
    onRename: () -> Unit,
) {
    // A watch never synced is named by what it advertises.
    val statusLine = device.lastSyncedAt
        ?.let { stringResource(R.string.settings_watch_last_synced, formatDeviceTime(it)) }
        ?: device.bluetoothName
        ?: device.address

    OpenWatchCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
        ) {
            DeviceAvatar(size = 44)
            Spacer(modifier = Modifier.width(WatchRowIconGap))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (device.enabled) {
                        stringResource(R.string.settings_watch_connected)
                    } else {
                        stringResource(R.string.settings_watch_not_connected)
                    },
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
                Text(
                    text = statusLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            device.batteryPercent?.let { battery ->
                Spacer(modifier = Modifier.width(Spacing.md))
                Text(
                    text = "$battery%",
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
            }
            OpenWatchIconButton(onClick = onRename) {
                Icon(
                    imageVector = Icons.Outlined.Edit,
                    contentDescription = stringResource(R.string.settings_watch_rename),
                )
            }
        }
    }
}

/**
 * How often the watch syncs on its own. A segmented row, so the choice in
 * force is visible at a glance. The body says the watch must be in range and
 * Android picks the moment, or a fifteen-minute drift reads as broken.
 */
@Composable
private fun AutoSyncCard(
    selected: AutoSyncInterval,
    backgroundReadMissing: Boolean,
    onSelect: (AutoSyncInterval) -> Unit,
    onAllowBackgroundRead: () -> Unit,
) {
    OpenWatchCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        ) {
            Text(
                text = stringResource(R.string.settings_watch_auto_sync),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(R.string.settings_watch_auto_sync_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.sm),
            ) {
                AutoSyncInterval.entries.forEachIndexed { index, interval ->
                    SegmentedButton(
                        selected = selected == interval,
                        onClick = { onSelect(interval) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = AutoSyncInterval.entries.size,
                        ),
                        label = {
                            Text(
                                text = stringResource(
                                    when (interval) {
                                        AutoSyncInterval.OFF ->
                                            R.string.settings_watch_auto_sync_off
                                        AutoSyncInterval.EVERY_30_MINUTES ->
                                            R.string.settings_watch_auto_sync_30m
                                        AutoSyncInterval.HOURLY ->
                                            R.string.settings_watch_auto_sync_1h
                                        AutoSyncInterval.EVERY_2_HOURS ->
                                            R.string.settings_watch_auto_sync_2h
                                    },
                                ),
                            )
                        },
                    )
                }
            }
            if (selected != AutoSyncInterval.OFF && backgroundReadMissing) {
                InlineGrantPrompt(
                    message = stringResource(R.string.settings_watch_auto_sync_background_access),
                    actionLabel = stringResource(R.string.settings_watch_auto_sync_background_access_action),
                    onGrant = onAllowBackgroundRead,
                    modifier = Modifier.padding(top = Spacing.sm),
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(WatchChipGap))
        HorizontalDivider(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun RenameDialog(
    initialName: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_watch_rename)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.settings_watch_name_label)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(name) }) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

/** Gap inside a status chip. */
private val WatchChipGap = 10.dp
/** Gap between a row's leading icon and its text. */
private val WatchRowIconGap = 14.dp
