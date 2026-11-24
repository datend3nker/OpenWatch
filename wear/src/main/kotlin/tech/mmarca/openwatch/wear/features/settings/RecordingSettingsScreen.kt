package tech.mmarca.openwatch.wear.features.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import tech.mmarca.openwatch.wear.MeasurementSource
import tech.mmarca.openwatch.wear.R
import tech.mmarca.openwatch.wear.WatchPreferences
import tech.mmarca.openwatch.wear.WearPermissions
import tech.mmarca.openwatch.wear.WearSettings
import tech.mmarca.openwatch.wear.ui.preview.WearPreviews
import tech.mmarca.openwatch.wear.ui.theme.OpenWatchWearTheme
import tech.mmarca.openwatch.wearlink.HeartRateInterval

/** The recording settings' buttons: each switches its setting between two values. */
internal class RecordingSettingsActions(
    val onHeartRatePowerSave: (Boolean) -> Unit,
    val onSyncedSleepHold: (Boolean) -> Unit,
    val onHeartRateSource: (WearSettings.Source) -> Unit,
    val onHrvSource: (WearSettings.Source) -> Unit,
    /** Switches skin temperature on or off; on asks for the platform's grant first. */
    val onSkinTemperature: (Boolean) -> Unit,
    val onSpo2AtNight: (Boolean) -> Unit,
    val onAutoPause: (Boolean) -> Unit = {},
    val onAutoDetect: (Boolean) -> Unit = {},
)

/**
 * The recording settings, `recording_settings`, which Settings opens: the
 * screen over its [RecordingSettingsViewModel]. Switching skin temperature or
 * SpO2 on asks for the permission the Samsung platform checks; without it the
 * setting stays off.
 */
@Composable
internal fun RecordingSettingsRoute() {
    val context = LocalContext.current
    val viewModel = viewModel { RecordingSettingsViewModel.create(context) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val askSkin = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.setSkinTemperature(true)
    }
    val askSpo2 = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.setSpo2AtNight(true)
    }
    // Refused, the green PPG is passed over and the cycle goes on to the raw sensor.
    val askPpg = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.setHrvSource(if (granted) WearSettings.Source.SAMSUNG_PPG else WearSettings.Source.RAW_SENSOR)
    }
    val actions = remember(viewModel, askSkin, askSpo2, askPpg) {
        RecordingSettingsActions(
            onHeartRatePowerSave = viewModel::setHeartRatePowerSave,
            onSyncedSleepHold = viewModel::setSyncedSleepHold,
            onHeartRateSource = viewModel::setHeartRateSource,
            onHrvSource = { source ->
                if (source == WearSettings.Source.SAMSUNG_PPG && !WearPermissions.hasAdditionalHealthData(context)) {
                    askPpg.launch(WearPermissions.additionalHealthData)
                } else {
                    viewModel.setHrvSource(source)
                }
            },
            onSkinTemperature = { on ->
                if (on && !WearPermissions.hasSkinTemperature(context)) askSkin.launch(WearPermissions.skinTemperature) else viewModel.setSkinTemperature(on)
            },
            onSpo2AtNight = { on ->
                if (on && !WearPermissions.hasOxygenSaturation(context)) askSpo2.launch(WearPermissions.oxygenSaturation) else viewModel.setSpo2AtNight(on)
            },
            onAutoPause = viewModel::setAutoPause,
            onAutoDetect = viewModel::setAutoDetect,
        )
    }
    RecordingSettingsScreen(state, actions)
}

/**
 * The watch's own recording settings ([WearSettings]), each with a line on
 * what it does, what it costs and what it loses: the heart rate outside
 * bedtime mode and how a sleep hold works, a workout's pause by itself and
 * the offer of a walk or a run the watch sees, and, on a watch with the
 * Samsung Health Platform only, where the heart rate and the HRV windows
 * come from, skin temperature and SpO2. The running service follows a change at once,
 * and a change the phone makes shows at once too.
 */
@Composable
internal fun RecordingSettingsScreen(state: RecordingSettingsUiState, actions: RecordingSettingsActions) {
    val listState = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
            item {
                ListHeader(
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                    transformation = SurfaceTransformation(spec),
                ) { Text(stringResource(R.string.settings_recording)) }
            }
            state.settings?.let { settings -> recordingSettingsItems(settings, state.samsung, spec, actions) }
        }
    }
}

