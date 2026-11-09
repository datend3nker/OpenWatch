package tech.mmarca.openwatch.wear

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import tech.mmarca.openwatch.wear.features.quicklog.QuickLogItem

/** Persists which quick log entries show on the watch, and their order. */
class WearQuickLogPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadItems(): List<QuickLogItem> = decodeQuickLogItems(prefs.getString(KEY_ITEMS, null))

    fun saveItems(items: List<QuickLogItem>) {
        prefs.edit { putString(KEY_ITEMS, encodeQuickLogItems(items)) }
    }

    fun resetItems(): List<QuickLogItem> {
        prefs.edit { remove(KEY_ITEMS) }
        return QuickLogItem.Defaults
    }

    private companion object {
        const val PREFS_NAME = "wear_quicklog_prefs"
        const val KEY_ITEMS = "active_items"
    }
}

internal fun encodeQuickLogItems(items: List<QuickLogItem>): String = items.joinToString(",") { it.name }

/**
 * Reads a stored list. Nothing stored means all items in the default order; an
 * empty string means the user switched every item off. Unknown names are dropped.
 */
internal fun decodeQuickLogItems(raw: String?): List<QuickLogItem> {
    if (raw == null) return QuickLogItem.Defaults
    return raw.split(",")
        .mapNotNull { name -> QuickLogItem.entries.firstOrNull { it.name == name.trim() } }
        .distinct()
}

/** Moves [moved] to the position of [target], shifting the items in between. */
internal fun List<QuickLogItem>.withItemMoved(moved: QuickLogItem, target: QuickLogItem): List<QuickLogItem> {
    val targetIndex = indexOf(target)
    if (moved == target || moved !in this || targetIndex < 0) return this
    return toMutableList().apply {
        remove(moved)
        add(targetIndex, moved)
    }
}
