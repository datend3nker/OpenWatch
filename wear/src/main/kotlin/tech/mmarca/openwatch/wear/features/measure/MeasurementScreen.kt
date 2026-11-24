package tech.mmarca.openwatch.wear.features.measure

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import tech.mmarca.openwatch.wear.BodyComposition
import tech.mmarca.openwatch.wear.EcgHeartRate
import tech.mmarca.openwatch.wear.MetricTile
import tech.mmarca.openwatch.wear.R
import tech.mmarca.openwatch.wear.SpotEvent
import tech.mmarca.openwatch.wear.SpotFailure
import tech.mmarca.openwatch.wear.SpotHint
import tech.mmarca.openwatch.wear.SpotKind
import tech.mmarca.openwatch.wear.UnitSystem
import tech.mmarca.openwatch.wear.WearPermissions
import tech.mmarca.openwatch.wear.haptics.Haptics
import tech.mmarca.openwatch.wear.ui.ambient.AmbientAware
import tech.mmarca.openwatch.wear.ui.components.MetricValueRow
import tech.mmarca.openwatch.wear.ui.components.formatCount
import tech.mmarca.openwatch.wear.ui.components.formatMetricValue
import tech.mmarca.openwatch.wear.ui.components.metricUnitLabel
import tech.mmarca.openwatch.wear.ui.preview.WearPreviews
import tech.mmarca.openwatch.wear.ui.theme.Emphasis
import tech.mmarca.openwatch.wear.ui.theme.OpenWatchWearTheme

/**
 * The destination of one spot measurement. [supported] is null while the
 * watch's sensors are read; false shows why the measurement cannot run here
 * instead of a start button. The measurement runs through [events]
 * (`WearGraph.spot`); it first asks for the grant it reads under where it is
 * missing ([Measurement.grant]: the heart rate's for a heart rate or an HRV,
 * the blood oxygen's for a blood oxygen). The wrist buzzes when a run ends ([endCue]).
 */
@Composable
internal fun MeasurementRoute(
    measurement: Measurement,
    supported: Boolean?,
    unitSystem: UnitSystem,
    events: (SpotKind) -> Flow<SpotEvent>,
    onDone: () -> Unit,
) {
    when (supported) {
        null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        false -> MeasurementScreen(
            measurement = measurement,
            state = MeasureUiState.Failed(SpotFailure.NOT_SUPPORTED),
            unitSystem = unitSystem,
            onStart = {},
            onOpenSettings = {},
            onDone = onDone,
        )
        true -> {
            val context = LocalContext.current
            val viewModel = viewModel(key = measurement.name) {
                val app = context.applicationContext
                MeasureViewModel(
                    measurement.kind,
                    events,
                    CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
                    onEnd = { ended -> Haptics.play(app, ended.endCue()) },
                )
            }
            val state by viewModel.state.collectAsStateWithLifecycle()
            val askGrant = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                if (granted) viewModel.start() else viewModel.fail(SpotFailure.PERMISSION_DENIED)
            }
            MeasurementScreen(
                measurement = measurement,
                state = state,
                unitSystem = unitSystem,
                onStart = {
                    val missing = when (measurement.grant) {
                        MeasureGrant.NONE -> null
                        MeasureGrant.HEART_RATE -> WearPermissions.heartRate.takeUnless { WearPermissions.hasHeartRate(context) }
                        MeasureGrant.OXYGEN_SATURATION -> WearPermissions.oxygenSaturation.takeUnless { WearPermissions.hasOxygenSaturation(context) }
                        MeasureGrant.ADDITIONAL_HEALTH_DATA ->
                            WearPermissions.additionalHealthData.takeUnless { WearPermissions.hasAdditionalHealthData(context) }
                    }
                    if (missing != null) askGrant.launch(missing) else viewModel.start()
                },
                onOpenSettings = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(Uri.fromParts("package", context.packageName, null)),
                    )
                },
                onDone = onDone,
            )
        }
    }
}

/**
 * One spot measurement: what to do and a start button, then the live value
 * inside an arc along the screen's edge that fills with the measurement's
 * time ([EdgeProgress]), then the reading or why there is none. The measurement holds a wake lock of its own, so it
 * ends with its reading even when the screen goes off meanwhile. While it
 * runs the screen stays on in ambient mode with a dim layout of its own
 * ([AmbientAware], as a running workout's): its name, a dash for the value,
 * which would be stale between the system's once-a-minute updates, and
 * what to do; the reading shows when the wearer raises the wrist.
 */
