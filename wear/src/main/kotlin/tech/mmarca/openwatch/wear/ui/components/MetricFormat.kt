package tech.mmarca.openwatch.wear.ui.components

import androidx.annotation.StringRes
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt
import tech.mmarca.openwatch.wear.R
import tech.mmarca.openwatch.wear.UnitSystem
import tech.mmarca.openwatch.wear.MetricTile

private const val MetersPerKilometer = 1000.0
private const val MetersPerMile = 1609.344
private const val FeetPerMeter = 3.28084
private const val PoundsPerKilogram = 2.20462
private const val MillilitresPerFluidOunce = 29.5735
private const val MinutesPerHour = 60
private const val FahrenheitPerCelsius = 1.8
private const val FahrenheitAtZeroCelsius = 32.0

fun formatCount(value: Int): String = NumberFormat.getIntegerInstance().format(value)

/** [value] is in the metric's base unit. The matching unit label is [metricUnitLabel]. */
fun formatMetricValue(metric: MetricTile, value: Double, unitSystem: UnitSystem): String =
    when (metric) {
        MetricTile.DISTANCE -> {
            val metersPerUnit = when (unitSystem) {
                UnitSystem.METRIC -> MetersPerKilometer
                UnitSystem.IMPERIAL -> MetersPerMile
            }
            String.format(Locale.getDefault(), "%.1f", value / metersPerUnit)
        }
        MetricTile.ELEVATION -> when (unitSystem) {
            UnitSystem.METRIC -> formatCount(value.roundToInt())
            UnitSystem.IMPERIAL -> formatCount((value * FeetPerMeter).roundToInt())
        }
        MetricTile.SLEEP -> formatHoursMinutes(value)
        MetricTile.SKIN_TEMPERATURE -> when (unitSystem) {
            UnitSystem.METRIC -> formatOneDecimal(value)
            UnitSystem.IMPERIAL -> formatOneDecimal(value * FahrenheitPerCelsius + FahrenheitAtZeroCelsius)
        }
        MetricTile.RESPIRATORY_RATE -> formatOneDecimal(value)
        MetricTile.WEIGHT -> when (unitSystem) {
            UnitSystem.METRIC -> formatOneDecimal(value)
            UnitSystem.IMPERIAL -> formatOneDecimal(value * PoundsPerKilogram)
        }
        MetricTile.HYDRATION -> when (unitSystem) {
            UnitSystem.METRIC -> formatCount(value.roundToInt())
            UnitSystem.IMPERIAL -> formatCount((value / MillilitresPerFluidOunce).roundToInt())
        }
        else -> formatCount(value.roundToInt())
    }

/** [kilograms] in the unit system's body weight unit, one decimal. */
fun formatWeight(kilograms: Double, unitSystem: UnitSystem): String =
    formatMetricValue(MetricTile.WEIGHT, kilograms, unitSystem)

/** "7:05" for 425 minutes. */
fun formatHoursMinutes(minutes: Double): String {
    val total = minutes.roundToInt()
    return String.format(Locale.getDefault(), "%d:%02d", total / MinutesPerHour, total % MinutesPerHour)
}

fun formatOneDecimal(value: Double): String = String.format(Locale.getDefault(), "%.1f", value)

@StringRes
fun metricUnitLabel(metric: MetricTile, unitSystem: UnitSystem): Int =
    when (metric) {
        MetricTile.STEPS -> R.string.unit_steps
        MetricTile.HEART_RATE -> R.string.unit_bpm
        MetricTile.DISTANCE -> when (unitSystem) {
            UnitSystem.METRIC -> R.string.unit_km
            UnitSystem.IMPERIAL -> R.string.unit_mi
        }
        MetricTile.ACTIVE_CALORIES -> R.string.unit_kcal
        MetricTile.FLOORS -> R.string.unit_floors
        MetricTile.ELEVATION -> when (unitSystem) {
            UnitSystem.METRIC -> R.string.unit_m
            UnitSystem.IMPERIAL -> R.string.unit_ft
        }
        MetricTile.BLOOD_OXYGEN -> R.string.unit_percent
        MetricTile.VO2_MAX -> R.string.unit_vo2_max
        MetricTile.RESTING_HEART_RATE -> R.string.unit_bpm
        MetricTile.HRV -> R.string.unit_ms
        MetricTile.SLEEP -> R.string.unit_hours
        MetricTile.SKIN_TEMPERATURE -> when (unitSystem) {
            UnitSystem.METRIC -> R.string.unit_celsius
            UnitSystem.IMPERIAL -> R.string.unit_fahrenheit
        }
        MetricTile.RESPIRATORY_RATE -> R.string.unit_breaths_per_minute
        MetricTile.HYDRATION -> when (unitSystem) {
            UnitSystem.METRIC -> R.string.unit_ml
            UnitSystem.IMPERIAL -> R.string.unit_fl_oz
        }
        MetricTile.WEIGHT -> when (unitSystem) {
            UnitSystem.METRIC -> R.string.unit_kg
            UnitSystem.IMPERIAL -> R.string.unit_lb
        }
    }
