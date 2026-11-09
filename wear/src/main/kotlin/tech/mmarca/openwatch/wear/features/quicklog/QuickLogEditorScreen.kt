package tech.mmarca.openwatch.wear.features.quicklog

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.wear.compose.material3.AppScaffold
import tech.mmarca.openwatch.wear.R
import tech.mmarca.openwatch.wear.ui.components.ChoiceEditorScreen
import tech.mmarca.openwatch.wear.ui.components.ChoiceEditorTexts
import tech.mmarca.openwatch.wear.ui.components.ChoiceRow
import tech.mmarca.openwatch.wear.ui.preview.WearPreviews
import tech.mmarca.openwatch.wear.ui.theme.OpenWatchWearTheme

/**
 * Chooses which entries the quick log shows and in what order. [items] are the
 * shown ones. The list itself is the shared [ChoiceEditorScreen], the one the
 * dashboard tiles use.
 */
@Composable
fun QuickLogEditorScreen(
    items: List<QuickLogItem>,
    onToggle: (QuickLogItem, Boolean) -> Unit,
    onMove: (moved: QuickLogItem, target: QuickLogItem) -> Unit,
    onReset: () -> Unit,
) {
    ChoiceEditorScreen(
        texts = ChoiceEditorTexts(
            title = stringResource(R.string.quicklog_editor_title),
            activeHeader = stringResource(R.string.quicklog_editor_active_header),
            availableHeader = stringResource(R.string.quicklog_editor_available_header),
            reorderHint = stringResource(R.string.quicklog_editor_reorder_hint),
            resetLabel = stringResource(R.string.tiles_reset_default),
        ),
        available = QuickLogItem.Defaults,
        active = items,
        row = { item -> ChoiceRow(stringResource(item.label), item.entryType.icon, item.entryType.color) },
        onToggle = onToggle,
        onMove = onMove,
        onReset = onReset,
    )
}

@WearPreviews
@Composable
private fun QuickLogEditorScreenPreview() {
    OpenWatchWearTheme {
        AppScaffold {
            QuickLogEditorScreen(
                items = QuickLogItem.Defaults.take(5),
                onToggle = { _, _ -> },
                onMove = { _, _ -> },
                onReset = {},
            )
        }
    }
}
