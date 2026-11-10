package tech.mmarca.openwatch.wear

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
fun WatchSettingsSection(context: Context) {
    val samsung = remember { SamsungHealthPlatform.isInstalled(context) }
    var day by remember { mutableStateOf(WearSettings.heartRateDay(context)) }
    var heartRateSource by remember { mutableStateOf(WearSettings.heartRateSource(context)) }
    var hrvSource by remember { mutableStateOf(WearSettings.hrvSource(context)) }
    var synced by remember { mutableStateOf(WearSettings.syncedSleepHold(context)) }
    var skin by remember { mutableStateOf(WearSettings.skinTemperature(context)) }
    var spo2 by remember { mutableStateOf(WearSettings.spo2AtNight(context)) }

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
    // Switching on asks for the permission the platform checks; the switch stays off without it.
    val askSkin = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        skin = granted
        WearSettings.setSkinTemperature(context, granted)
        WearAppService.settingsChanged(context)
    }
    val askSpo2 = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        spo2 = granted
        WearSettings.setSpo2AtNight(context, granted)
        WearAppService.settingsChanged(context)
    }
    Toggle(stringResource(if (skin) R.string.settings_skin_temperature_on else R.string.settings_skin_temperature_off)) {
        if (!skin && !WearPermissions.hasSkinTemperature(context)) {
            askSkin.launch(WearPermissions.skinTemperature)
        } else {
            skin = !skin
            WearSettings.setSkinTemperature(context, skin)
            WearAppService.settingsChanged(context)
        }
    }
    Toggle(stringResource(if (spo2) R.string.settings_spo2_on else R.string.settings_spo2_off)) {
        if (!spo2 && !WearPermissions.hasOxygenSaturation(context)) {
            askSpo2.launch(WearPermissions.oxygenSaturation)
        } else {
            spo2 = !spo2
            WearSettings.setSpo2AtNight(context, spo2)
            WearAppService.settingsChanged(context)
        }
    }
    if (heartRateSamsung || hrvSamsung || skin || spo2) Line(stringResource(R.string.status_hrv_source_samsung_note))
}

private fun flip(source: WearSettings.Source): WearSettings.Source =
    if (source == WearSettings.Source.SAMSUNG_PLATFORM) WearSettings.Source.RAW_SENSOR else WearSettings.Source.SAMSUNG_PLATFORM

@Composable
private fun Toggle(label: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.padding(top = 8.dp)) { Text(label) }
}
