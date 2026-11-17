package tech.mmarca.openwatch.features.watches

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import tech.mmarca.openwatch.R
import tech.mmarca.openwatch.devices.wearos.WatchGoals
import tech.mmarca.openwatch.core.presentation.ScreenError
import tech.mmarca.openwatch.ui.components.OpenWatchCard
import tech.mmarca.openwatch.ui.theme.Spacing
import tech.mmarca.openwatch.wearlink.WearLinkPreferences as Preferences

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
                body = stringResource(R.string.watch_settings_power_save_explained),
                checked = settings[Preferences.HEART_RATE_DAY] == "600",
                enabled = !busy,
            ) { onSet(Preferences.HEART_RATE_DAY, if (it) "600" else "10") }
            // An older watch app reports no goals and would refuse them.
            if (settings.containsKey(Preferences.GOAL_STEPS)) {
                Text(
                    text = stringResource(R.string.watch_settings_goals_explained),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                )
                GoalField(stringResource(R.string.watch_settings_goal_steps), Preferences.GOAL_STEPS, settings, allowNone = true, enabled = !busy, onSet)
                GoalField(stringResource(R.string.watch_settings_goal_active_kcal), Preferences.GOAL_ACTIVE_KCAL, settings, allowNone = true, enabled = !busy, onSet)
                GoalField(stringResource(R.string.watch_settings_goal_water_ml), Preferences.GOAL_WATER_ML, settings, allowNone = false, enabled = !busy, onSet)
            }
            if (on(Preferences.SAMSUNG_AVAILABLE)) {
                Text(
                    text = stringResource(R.string.watch_settings_samsung_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                )
                SettingSwitch(
                    title = stringResource(R.string.watch_settings_hr_samsung),
                    body = stringResource(R.string.watch_settings_hr_source_explained),
                    checked = settings[Preferences.HEART_RATE_SOURCE] == Preferences.SAMSUNG,
                    enabled = !busy,
                ) { onSet(Preferences.HEART_RATE_SOURCE, if (it) Preferences.SAMSUNG else Preferences.SENSOR) }
                SettingSwitch(
                    title = stringResource(R.string.watch_settings_hrv_samsung),
                    // The platform's PPG is chosen on the watch, where its permission is asked; shown here, not offered.
                    body = stringResource(
                        if (settings[Preferences.HRV_SOURCE] == Preferences.SAMSUNG_PPG) {
                            R.string.watch_settings_hrv_samsung_ppg
                        } else {
                            R.string.watch_settings_hrv_source_explained
                        },
                    ),
                    checked = settings[Preferences.HRV_SOURCE] in setOf(Preferences.SAMSUNG, Preferences.SAMSUNG_PPG),
                    enabled = !busy,
                ) { onSet(Preferences.HRV_SOURCE, if (it) Preferences.SAMSUNG else Preferences.SENSOR) }
                SettingSwitch(
                    title = stringResource(R.string.watch_settings_skin),
                    body = if (on(Preferences.SKIN_TEMPERATURE) && !on(Preferences.SKIN_GRANTED)) {
                        stringResource(R.string.watch_settings_allow_on_watch)
                    } else {
                        stringResource(R.string.watch_settings_skin_explained)
                    },
                    checked = on(Preferences.SKIN_TEMPERATURE),
                    enabled = !busy,
                ) { onSet(Preferences.SKIN_TEMPERATURE, if (it) Preferences.ON else Preferences.OFF) }
                SettingSwitch(
                    title = stringResource(R.string.watch_settings_spo2),
                    body = if (on(Preferences.SPO2) && !on(Preferences.SPO2_GRANTED)) {
                        stringResource(R.string.watch_settings_allow_on_watch)
                    } else {
                        stringResource(R.string.watch_settings_spo2_explained)
                    },
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

/**
 * One daily goal as the watch holds it (`0` for none): a number to type and a button to send it.
 * An empty field sends `0` where [allowNone] says the goal may be switched off; a number outside
 * the goal's range cannot be sent.
 */
@Composable
private fun GoalField(
    title: String,
    key: String,
    settings: Map<String, String>,
    allowNone: Boolean,
    enabled: Boolean,
    onSet: (String, String) -> Unit,
) {
    val held = settings[key]
    val shown = held?.takeIf { it != "0" }.orEmpty()
    var text by remember(held) { mutableStateOf(shown) }
    val value = text.ifEmpty { if (allowNone) "0" else null }?.takeIf { WatchGoals.toSetting(key, it) != null }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.xs),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.filter(Char::isDigit).take(MAX_GOAL_DIGITS) },
            label = { Text(title) },
            placeholder = if (allowNone) ({ Text(stringResource(R.string.watch_settings_goal_none)) }) else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { value?.let { onSet(key, it) } }, enabled = enabled && value != null && text != shown) {
            Text(stringResource(R.string.action_save))
        }
    }
}

private const val MAX_GOAL_DIGITS = 6

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
