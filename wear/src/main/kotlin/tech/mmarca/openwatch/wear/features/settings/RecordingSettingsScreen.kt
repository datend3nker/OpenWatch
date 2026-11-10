package tech.mmarca.openwatch.wear.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import tech.mmarca.openwatch.wear.R
import tech.mmarca.openwatch.wear.WatchSettingsSection

/** The recording settings: power save, the sleep hold, the Samsung sources and the night measurements. */
@Composable
fun RecordingSettingsScreen() {
    val scroll = rememberScrollState()
    ScreenScaffold(scrollState = scroll) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(horizontal = 20.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            ListHeader { Text(stringResource(R.string.settings_recording)) }
            WatchSettingsSection(LocalContext.current)
        }
    }
}
