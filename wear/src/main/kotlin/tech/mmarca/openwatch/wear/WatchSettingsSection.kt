package tech.mmarca.openwatch.wear

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
 * button switches between its values; the running service follows at
 * once ([WearAppService.settingsChanged]).
 *
 * What the platform offers differs by watch, so it is asked for its tracker
 * types: a switch or a measurement whose type it does not list is not shown.
 * Until it has answered, and when it does not, the switches this app always
 * had are shown and the newer ones are not.
 */
@Composable
fun WatchSettingsSection(context: Context) {
    val samsung = remember { SamsungHealthPlatform.isInstalled(context) }
    var types by remember { mutableStateOf<Set<String>?>(null) }
    LaunchedEffect(samsung) {
        if (samsung) SamsungHealthPlatform.supportedTypes(context, Handler(Looper.getMainLooper())) { types = it }
    }
    fun has(type: String, whenUnknown: Boolean) = types?.contains(type) ?: whenUnknown
    var day by remember { mutableStateOf(WearSettings.heartRateDay(context)) }
    var heartRateSource by remember { mutableStateOf(WearSettings.heartRateSource(context)) }
    var hrvSource by remember { mutableStateOf(WearSettings.hrvSource(context)) }
    var skin by remember { mutableStateOf(WearSettings.skinTemperature(context)) }
    var spo2 by remember { mutableStateOf(WearSettings.spo2AtNight(context)) }
    var autoPause by remember { mutableStateOf(WearSettings.autoPause(context)) }
    var autoDetect by remember { mutableStateOf(WearSettings.autoDetect(context)) }

    val powerSave = day == HeartRateInterval.POWER_SAVE
    Toggle(stringResource(if (powerSave) R.string.settings_heart_rate_power_save else R.string.settings_heart_rate_every_10_seconds)) {
        day = if (powerSave) HeartRateInterval.EVERY_10_SECONDS else HeartRateInterval.POWER_SAVE
        WearSettings.setHeartRateDay(context, day)
        WearAppService.settingsChanged(context)
    }
    Line(stringResource(if (powerSave) R.string.settings_power_save_on_explained else R.string.settings_power_save_off_explained))
    Toggle(stringResource(if (autoPause) R.string.settings_auto_pause_on else R.string.settings_auto_pause_off)) {
        autoPause = !autoPause
        WearSettings.setAutoPause(context, autoPause)
    }
    Toggle(stringResource(if (autoDetect) R.string.settings_auto_detect_on else R.string.settings_auto_detect_off)) {
        autoDetect = !autoDetect
        WearSettings.setAutoDetect(context, autoDetect)
    }
    if (!samsung) return

    val heartRateSamsung = heartRateSource == WearSettings.Source.SAMSUNG_PLATFORM
    Toggle(stringResource(if (heartRateSamsung) R.string.settings_heart_rate_source_samsung else R.string.settings_heart_rate_source_sensor)) {
        heartRateSource = flip(heartRateSource)
        WearSettings.setHeartRateSource(context, heartRateSource)
        WearAppService.settingsChanged(context)
    }
    Line(stringResource(R.string.settings_heart_rate_source_explained))
    val hrvSamsung = hrvSource != WearSettings.Source.RAW_SENSOR
    val hasPpg = has(SamsungHealthPlatform.TRACKER_PPG, whenUnknown = false)
    fun setHrv(source: WearSettings.Source) {
        hrvSource = source
        WearSettings.setHrvSource(context, source)
        WearAppService.settingsChanged(context)
    }
    // The platform's PPG needs its own permission; refused, the choice goes back to the sensor.
    val askPpg = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        setHrv(if (granted) WearSettings.Source.SAMSUNG_PPG else WearSettings.Source.RAW_SENSOR)
    }
    Toggle(
        stringResource(
            when (hrvSource) {
                WearSettings.Source.RAW_SENSOR -> R.string.status_hrv_source_ppg
                WearSettings.Source.SAMSUNG_PLATFORM -> R.string.status_hrv_source_samsung
                WearSettings.Source.SAMSUNG_PPG -> R.string.status_hrv_source_samsung_ppg
            },
        ),
    ) {
        when {
            hrvSource == WearSettings.Source.RAW_SENSOR -> setHrv(WearSettings.Source.SAMSUNG_PLATFORM)
            hrvSource == WearSettings.Source.SAMSUNG_PLATFORM && hasPpg ->
                if (WearPermissions.hasAdditionalHealthData(context)) {
                    setHrv(WearSettings.Source.SAMSUNG_PPG)
                } else {
                    askPpg.launch(WearPermissions.additionalHealthData)
                }
            else -> setHrv(WearSettings.Source.RAW_SENSOR)
        }
    }
    Line(stringResource(R.string.settings_hrv_source_explained))
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
    if (has(SamsungHealthPlatform.TRACKER_SKIN_TEMPERATURE, whenUnknown = true)) {
        Toggle(stringResource(if (skin) R.string.settings_skin_temperature_on else R.string.settings_skin_temperature_off)) {
            if (!skin && !WearPermissions.hasSkinTemperature(context)) {
                askSkin.launch(WearPermissions.skinTemperature)
            } else {
                skin = !skin
                WearSettings.setSkinTemperature(context, skin)
                WearAppService.settingsChanged(context)
            }
        }
        Line(stringResource(R.string.settings_skin_explained))
    }
    if (has(SamsungHealthPlatform.TRACKER_SPO2, whenUnknown = true)) {
        Toggle(stringResource(if (spo2) R.string.settings_spo2_on else R.string.settings_spo2_off)) {
            if (!spo2 && !WearPermissions.hasOxygenSaturation(context)) {
                askSpo2.launch(WearPermissions.oxygenSaturation)
            } else {
                spo2 = !spo2
                WearSettings.setSpo2AtNight(context, spo2)
                WearAppService.settingsChanged(context)
            }
        }
        Line(stringResource(R.string.settings_spo2_explained))
    }
    val hasEcg = has(SamsungHealthPlatform.TRACKER_ECG, whenUnknown = false)
    val hasBia = has(SamsungHealthPlatform.TRACKER_BIA, whenUnknown = false)
    if (hasEcg) Toggle(stringResource(R.string.ecg_open)) { OpenMeasurement.current = OpenMeasurement.Kind.ECG }
    if (hasBia) Toggle(stringResource(R.string.bia_open)) { OpenMeasurement.current = OpenMeasurement.Kind.BIA }
    if (heartRateSamsung || hrvSamsung || skin || spo2 || hasEcg || hasBia) Line(stringResource(R.string.status_hrv_source_samsung_note))
}

private fun flip(source: WearSettings.Source): WearSettings.Source =
    if (source == WearSettings.Source.SAMSUNG_PLATFORM) WearSettings.Source.RAW_SENSOR else WearSettings.Source.SAMSUNG_PLATFORM

@Composable
private fun Toggle(label: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.padding(top = 8.dp)) { Text(label) }
}
