package tech.mmarca.openwatch.wear.features.dashboard

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.wear.compose.material3.AppScaffold
import tech.mmarca.openwatch.wear.R
import tech.mmarca.openwatch.wear.MetricTile
import tech.mmarca.openwatch.wear.ui.components.ChoiceEditorScreen
import tech.mmarca.openwatch.wear.ui.components.ChoiceEditorTexts
import tech.mmarca.openwatch.wear.ui.components.ChoiceRow
import tech.mmarca.openwatch.wear.ui.preview.SampleData
import tech.mmarca.openwatch.wear.ui.preview.WearPreviews
import tech.mmarca.openwatch.wear.ui.theme.OpenWatchWearTheme

/**
 * Adds, removes and reorders dashboard tiles. Only [available] metrics are
 * listed: a metric the watch has no sensor for cannot be switched on. The list
 * itself is the shared [ChoiceEditorScreen].
 */
@Composable
fun TileEditorScreen(
    available: List<MetricTile>,
    tiles: List<MetricTile>,
    onToggle: (MetricTile, Boolean) -> Unit,
    onMove: (moved: MetricTile, target: MetricTile) -> Unit,
    onReset: () -> Unit,
) {
    ChoiceEditorScreen(
        texts = ChoiceEditorTexts(
            title = stringResource(R.string.tiles_title),
            activeHeader = stringResource(R.string.tiles_active_header),
            availableHeader = stringResource(R.string.tiles_available_header),
            reorderHint = stringResource(R.string.tiles_reorder_hint),
            resetLabel = stringResource(R.string.tiles_reset_default),
            footerHint = stringResource(R.string.tiles_sensor_hint),
        ),
        available = available,
        active = tiles,
        row = { metric -> ChoiceRow(stringResource(metric.label), metric.icon, metric.accentColor) },
        onToggle = onToggle,
        onMove = onMove,
        onReset = onReset,
    )
}

@WearPreviews
@Composable
private fun TileEditorScreenPreview() {
    OpenWatchWearTheme {
        AppScaffold {
            TileEditorScreen(
                available = SampleData.uiState.availableMetrics.toList(),
                tiles = MetricTile.DefaultTiles,
                onToggle = { _, _ -> },
                onMove = { _, _ -> },
                onReset = {},
            )
        }
    }
}
