package tech.mmarca.openwatch.features.watches

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import tech.mmarca.openwatch.R
import tech.mmarca.openwatch.core.presentation.ScreenError
import tech.mmarca.openwatch.ui.components.OpenWatchCard
import tech.mmarca.openwatch.ui.theme.Spacing
import tech.mmarca.openwatch.wearlink.WearLinkProtocol.Preferences

/**
 * The watch's own settings, read from and written to the watch over the
 * link (`GET_PREF`, `SET_PREF`): the same switches its status screen has.
 * The Samsung ones appear only when the watch reports the Samsung platform.
 * Each switch is one setting; the watch answers with all of them again.
 */
/** The watch's own settings on its device screen, for a Wear OS watch only; a Garmin watch has its own. */
@Composable
internal fun WearOsWatchSettingsSection(state: WatchDeviceUiState, onLoad: () -> Unit, onSet: (key: String, value: String) -> Unit) {
    if (state.device?.isWearosWatch != true) return
    SectionHeader(stringResource(R.string.watch_settings_section))
    WearOsWatchSettingsCard(
        settings = state.watchSettings,
        busy = state.isWatchSettingsBusy,
        error = state.watchSettingsError,
        onLoad = onLoad,
        onSet = onSet,
    )
}

@Composable
internal fun WearOsWatchSettingsCard(
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
            SettingSwitch(
                title = stringResource(R.string.watch_settings_power_save),
                body = stringResource(R.string.watch_settings_power_save_body),
                checked = settings[Preferences.HEART_RATE_DAY] == "600",
                enabled = !busy,
            ) { onSet(Preferences.HEART_RATE_DAY, if (it) "600" else "10") }
            SettingSwitch(
                title = stringResource(R.string.watch_settings_synced),
                body = stringResource(R.string.watch_settings_synced_body),
                checked = settings[Preferences.SLEEP_HOLD] == Preferences.SYNCED,
                enabled = !busy,
            ) { onSet(Preferences.SLEEP_HOLD, if (it) Preferences.SYNCED else Preferences.LOCK) }
            if (on(Preferences.SAMSUNG_AVAILABLE)) {
                Text(
                    text = stringResource(R.string.watch_settings_samsung_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                )
                SettingSwitch(
                    title = stringResource(R.string.watch_settings_hr_samsung),
                    body = null,
                    checked = settings[Preferences.HEART_RATE_SOURCE] == Preferences.SAMSUNG,
                    enabled = !busy,
                ) { onSet(Preferences.HEART_RATE_SOURCE, if (it) Preferences.SAMSUNG else Preferences.SENSOR) }
                SettingSwitch(
                    title = stringResource(R.string.watch_settings_hrv_samsung),
                    body = null,
                    checked = settings[Preferences.HRV_SOURCE] == Preferences.SAMSUNG,
                    enabled = !busy,
                ) { onSet(Preferences.HRV_SOURCE, if (it) Preferences.SAMSUNG else Preferences.SENSOR) }
                SettingSwitch(
                    title = stringResource(R.string.watch_settings_skin),
                    body = stringResource(R.string.watch_settings_allow_on_watch).takeIf { on(Preferences.SKIN_TEMPERATURE) && !on(Preferences.SKIN_GRANTED) },
                    checked = on(Preferences.SKIN_TEMPERATURE),
                    enabled = !busy,
                ) { onSet(Preferences.SKIN_TEMPERATURE, if (it) Preferences.ON else Preferences.OFF) }
                SettingSwitch(
                    title = stringResource(R.string.watch_settings_spo2),
                    body = stringResource(R.string.watch_settings_allow_on_watch).takeIf { on(Preferences.SPO2) && !on(Preferences.SPO2_GRANTED) },
                    checked = on(Preferences.SPO2),
                    enabled = !busy,
                ) { onSet(Preferences.SPO2, if (it) Preferences.ON else Preferences.OFF) }
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

@Composable
private fun SettingSwitch(title: String, body: String?, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.xs),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (body != null) {
                Text(text = body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
