package tech.mmarca.openwatch.wear.features.quicklog

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToInt
import tech.mmarca.openwatch.wear.R
import tech.mmarca.openwatch.wear.UnitSystem

/**
 * A value the watch logs by stepping to it. [metricRange] and [metricDefault]
 * are in the metric unit; the imperial unit is a straight line from it,
 * `display = metric * imperialScale + imperialOffset`, so a value is stored
 * in metric whichever units are shown.
 */
internal data class LogValueSpec(
    @StringRes val label: Int,
    @StringRes val metricUnit: Int,
    @StringRes val imperialUnit: Int,
    val metricRange: ClosedFloatingPointRange<Double>,
    val metricDefault: Double,
    /** Digits after the point; one stepper step is one unit in this place. */
    val decimals: Int,
    val imperialScale: Double = 1.0,
    val imperialOffset: Double = 0.0,
) {
    fun unit(unitSystem: UnitSystem): Int =
        if (unitSystem == UnitSystem.IMPERIAL) imperialUnit else metricUnit

    fun toDisplay(metric: Double, imperial: Boolean): Double =
        if (imperial) metric * imperialScale + imperialOffset else metric

    fun toMetric(display: Double, imperial: Boolean): Double =
        if (imperial) (display - imperialOffset) / imperialScale else display

    /** Stepper units per displayed unit: 10 for one decimal. */
    val stepsPerUnit: Int get() = 10.0.pow(decimals).roundToInt()

    fun format(display: Double): String =
        String.format(Locale.getDefault(), "%.${decimals}f", display)
}

/** The values the watch logs. Anything the phone logs that takes one number is here. */
internal val LogValueSpecs: Map<EntryType, LogValueSpec> = mapOf(
    EntryType.BODY_FAT to LogValueSpec(
        label = R.string.metric_body_fat,
        metricUnit = R.string.unit_percent,
        imperialUnit = R.string.unit_percent,
        metricRange = 3.0..60.0,
        metricDefault = 20.0,
        decimals = 1,
    ),
    EntryType.BLOOD_OXYGEN to LogValueSpec(
        label = R.string.metric_blood_oxygen,
        metricUnit = R.string.unit_percent,
        imperialUnit = R.string.unit_percent,
        metricRange = 70.0..100.0,
        metricDefault = 97.0,
        decimals = 0,
    ),
    EntryType.RESPIRATORY_RATE to LogValueSpec(
        label = R.string.metric_respiratory_rate,
        metricUnit = R.string.unit_breaths,
        imperialUnit = R.string.unit_breaths,
        metricRange = 4.0..60.0,
        metricDefault = 14.0,
        decimals = 0,
    ),
    EntryType.BODY_TEMPERATURE to LogValueSpec(
        label = R.string.metric_body_temperature,
        metricUnit = R.string.unit_celsius,
        imperialUnit = R.string.unit_fahrenheit,
        metricRange = 34.0..42.0,
        metricDefault = 36.5,
        decimals = 1,
        imperialScale = 1.8,
        imperialOffset = 32.0,
    ),
    EntryType.BLOOD_GLUCOSE to LogValueSpec(
        label = R.string.metric_blood_glucose,
        metricUnit = R.string.unit_mmol_l,
        imperialUnit = R.string.unit_mg_dl,
        metricRange = 2.0..30.0,
        metricDefault = 5.5,
        decimals = 1,
        imperialScale = 18.0182,
    ),
    // Inches, not feet and inches: the stepper holds one number, and a whole inch
    // (2.5 cm) is finer than a height is read to. Stored in whole centimetres.
    EntryType.HEIGHT to LogValueSpec(
        label = R.string.metric_height,
        metricUnit = R.string.unit_cm,
        imperialUnit = R.string.unit_inch,
        metricRange = 50.0..250.0,
        metricDefault = 170.0,
        decimals = 0,
        imperialScale = 1.0 / 2.54,
    ),
)

/**
 * One value, picked with the stepper. [initialMetric] is the last logged value
 * of this type, or the default. Saved in the metric unit.
 */
@Composable
internal fun LogValueScreen(
    type: EntryType,
    initialMetric: Double?,
    unitSystem: UnitSystem,
    onSave: (metric: Double) -> Unit,
) {
    val spec = LogValueSpecs.getValue(type)
    val imperial = unitSystem == UnitSystem.IMPERIAL
    val factor = spec.stepsPerUnit
    val start = spec.toDisplay(initialMetric ?: spec.metricDefault, imperial)
    var steps by rememberSaveable { mutableIntStateOf((start * factor).roundToInt()) }
    val min = (spec.toDisplay(spec.metricRange.start, imperial) * factor).roundToInt()
    val max = (spec.toDisplay(spec.metricRange.endInclusive, imperial) * factor).roundToInt()
    ValueStepper(
        title = stringResource(spec.label),
        unit = stringResource(spec.unit(unitSystem)),
        value = steps,
        onValueChange = { steps = it },
        range = min..max,
        format = { spec.format(it.toDouble() / factor) },
        onConfirm = {
            val display = steps.toDouble() / factor
            onSave((spec.toMetric(display, imperial) * factor).roundToInt() / factor.toDouble())
        },
    )
}
