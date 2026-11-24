package tech.mmarca.openwatch.wear.features.measure

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.Bloodtype
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import kotlin.math.roundToInt
import tech.mmarca.openwatch.wear.R
import tech.mmarca.openwatch.wear.SpotFailure
import tech.mmarca.openwatch.wear.SpotKind
import tech.mmarca.openwatch.wear.UnitSystem
import tech.mmarca.openwatch.wear.haptics.HapticCue
import tech.mmarca.openwatch.wear.ui.components.FeetPerMeter
import tech.mmarca.openwatch.wear.ui.components.formatCount
import tech.mmarca.openwatch.wear.ui.theme.ElevationColor
import tech.mmarca.openwatch.wear.ui.theme.HeartColor
import tech.mmarca.openwatch.wear.ui.theme.VitalsColor
import tech.mmarca.openwatch.wear.ui.theme.WeightColor

/**
 * A spot measurement ([SpotKind]) as the screens show it: its name, what to
 * do, its icon and colour, and the units of its live value and its result.
 * Offered only where the watch has its sensor (`WatchSensors.spotKinds`).
 */
enum class Measurement(
    val kind: SpotKind,
    @param:StringRes val label: Int,
    @param:StringRes val description: Int,
    @param:StringRes val instruction: Int,
    val icon: ImageVector,
    val accentColor: Color,
    /** The grant it reads under, asked for before it starts where it is missing. */
    val grant: MeasureGrant,
    /** The unit of the value shown while it runs. */
    @param:StringRes val liveUnit: Int,
    /** The unit of the result. */
    @param:StringRes val unit: Int,
) {
    HEART_RATE(
        SpotKind.HEART_RATE,
        R.string.measure_heart_rate,
        R.string.measure_heart_rate_description,
        R.string.measure_instruction_still,
        Icons.Outlined.Favorite,
        HeartColor,
        grant = MeasureGrant.HEART_RATE,
        liveUnit = R.string.unit_bpm,
        unit = R.string.unit_bpm,
    ),

    /** Three minutes; while it runs it shows the pulse, and the result is the RMSSD. */
    HRV(
        SpotKind.HRV,
        R.string.measure_hrv,
        R.string.measure_hrv_description_minutes,
        R.string.measure_instruction_still_minutes,
        Icons.Outlined.Insights,
        VitalsColor,
        grant = MeasureGrant.HEART_RATE,
        liveUnit = R.string.unit_bpm,
        unit = R.string.unit_ms,
    ),

    AIR_PRESSURE(
        SpotKind.AIR_PRESSURE,
        R.string.measure_air_pressure,
        R.string.measure_air_pressure_description,
        R.string.measure_instruction_none,
        Icons.Outlined.Compress,
        ElevationColor,
        grant = MeasureGrant.NONE,
        liveUnit = R.string.unit_hpa,
        unit = R.string.unit_hpa,
    ),

    /** The Samsung platform's tracker, as at night: no live value, the reading after about 15 seconds. */
    SPO2(
        SpotKind.SPO2,
        R.string.measure_spo2,
        R.string.measure_spo2_description,
        R.string.measure_instruction_still,
        Icons.Outlined.Bloodtype,
        VitalsColor,
        grant = MeasureGrant.OXYGEN_SATURATION,
        liveUnit = R.string.unit_percent,
        unit = R.string.unit_percent,
    ),

    /** The Samsung platform's ECG: the trace while it records, then its heart rate and what its rhythm looks like. */
    ECG(
        SpotKind.ECG,
        R.string.measure_ecg,
        R.string.measure_ecg_description,
        R.string.measure_instruction_ecg,
        Icons.Outlined.MonitorHeart,
        HeartColor,
        grant = MeasureGrant.ADDITIONAL_HEALTH_DATA,
        liveUnit = R.string.unit_bpm,
        unit = R.string.unit_bpm,
    ),

    /** The Samsung platform's body composition: its progress, then the body fat with the rest beneath it. */
    BODY_COMPOSITION(
        SpotKind.BODY_COMPOSITION,
        R.string.measure_body_composition,
        R.string.measure_body_composition_description,
        R.string.measure_instruction_body_composition,
        Icons.Outlined.Accessibility,
        WeightColor,
        grant = MeasureGrant.ADDITIONAL_HEALTH_DATA,
        liveUnit = R.string.unit_percent,
        unit = R.string.unit_percent,
    ),
    ;

    /** What the wearer is told when this measurement gave no reading: [SpotFailure.message], in this measurement's words. */
    @StringRes
    fun messageOf(failure: SpotFailure): Int = when {
        this == SPO2 && failure == SpotFailure.PERMISSION_DENIED -> R.string.measure_error_permission_spo2
        this == SPO2 && failure == SpotFailure.BUSY -> R.string.measure_error_busy_spo2
        grant == MeasureGrant.ADDITIONAL_HEALTH_DATA && failure == SpotFailure.PERMISSION_DENIED -> R.string.measure_error_additional_permission
        else -> failure.message
    }

    companion object {
        fun of(kind: SpotKind): Measurement = entries.first { it.kind == kind }
    }
}

/**
 * The grant a measurement reads under: the heart rate's, the blood oxygen's or Samsung's additional
 * health data (the Samsung platform checks them), or none.
 */
enum class MeasureGrant { NONE, HEART_RATE, OXYGEN_SATURATION, ADDITIONAL_HEALTH_DATA }

/** What the wearer is told when a measurement gave no reading. */
@get:StringRes
val SpotFailure.message: Int
    get() = when (this) {
        SpotFailure.NOT_SUPPORTED -> R.string.measure_error_not_supported
        SpotFailure.PERMISSION_DENIED -> R.string.measure_error_permission
        SpotFailure.OFF_BODY -> R.string.measure_error_off_body
        SpotFailure.NO_SIGNAL -> R.string.measure_error_no_signal
        SpotFailure.MOVED -> R.string.measure_error_moved
        SpotFailure.BUSY -> R.string.measure_error_busy
        SpotFailure.FAILED -> R.string.measure_error_failed
        SpotFailure.NO_PROFILE -> R.string.measure_error_no_profile
    }

/**
 * How the wrist is told a run ended: one pulse for a reading, two for a
 * failure. A failure found before anything ran (no sensor, no grant), while
 * the wearer looks at the screen, is silent; so is a state that is no end.
 */
fun MeasureUiState.endCue(): HapticCue? = when (this) {
    is MeasureUiState.Done -> HapticCue.PULSE
    is MeasureUiState.Failed -> when (failure) {
        SpotFailure.NOT_SUPPORTED, SpotFailure.PERMISSION_DENIED, SpotFailure.BUSY, SpotFailure.NO_PROFILE -> null
        else -> HapticCue.DOUBLE_PULSE
    }
    MeasureUiState.Ready, is MeasureUiState.Running -> null
}

/** A measured value as the screen shows it: whole units. */
fun formatMeasurement(value: Double): String = formatCount(value.roundToInt())

/** The altitude that goes with a pressure reading, in the unit system's length unit, with that unit's string. */
fun formatAltitude(metres: Double, unitSystem: UnitSystem): Pair<String, Int> = when (unitSystem) {
    UnitSystem.METRIC -> formatCount(metres.roundToInt()) to R.string.unit_m
    UnitSystem.IMPERIAL -> formatCount((metres * FeetPerMeter).roundToInt()) to R.string.unit_ft
}