@Composable
fun MeasurementScreen(
    measurement: Measurement,
    state: MeasureUiState,
    unitSystem: UnitSystem,
    onStart: () -> Unit,
    onOpenSettings: () -> Unit,
    onDone: () -> Unit,
) {
    val edgeButton: (@Composable () -> Unit)? = when (state) {
        MeasureUiState.Ready -> {
            { EdgeButton(onClick = onStart) { Text(stringResource(R.string.measure_start)) } }
        }
        is MeasureUiState.Done -> {
            { EdgeButton(onClick = onDone) { Text(stringResource(R.string.action_done)) } }
        }
        is MeasureUiState.Failed -> when (state.failure) {
            SpotFailure.NOT_SUPPORTED -> {
                { EdgeButton(onClick = onDone) { Text(stringResource(R.string.action_back)) } }
            }
            SpotFailure.PERMISSION_DENIED -> {
                { EdgeButton(onClick = onStart) { Text(stringResource(R.string.measure_grant)) } }
            }
            else -> {
                { EdgeButton(onClick = onStart) { Text(stringResource(R.string.action_retry)) } }
            }
        }
        is MeasureUiState.Running -> null
    }

    val layout = @Composable {
        ScreenScaffold {
            Box(modifier = Modifier.fillMaxSize()) {
                MeasurementBody(measurement, state, unitSystem, onOpenSettings)
                if (edgeButton != null) {
                    Box(modifier = Modifier.align(Alignment.BottomCenter)) { edgeButton() }
                }
            }
        }
    }
    // Only a running measurement observes the ambient state: the other states are a reading, a reason or a start button.
    if (state is MeasureUiState.Running) {
        AmbientAware(latest = state, ambient = { AmbientMeasurement(measurement) }, interactive = layout)
    } else {
        layout()
    }
}

/** The ambient layout of a running measurement: dim, still, no value that would be stale. */
@Composable
private fun AmbientMeasurement(measurement: Measurement) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    ScreenScaffold { contentPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(contentPadding).padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        ) {
            Text(stringResource(measurement.label), style = MaterialTheme.typography.bodySmall, color = dim, textAlign = TextAlign.Center)
            Text(
                text = stringResource(R.string.measure_value_placeholder, stringResource(measurement.liveUnit)),
                style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Light),
                color = dim,
            )
            Text(stringResource(measurement.instruction), style = MaterialTheme.typography.bodySmall, color = dim, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun MeasurementBody(
    measurement: Measurement,
    state: MeasureUiState,
    unitSystem: UnitSystem,
    onOpenSettings: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (state is MeasureUiState.Running) {
            EdgeProgress(
                progress = state.progress,
                color = measurement.accentColor,
                trackColor = measurement.accentColor.copy(alpha = Emphasis.subtle),
                modifier = Modifier.fillMaxSize(),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = if (state is MeasureUiState.Running) 0.dp else 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            when (state) {
                MeasureUiState.Ready -> {
                    Icon(measurement.icon, null, tint = measurement.accentColor, modifier = Modifier.size(28.dp))
                    Title(stringResource(measurement.label))
                    Body(stringResource(measurement.instruction))
                }
                is MeasureUiState.Running -> {
                    PulsingIcon(measurement)
                    val trace = state.trace
                    when {
                        trace != null -> EcgTrace(trace, measurement.accentColor)
                        // A measurement that counts its own progress has no value until its result.
                        !state.ownProgress -> MetricValueRow(
                            value = state.live?.let { formatMeasurement(it) },
                            unit = stringResource(measurement.liveUnit),
                            valueStyle = MaterialTheme.typography.displaySmall,
                        )
                    }
                    Body(
                        stringResource(
                            when {
                                state.offBody -> R.string.measure_hint_off_body
                                state.hint != null -> state.hint.message
                                state.waiting -> R.string.measure_hint_detecting
                                trace != null -> R.string.ecg_recording
                                else -> measurement.instruction
                            },
                        ),
                    )
                }
                is MeasureUiState.Done -> {
                    Icon(measurement.icon, null, tint = measurement.accentColor, modifier = Modifier.size(24.dp))
                    MetricValueRow(
                        value = formatMeasurement(state.value),
                        unit = stringResource(measurement.unit),
                        valueStyle = MaterialTheme.typography.displayMedium,
                    )
                    state.secondary?.let { altitude ->
                        val (value, unit) = formatAltitude(altitude, unitSystem)
                        Body(stringResource(R.string.measure_altitude, value, stringResource(unit)))
                    }
                    state.ecg?.let { ecg ->
                        Title(stringResource(ecg.rhythm.message))
                        Body(stringResource(R.string.ecg_not_medical))
                    }
                    state.body?.let { body -> BodyCompositionRows(body, unitSystem) }
                }
                is MeasureUiState.Failed -> {
                    Icon(
                        Icons.Outlined.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(28.dp),
                    )
                    Title(stringResource(measurement.label))
                    Body(stringResource(measurement.messageOf(state.failure)))
                    if (state.failure == SpotFailure.PERMISSION_DENIED) {
                        Button(
                            onClick = onOpenSettings,
                            label = { Text(stringResource(R.string.measure_open_settings)) },
                            colors = ButtonDefaults.outlinedButtonColors(),
                        )
                    }
                }
            }
        }
    }
}

/** What a body composition gives beside its body fat: the masses in the unit system's unit, and the basal rate. */
@Composable
private fun BodyCompositionRows(body: BodyComposition, unitSystem: UnitSystem) {
    val massUnit = stringResource(metricUnitLabel(MetricTile.WEIGHT, unitSystem))
    fun mass(kg: Double) = formatMetricValue(MetricTile.WEIGHT, kg, unitSystem)
    Body(stringResource(R.string.bia_lean, mass(body.leanMassKg), massUnit))
    Body(stringResource(R.string.bia_water, mass(body.bodyWaterKg), massUnit))
    body.skeletalMuscleKg?.let { Body(stringResource(R.string.bia_muscle, mass(it), massUnit)) }
    body.basalMetabolicRateKcal?.let { Body(stringResource(R.string.bia_basal_rate, formatCount(it.roundToInt()))) }
    Body(stringResource(R.string.bia_estimate))
}

/** An ECG's last seconds, scaled to their own range. */
@Composable
private fun EcgTrace(millivolts: FloatArray, color: Color) {
    Canvas(Modifier.fillMaxWidth().height(40.dp)) {
        if (millivolts.size < 2) return@Canvas
        val low = millivolts.min()
        val span = (millivolts.max() - low).coerceAtLeast(1e-6f)
        val shown = TRACE_SECONDS * EcgHeartRate.SAMPLE_RATE_HZ
        var last: Offset? = null
        for (i in millivolts.indices step TRACE_STEP) {
            val point = Offset(
                x = size.width * i / shown,
                y = size.height * (1f - (millivolts[i] - low) / span),
            )
            last?.let { drawLine(color, it, point, strokeWidth = 2f) }
            last = point
        }
    }
}

/** The seconds of trace the screen shows, as `SpotMeasurements` sends them. */
private const val TRACE_SECONDS = 3

/** Every third sample: 6 ms, still several points on an R wave. */
private const val TRACE_STEP = 3

@get:StringRes
private val SpotHint.message: Int
    get() = when (this) {
        SpotHint.TOUCH_ELECTRODES -> R.string.measure_instruction_ecg
        SpotHint.WRIST -> R.string.bia_wrist
        SpotHint.FINGERS -> R.string.bia_fingers
        SpotHint.FINGERS_DRY -> R.string.bia_dry
        SpotHint.HANDS_TOUCH, SpotHint.FRAME_TOUCHED -> R.string.bia_apart
    }

@get:StringRes
private val EcgHeartRate.Rhythm.message: Int
    get() = when (this) {
        EcgHeartRate.Rhythm.SINUS_LIKE -> R.string.ecg_rhythm_sinus_like
        EcgHeartRate.Rhythm.REGULAR -> R.string.ecg_rhythm_regular
        EcgHeartRate.Rhythm.IRREGULAR -> R.string.ecg_rhythm_irregular
        EcgHeartRate.Rhythm.UNCLEAR -> R.string.ecg_rhythm_unclear
    }

@Composable
private fun PulsingIcon(measurement: Measurement) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val scale by transition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 500), RepeatMode.Reverse),
        label = "pulseScale",
    )
    Icon(
        measurement.icon,
        contentDescription = null,
        tint = measurement.accentColor,
        modifier = Modifier
            .size(28.dp)
            .scale(scale),
    )
}

