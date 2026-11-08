package tech.mmarca.openwatch.wear

import android.content.Context
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Text
import tech.mmarca.openwatch.wear.samsung.SamsungHealthPlatform
import tech.mmarca.openwatch.wearlink.HeartRateInterval

/**
 * The watch's own settings ([WearSettings]) on the status screen: the heart
 * rate outside bedtime mode, and, on a watch with the Samsung Health
 * Platform only, where the heart rate and the HRV windows come from. Each
 * button switches between its two values; the running service follows at
 * once ([WearAppService.settingsChanged]).
 */
@Composable
internal fun WatchSettingsSection(context: Context) {
    val samsung = remember { SamsungHealthPlatform.isInstalled(context) }
    var day by remember { mutableStateOf(WearSettings.heartRateDay(context)) }
    var heartRateSource by remember { mutableStateOf(WearSettings.heartRateSource(context)) }
    var hrvSource by remember { mutableStateOf(WearSettings.hrvSource(context)) }
    var synced by remember { mutableStateOf(WearSettings.syncedSleepHold(context)) }

    val powerSave = day == HeartRateInterval.POWER_SAVE
    Toggle(stringResource(if (powerSave) R.string.settings_heart_rate_power_save else R.string.settings_heart_rate_every_10_seconds)) {
        day = if (powerSave) HeartRateInterval.EVERY_10_SECONDS else HeartRateInterval.POWER_SAVE
        WearSettings.setHeartRateDay(context, day)
        WearAppService.settingsChanged(context)
    }
    if (powerSave) Line(stringResource(R.string.settings_heart_rate_power_save_note))
    Toggle(stringResource(if (synced) R.string.settings_sleep_hold_synced else R.string.settings_sleep_hold_wake_lock)) {
        synced = !synced
        WearSettings.setSyncedSleepHold(context, synced)
        WearAppService.settingsChanged(context)
    }
    if (!samsung) return

    val heartRateSamsung = heartRateSource == WearSettings.Source.SAMSUNG_PLATFORM
    Toggle(stringResource(if (heartRateSamsung) R.string.settings_heart_rate_source_samsung else R.string.settings_heart_rate_source_sensor)) {
        heartRateSource = flip(heartRateSource)
        WearSettings.setHeartRateSource(context, heartRateSource)
        WearAppService.settingsChanged(context)
    }
    val hrvSamsung = hrvSource == WearSettings.Source.SAMSUNG_PLATFORM
    Toggle(stringResource(if (hrvSamsung) R.string.status_hrv_source_samsung else R.string.status_hrv_source_ppg)) {
        hrvSource = flip(hrvSource)
        WearSettings.setHrvSource(context, hrvSource)
        WearAppService.settingsChanged(context)
    }
    if (heartRateSamsung || hrvSamsung) Line(stringResource(R.string.status_hrv_source_samsung_note))
}

private fun flip(source: WearSettings.Source): WearSettings.Source =
    if (source == WearSettings.Source.SAMSUNG_PLATFORM) WearSettings.Source.RAW_SENSOR else WearSettings.Source.SAMSUNG_PLATFORM

@Composable
private fun Toggle(label: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.padding(top = 8.dp)) { Text(label) }
}
