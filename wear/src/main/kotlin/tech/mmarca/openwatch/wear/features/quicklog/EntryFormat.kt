package tech.mmarca.openwatch.wear.features.quicklog

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.Bloodtype
import androidx.compose.material.icons.outlined.Opacity
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.MonitorWeight
import androidx.compose.material.icons.outlined.SelfImprovement
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import kotlin.math.roundToInt
import tech.mmarca.openwatch.wear.R
import tech.mmarca.openwatch.wear.UnitSystem
import tech.mmarca.openwatch.wear.MetricTile
import tech.mmarca.openwatch.wear.ui.components.formatCount
import tech.mmarca.openwatch.wear.ui.components.formatMetricValue
import tech.mmarca.openwatch.wear.ui.components.metricUnitLabel
import tech.mmarca.openwatch.wear.ui.theme.ActiveCaloriesColor
import tech.mmarca.openwatch.wear.ui.theme.HeartColor
import tech.mmarca.openwatch.wear.ui.theme.HydrationColor
import tech.mmarca.openwatch.wear.ui.theme.MindfulnessColor
import tech.mmarca.openwatch.wear.ui.theme.VitalsColor
import tech.mmarca.openwatch.wear.ui.theme.WeightColor

val EntryType.icon: ImageVector
    get() = when (this) {
        EntryType.WATER -> Icons.Outlined.WaterDrop
        EntryType.WEIGHT -> Icons.Outlined.MonitorWeight
        EntryType.BLOOD_PRESSURE -> Icons.Outlined.Speed
        EntryType.MINDFULNESS -> Icons.Outlined.SelfImprovement
        EntryType.HEART_RATE -> Icons.Outlined.Favorite
        EntryType.HRV -> Icons.Outlined.Insights
        EntryType.BODY_FAT -> Icons.Outlined.MonitorWeight
        EntryType.BLOOD_OXYGEN -> Icons.Outlined.Bloodtype
        EntryType.RESPIRATORY_RATE -> Icons.Outlined.Air
        EntryType.BODY_TEMPERATURE -> Icons.Outlined.Thermostat
        EntryType.BLOOD_GLUCOSE -> Icons.Outlined.Opacity
        EntryType.HEIGHT -> Icons.Outlined.Straighten
    }

val EntryType.color: Color
    get() = when (this) {
        EntryType.WATER -> HydrationColor
        EntryType.WEIGHT -> WeightColor
        EntryType.BLOOD_PRESSURE -> VitalsColor
        EntryType.MINDFULNESS -> MindfulnessColor
        EntryType.HEART_RATE -> HeartColor
        EntryType.HRV -> VitalsColor
        EntryType.BODY_FAT -> WeightColor
        EntryType.BLOOD_OXYGEN -> VitalsColor
        EntryType.RESPIRATORY_RATE -> VitalsColor
        EntryType.BODY_TEMPERATURE -> ActiveCaloriesColor
        EntryType.BLOOD_GLUCOSE -> ActiveCaloriesColor
        EntryType.HEIGHT -> WeightColor
    }

/** The entry's value with its unit, e.g. "72.4 kg" or "121/79 mmHg". */
@Composable
fun formatEntry(entry: LoggedEntry, unitSystem: UnitSystem): String = when (entry.type) {
    EntryType.WATER -> metricValue(MetricTile.HYDRATION, entry.value, unitSystem)
    EntryType.WEIGHT -> metricValue(MetricTile.WEIGHT, entry.value, unitSystem)
    EntryType.HEART_RATE -> metricValue(MetricTile.HEART_RATE, entry.value, unitSystem)
    EntryType.HRV -> metricValue(MetricTile.HRV, entry.value, unitSystem)
    EntryType.MINDFULNESS -> stringResource(R.string.quicklog_minutes, entry.value.roundToInt())
    EntryType.BLOOD_PRESSURE -> stringResource(
        R.string.quicklog_blood_pressure_value,
        formatCount(entry.value.roundToInt()),
        formatCount(entry.value2?.roundToInt() ?: 0),
    )
    EntryType.BODY_FAT,
    EntryType.BLOOD_OXYGEN,
    EntryType.RESPIRATORY_RATE,
    EntryType.BODY_TEMPERATURE,
    EntryType.BLOOD_GLUCOSE,
    EntryType.HEIGHT,
    -> LogValueSpecs.getValue(entry.type).let { spec ->
        val display = spec.toDisplay(entry.value, imperial = unitSystem == UnitSystem.IMPERIAL)
        "${spec.format(display)} ${stringResource(spec.unit(unitSystem))}"
    }
}

@Composable
private fun metricValue(metric: MetricTile, value: Double, unitSystem: UnitSystem): String =
    "${formatMetricValue(metric, value, unitSystem)} ${stringResource(metricUnitLabel(metric, unitSystem))}"
