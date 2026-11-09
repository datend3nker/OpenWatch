package tech.mmarca.openwatch.wear.features.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import tech.mmarca.openwatch.wear.R
import tech.mmarca.openwatch.wear.ui.preview.WearPreviews
import tech.mmarca.openwatch.wear.ui.theme.OpenWatchWearTheme

/** What the watch app may use, one row per grant, and a way to ask for the ones that are missing. */
internal data class SensorAccess(
    val heartRate: Boolean,
    val heartRateInBackground: Boolean,
    /** Null when the watch has no step counter. */
    val steps: Boolean?,
    val nearbyDevices: Boolean,
) {
    val complete: Boolean get() = heartRate && heartRateInBackground && steps != false && nearbyDevices
}

@Composable
internal fun SensorAccessScreen(
    access: SensorAccess,
    onGrant: () -> Unit,
    onOpenDetails: () -> Unit,
) {
    val listState = rememberTransformingLazyColumnState()
    val transformationSpec = rememberTransformationSpec()
    val rowModifier = Modifier.fillMaxWidth()
    val granted = stringResource(R.string.settings_sensor_access_granted)
    val missing = stringResource(R.string.settings_sensor_access_missing)

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
            item {
                ListHeader(
                    modifier = rowModifier.transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec),
                ) { Text(stringResource(R.string.settings_sensor_access)) }
            }
            item {
                InfoRow(
                    label = stringResource(R.string.sensors_heart_rate),
                    value = if (access.heartRate) granted else missing,
                    modifier = rowModifier.transformedHeight(this, transformationSpec),
                )
            }
            item {
                InfoRow(
                    label = stringResource(R.string.sensors_heart_rate_background),
                    value = if (access.heartRateInBackground) granted else missing,
                    modifier = rowModifier.transformedHeight(this, transformationSpec),
                )
            }
            item {
                InfoRow(
                    label = stringResource(R.string.sensors_steps),
                    value = when (access.steps) {
                        null -> stringResource(R.string.sensors_no_step_counter)
                        true -> granted
                        false -> missing
                    },
                    modifier = rowModifier.transformedHeight(this, transformationSpec),
                )
            }
            item {
                InfoRow(
                    label = stringResource(R.string.sensors_nearby_devices),
                    value = if (access.nearbyDevices) granted else missing,
                    modifier = rowModifier.transformedHeight(this, transformationSpec),
                )
            }
            if (!access.complete) {
                item {
                    Button(
                        onClick = onGrant,
                        label = { Text(stringResource(R.string.action_grant)) },
                        modifier = rowModifier.transformedHeight(this, transformationSpec),
                        transformation = SurfaceTransformation(transformationSpec),
                    )
                }
            }
            item {
                Button(
                    onClick = onOpenDetails,
                    label = { Text(stringResource(R.string.sensors_recording_details)) },
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    modifier = rowModifier.transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec),
                )
            }
        }
    }
}

@WearPreviews
@Composable
private fun SensorAccessScreenPreview() {
    OpenWatchWearTheme {
        AppScaffold {
            SensorAccessScreen(
                access = SensorAccess(heartRate = true, heartRateInBackground = false, steps = true, nearbyDevices = true),
                onGrant = {},
                onOpenDetails = {},
            )
        }
    }
}