private fun TransformingLazyColumnScope.recordingSettingsItems(
    settings: WatchPreferences,
    samsung: Boolean,
    spec: TransformationSpec,
    actions: RecordingSettingsActions,
) {
    val powerSave = settings.heartRateDay == HeartRateInterval.POWER_SAVE
    setting(
        spec,
        if (powerSave) R.string.settings_heart_rate_power_save else R.string.settings_heart_rate_every_10_seconds,
        if (powerSave) R.string.settings_heart_rate_power_save_note else R.string.settings_heart_rate_every_10_seconds_explained,
    ) { actions.onHeartRatePowerSave(!powerSave) }
    val synced = settings.syncedSleepHold
    setting(spec, if (synced) R.string.settings_sleep_hold_synced else R.string.settings_sleep_hold_wake_lock, R.string.settings_sleep_hold_explained) {
        actions.onSyncedSleepHold(!synced)
    }
    val autoPause = settings.autoPause
    setting(spec, if (autoPause) R.string.settings_auto_pause_on else R.string.settings_auto_pause_off, R.string.settings_auto_pause_explained) {
        actions.onAutoPause(!autoPause)
    }
    val autoDetect = settings.autoDetect
    setting(spec, if (autoDetect) R.string.settings_auto_detect_on else R.string.settings_auto_detect_off, R.string.settings_auto_detect_explained) {
        actions.onAutoDetect(!autoDetect)
    }
    if (!samsung) return

    item { Line(stringResource(R.string.status_hrv_source_samsung_note)) }
    val heartRateSamsung = settings.heartRateSource == MeasurementSource.SAMSUNG_PLATFORM
    setting(
        spec,
        if (heartRateSamsung) R.string.settings_heart_rate_source_samsung else R.string.settings_heart_rate_source_sensor,
        R.string.settings_heart_rate_source_explained,
    ) { actions.onHeartRateSource(flip(settings.heartRateSource)) }
    val hrvLabel = when (settings.hrvSource) {
        MeasurementSource.RAW_SENSOR -> R.string.status_hrv_source_ppg
        MeasurementSource.SAMSUNG_PLATFORM -> R.string.status_hrv_source_samsung
        MeasurementSource.SAMSUNG_PPG -> R.string.status_hrv_source_samsung_ppg
    }
    setting(spec, hrvLabel, R.string.settings_hrv_source_explained) {
        actions.onHrvSource(nextHrvSource(settings.hrvSource))
    }
    val skin = settings.skinTemperature
    setting(spec, if (skin) R.string.settings_skin_temperature_on else R.string.settings_skin_temperature_off, R.string.settings_skin_explained) {
        actions.onSkinTemperature(!skin)
    }
    val spo2 = settings.spo2AtNight
    setting(spec, if (spo2) R.string.settings_spo2_on else R.string.settings_spo2_off, R.string.settings_spo2_explained) {
        actions.onSpo2AtNight(!spo2)
    }
}

/** One setting: a button that shows how it stands and switches it, and a line on what it does. */
private fun TransformingLazyColumnScope.setting(spec: TransformationSpec, @StringRes label: Int, @StringRes explained: Int, onClick: () -> Unit) {
    item {
        Button(
            onClick = onClick,
            label = { Text(stringResource(label)) },
            colors = ButtonDefaults.filledTonalButtonColors(),
            modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
            transformation = SurfaceTransformation(spec),
        )
    }
    item { Line(stringResource(explained)) }
}

@Composable
private fun Line(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

private fun flip(source: WearSettings.Source): WearSettings.Source =
    if (source == WearSettings.Source.SAMSUNG_PLATFORM) WearSettings.Source.RAW_SENSOR else WearSettings.Source.SAMSUNG_PLATFORM

/** The HRV's three sources in turn: the raw PPG, the platform's intervals, the platform's green PPG. */
internal fun nextHrvSource(source: WearSettings.Source): WearSettings.Source = when (source) {
    WearSettings.Source.RAW_SENSOR -> WearSettings.Source.SAMSUNG_PLATFORM
    WearSettings.Source.SAMSUNG_PLATFORM -> WearSettings.Source.SAMSUNG_PPG
    WearSettings.Source.SAMSUNG_PPG -> WearSettings.Source.RAW_SENSOR
}

@WearPreviews
@Composable
private fun RecordingSettingsScreenPreview() {
    val settings = WatchPreferences(
        heartRateDay = HeartRateInterval.EVERY_10_SECONDS,
        heartRateSource = MeasurementSource.RAW_SENSOR,
        hrvSource = MeasurementSource.RAW_SENSOR,
        syncedSleepHold = true,
        skinTemperature = true,
        spo2AtNight = false,
    )
    OpenWatchWearTheme {
        AppScaffold {
            RecordingSettingsScreen(
                RecordingSettingsUiState(settings, samsung = true),
                RecordingSettingsActions({}, {}, {}, {}, {}, {}),
            )
        }
    }
}
