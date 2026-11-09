package tech.mmarca.openwatch.wear.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/** What a row of [ChoiceEditorScreen] shows for one choice. */
data class ChoiceRow(val label: String, val icon: ImageVector, val tint: Color)

/** The fixed texts of a [ChoiceEditorScreen]. */
data class ChoiceEditorTexts(
    val title: String,
    val activeHeader: String,
    val availableHeader: String,
    val reorderHint: String,
    val resetLabel: String,
    /** Shown last, under the reset button; null for none. */
    val footerHint: String? = null,
)

/**
 * Switches choices on and off and reorders the active ones. Active choices are
 * reordered by long-press and drag; tapping a row switches it on or off. Only
 * [available] choices are listed. The dashboard tiles and the quick log entries
 * both use it; [row] gives a choice its label, icon and colour.
 *
 * This uses a plain [LazyColumn] rather than a TransformingLazyColumn, because
 * the reorder library only drives a LazyListState.
 */
@Composable
fun <T : Any> ChoiceEditorScreen(
    texts: ChoiceEditorTexts,
    available: List<T>,
    active: List<T>,
    row: @Composable (T) -> ChoiceRow,
    onToggle: (T, Boolean) -> Unit,
    onMove: (moved: T, target: T) -> Unit,
    onReset: () -> Unit,
) {
    val listState = rememberLazyListState()
    val haptics = LocalHapticFeedback.current
    val inactive = available.filterNot { it in active }
    // Only active choices are ReorderableItems, so a drag never lands on a header
    // or on an inactive row.
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        // Keys of headers and hints match no choice, so they are ignored.
        val moved = active.firstOrNull { it == from.key }
        val target = active.firstOrNull { it == to.key }
        if (moved != null && target != null) onMove(moved, target)
    }

    ScreenScaffold(scrollState = listState) { contentPadding ->
        LazyColumn(
            state = listState,
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item(key = "title") {
                ListHeader { Text(texts.title) }
            }

            if (active.isNotEmpty()) {
                item(key = "active_header") {
                    ListHeader { Text(texts.activeHeader) }
                }
                items(active, key = { it }) { choice ->
                    ReorderableItem(reorderState, key = choice) { isDragging ->
                        ChoiceSwitch(
                            row = row(choice),
                            checked = true,
                            onCheckedChange = { onToggle(choice, it) },
                            modifier = Modifier
                                .longPressDraggableHandle(
                                    onDragStarted = {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    },
                                )
                                .scale(if (isDragging) 1.05f else 1f),
                        )
                    }
                }
                item(key = "reorder_hint") {
                    Hint(texts.reorderHint)
                }
            }

            if (inactive.isNotEmpty()) {
                item(key = "inactive_header") {
                    ListHeader { Text(texts.availableHeader) }
                }
                items(inactive, key = { it }) { choice ->
                    ChoiceSwitch(
                        row = row(choice),
                        checked = false,
                        onCheckedChange = { onToggle(choice, it) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }

            item(key = "reset") {
                Button(
                    onClick = onReset,
                    label = { Text(texts.resetLabel) },
                    icon = { Icon(Icons.Outlined.Refresh, contentDescription = null) },
                    colors = ButtonDefaults.outlinedButtonColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            texts.footerHint?.let { footer ->
                item(key = "footer_hint") { Hint(footer) }
            }
        }
    }
}

@Composable
private fun ChoiceSwitch(
    row: ChoiceRow,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    SwitchButton(
        checked = checked,
        onCheckedChange = onCheckedChange,
        label = { Text(row.label) },
        icon = { Icon(row.icon, contentDescription = null, tint = row.tint) },
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    )
}