@Composable
private fun Title(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
}

@Composable
private fun Body(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@WearPreviews
@Composable
private fun MeasurementReadyPreview() {
    OpenWatchWearTheme {
        AppScaffold {
            MeasurementScreen(Measurement.HEART_RATE, MeasureUiState.Ready, UnitSystem.METRIC, {}, {}, {})
        }
    }
}

@WearPreviews
@Composable
private fun MeasurementRunningPreview() {
    OpenWatchWearTheme {
        AppScaffold {
            MeasurementScreen(
                Measurement.HEART_RATE,
                MeasureUiState.Running(progress = 0.4f, live = 71.0, waiting = false, offBody = false),
                UnitSystem.METRIC,
                {},
                {},
                {},
            )
        }
    }
}

@WearPreviews
@Composable
private fun MeasurementDonePreview() {
    OpenWatchWearTheme {
        AppScaffold {
            MeasurementScreen(
                Measurement.AIR_PRESSURE,
                MeasureUiState.Done(value = 1003.0, secondary = 85.0),
                UnitSystem.METRIC,
                {},
                {},
                {},
            )
        }
    }
}

@WearPreviews
@Composable
private fun MeasurementBodyCompositionPreview() {
    OpenWatchWearTheme {
        AppScaffold {
            MeasurementScreen(
                Measurement.BODY_COMPOSITION,
                MeasureUiState.Done(
                    value = 19.6,
                    secondary = null,
                    body = BodyComposition(19.6, leanMassKg = 64.5, bodyWaterKg = 47.2, skeletalMuscleKg = 36.1, basalMetabolicRateKcal = 1720.0),
                ),
                UnitSystem.METRIC,
                {},
                {},
                {},
            )
        }
    }
}

@WearPreviews
@Composable
private fun MeasurementFailedPreview() {
    OpenWatchWearTheme {
        AppScaffold {
            MeasurementScreen(
                Measurement.HEART_RATE,
                MeasureUiState.Failed(SpotFailure.PERMISSION_DENIED),
                UnitSystem.METRIC,
                {},
                {},
                {},
            )
        }
    }
}
