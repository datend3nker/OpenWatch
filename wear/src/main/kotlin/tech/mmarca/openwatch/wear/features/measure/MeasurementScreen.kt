package tech.mmarca.openwatch.wear.features.measure

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
import androidx.wear.compose.material3.ProgressIndicatorDefaults
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import tech.mmarca.openwatch.wear.R
import tech.mmarca.openwatch.wear.WearPermissions
import tech.mmarca.openwatch.wear.UnitSystem
import tech.mmarca.openwatch.wear.ui.ambient.AmbientLook
import tech.mmarca.openwatch.wear.ui.ambient.ambientLook
import tech.mmarca.openwatch.wear.ui.ambient.rememberAmbient
import tech.mmarca.openwatch.wear.ui.components.MetricValueRow
import tech.mmarca.openwatch.wear.ui.preview.WearPreviews
import tech.mmarca.openwatch.wear.ui.theme.Emphasis
import tech.mmarca.openwatch.wear.ui.theme.OpenWatchWearTheme

/**
 * Route for one spot measurement. [supported] is null while the watch's
 * capabilities are still being read; false shows why the measurement cannot
 * run here instead of a start button, which also covers a tile shortcut to a
 * measurement this watch lacks.
 */
@Composable
fun MeasurementRoute(
    measurement: Measurement,
    supported: Boolean?,
    unitSystem: UnitSystem,
    onDone: () -> Unit,
) {
    when (supported) {
        null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        false -> MeasurementScreen(
            measurement = measurement,
            state = MeasureUiState.Failed(MeasureError.NOT_SUPPORTED),
            unitSystem = unitSystem,
            onStart = {},
            onOpenSettings = {},
            onDone = onDone,
        )
        true -> {
            val context = LocalContext.current
            val viewModel: MeasureViewModel = viewModel(
                key = measurement.name,
                factory = MeasureViewModel.factory(measurement, context.applicationContext as Application),
            )
            val state by viewModel.state.collectAsStateWithLifecycle()
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { granted ->
                if (granted) viewModel.start() else viewModel.fail(MeasureError.PERMISSION_DENIED)
            }
            MeasurementScreen(
                measurement = measurement,
                state = state,
                unitSystem = unitSystem,
                onStart = {
                    val missing = measurement.permission()?.takeIf {
                        context.checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
                    }
                    if (missing != null) {
                        permissionLauncher.launch(missing)
                    } else {
                        viewModel.start()
                    }
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
        is MeasureUiState.Failed -> when (state.error) {
            MeasureError.NOT_SUPPORTED -> {
                { EdgeButton(onClick = onDone) { Text(stringResource(R.string.action_back)) } }
            }
            MeasureError.PERMISSION_DENIED -> {
                { EdgeButton(onClick = onStart) { Text(stringResource(R.string.measure_grant)) } }
            }
            else -> {
                { EdgeButton(onClick = onStart) { Text(stringResource(R.string.action_retry)) } }
            }
        }
        is MeasureUiState.Running -> null
    }

    // The screen is not held bright any more. While a measurement runs it may
    // dim to the ambient view. The HRV window holds its own wake lock, so the
    // run goes on either way.
    val ambient = rememberAmbient(wanted = state is MeasureUiState.Running)
    val look = ambientLook(ambient)

    ScreenScaffold {
        Box(modifier = Modifier.fillMaxSize().then(if (ambient) Modifier.background(Color.Black) else Modifier)) {
            MeasurementBody(measurement, state, unitSystem, look, onStart, onOpenSettings)
            if (edgeButton != null) {
                Box(modifier = Modifier.align(Alignment.BottomCenter)) { edgeButton() }
            }
        }
    }
}

@Composable
private fun MeasurementBody(
    measurement: Measurement,
    state: MeasureUiState,
    unitSystem: UnitSystem,
    look: AmbientLook,
    onStart: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (state is MeasureUiState.Running && look.showProgressArc) {
            EdgeProgress(
                progress = state.progress,
                color = measurement.accentColor,
                trackColor = measurement.accentColor.copy(alpha = Emphasis.disabled),
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
                    if (look.animate) PulsingIcon(measurement)
                    MetricValueRow(
                        value = state.live?.let { formatMeasurement(it) },
                        unit = stringResource(measurementUnit(measurement)),
                        valueStyle = MaterialTheme.typography.displaySmall,
                    )
                    if (look.showSecondaryValues) {
                        Body(
                            stringResource(
                                when {
                                    state.offBody -> R.string.measure_hint_off_body
                                    state.waiting -> R.string.measure_hint_detecting
                                    else -> measurement.instruction
                                },
                            ),
                        )
                    }
                }
                is MeasureUiState.Done -> {
                    Icon(measurement.icon, null, tint = measurement.accentColor, modifier = Modifier.size(24.dp))
                    MetricValueRow(
                        value = formatMeasurement(state.value),
                        unit = stringResource(measurementUnit(measurement)),
                        valueStyle = MaterialTheme.typography.displayMedium,
                    )
                    state.secondary?.takeIf { measurement == Measurement.AIR_PRESSURE }?.let { altitude ->
                        val (value, unit) = formatAltitude(altitude, unitSystem)
                        Body(stringResource(R.string.measure_altitude, value, stringResource(unit)))
                    }
                }
                is MeasureUiState.Failed -> {
                    Icon(
                        Icons.Outlined.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(28.dp),
                    )
                    Title(stringResource(measurement.label))
                    Body(stringResource(state.error.message))
                    if (state.error == MeasureError.PERMISSION_DENIED) {
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
private fun MeasurementFailedPreview() {
    OpenWatchWearTheme {
        AppScaffold {
            MeasurementScreen(
                Measurement.HEART_RATE,
                MeasureUiState.Failed(MeasureError.PERMISSION_DENIED),
                UnitSystem.METRIC,
                {},
                {},
                {},
            )
        }
    }
}
