package tech.mmarca.openwatch.wear.features.measure

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import tech.mmarca.openwatch.wear.ui.theme.Emphasis

/**
 * The frame of a measurement that has a screen of its own (the ECG, the body
 * composition), laid out as `MeasurementScreen` lays out the others: the
 * progress along the edge while it runs ([progress], null otherwise), the
 * content in the middle, and one button on the bottom edge.
 */
@Composable
internal fun OwnMeasurementFrame(
    measurement: Measurement,
    progress: Float?,
    edgeButton: (@Composable () -> Unit)?,
    content: @Composable ColumnScope.() -> Unit,
) {
    ScreenScaffold {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (progress != null) {
                EdgeProgress(
                    progress = progress,
                    color = measurement.accentColor,
                    trackColor = measurement.accentColor.copy(alpha = Emphasis.disabled),
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // A result with several lines is taller than the screen's middle.
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
                    .padding(top = 24.dp, bottom = if (edgeButton == null) 24.dp else 44.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                content = content,
            )
            if (edgeButton != null) {
                Box(modifier = Modifier.align(Alignment.BottomCenter)) { edgeButton() }
            }
        }
    }
}

@Composable
internal fun FrameTitle(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
}

@Composable
internal fun FrameBody(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

/**
 * A start that first asks for [measurement]'s permission where the watch
 * does not hold it: [onGranted] once it does, [onDenied] when the wearer
 * says no.
 */
@Composable
internal fun rememberPermittedStart(measurement: Measurement, onGranted: () -> Unit, onDenied: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onGranted() else onDenied()
    }
    return {
        val missing = measurement.permission()?.takeIf {
            context.checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing != null) launcher.launch(missing) else onGranted()
    }
}

/**
 * Holds the screen bright while [on]: these two measurements need a finger
 * on the watch, and the wearer follows the screen to keep it there.
 */
@Composable
internal fun KeepScreenOn(on: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, on) {
        view.keepScreenOn = on
        onDispose { view.keepScreenOn = false }
    }
}
