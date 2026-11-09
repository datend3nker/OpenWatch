package tech.mmarca.openwatch.wear.workout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import kotlinx.coroutines.delay
import tech.mmarca.openwatch.wear.R
import tech.mmarca.openwatch.wear.features.measure.EdgeProgress
import tech.mmarca.openwatch.wear.ui.theme.HeartColor

/** The two minutes of a heart rate recovery: the time left, the live heart rate, and what to do. */
@Composable
internal fun RecoveryScreen(recovery: RecoveryState) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(recovery.untilMillis) {
        while (now < recovery.untilMillis) {
            delay(TICK_MILLIS)
            now = System.currentTimeMillis()
        }
    }
    val total = (recovery.untilMillis - recovery.stopMillis).coerceAtLeast(1)
    val left = ((recovery.untilMillis - now) / 1000).coerceAtLeast(0)
    ScreenScaffold {
        EdgeProgress(
            progress = ((now - recovery.stopMillis).toFloat() / total).coerceIn(0f, 1f),
            color = HeartColor,
            trackColor = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxSize(),
        )
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(stringResource(R.string.recovery_title), style = MaterialTheme.typography.titleMedium)
            Text("%d:%02d".format(left / 60, left % 60), style = MaterialTheme.typography.displayMedium)
            Text(
                recovery.bpm?.let { stringResource(R.string.recovery_bpm, it) } ?: "--",
                style = MaterialTheme.typography.titleLarge,
                color = HeartColor,
            )
            Text(
                stringResource(R.string.recovery_instruction),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private const val TICK_MILLIS = 1_000L
