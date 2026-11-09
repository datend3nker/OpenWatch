package tech.mmarca.openwatch.wear.features.quicklog

import tech.mmarca.openwatch.wearlink.Page
import tech.mmarca.openwatch.wearlink.WatchEntry
import tech.mmarca.openwatch.wearlink.WearLinkDaily
import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.wear.tiles.TileService
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import tech.mmarca.openwatch.wear.tiles.QuickLogTileService

/** What a logged entry records, with the unit of [LoggedEntry.value]. */
enum class EntryType {
    /** Millilitres. */
    WATER,

    /** Kilograms. */
    WEIGHT,

    /** Systolic mmHg; [LoggedEntry.value2] is diastolic. */
    BLOOD_PRESSURE,

    /** Minutes of a breathing session. */
    MINDFULNESS,

    /** Beats per minute from a spot measurement. */
    HEART_RATE,

    /** Milliseconds RMSSD from a spot measurement. */
    HRV,
}

data class LoggedEntry(
    val type: EntryType,
    val timeMillis: Long,
    val value: Double,
    val value2: Double? = null,
)

/**
 * Entries made on the watch: quick logs and spot measurements. They stay
 * here; the phone pulls them (`LG_SINCE`) and writes them to Health Connect. Shared with
 * the quick log tile, which writes to it directly.
 */
class EntryLog(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): List<LoggedEntry> = decodeEntries(prefs.getString(KEY_ENTRIES, null))

    /** [refreshTile] is false when the tile itself logs, as it renders the new total anyway. */
    fun add(entry: LoggedEntry, refreshTile: Boolean = true) {
        val updated = (load() + entry).takeLast(MaxEntries)
        prefs.edit { putString(KEY_ENTRIES, encodeEntries(updated)) }
        if (refreshTile) TileService.getUpdater(appContext).requestUpdate(QuickLogTileService::class.java)
    }

    /** The entries made after [sinceEpochMillis], oldest first, as the phone pulls them. */
    fun entriesSince(sinceEpochMillis: Long, limit: Int): Page<WatchEntry> {
        val newer = load().filter { it.timeMillis > sinceEpochMillis }.sortedBy { it.timeMillis }
        return Page(newer.take(limit).map { it.toWatchEntry() }, more = newer.size > limit)
    }

    /** The log now and after every change, including changes the tile makes. */
    val entries: Flow<List<LoggedEntry>> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_ENTRIES) trySend(load())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        send(load())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    private companion object {
        const val PREFS_NAME = "wear_entry_log"
        const val KEY_ENTRIES = "entries"
        const val MaxEntries = 500
    }
}

/** The entry as the link carries it. */
internal fun LoggedEntry.toWatchEntry(): WatchEntry = WatchEntry(
    epochMillis = timeMillis,
    type = when (type) {
        EntryType.WATER -> WearLinkDaily.WATER
        EntryType.WEIGHT -> WearLinkDaily.WEIGHT
        EntryType.BLOOD_PRESSURE -> WearLinkDaily.BLOOD_PRESSURE
        EntryType.MINDFULNESS -> WearLinkDaily.MINDFULNESS
        EntryType.HEART_RATE -> WearLinkDaily.HEART_RATE
        EntryType.HRV -> WearLinkDaily.HRV
    },
    value = value,
    value2 = value2,
)

/** One entry per line: `TYPE;time;value[;value2]`. */
internal fun encodeEntries(entries: List<LoggedEntry>): String =
    entries.joinToString("\n") { entry ->
        listOfNotNull(entry.type.name, entry.timeMillis, entry.value, entry.value2).joinToString(";")
    }

/** Lines that do not parse, for example from a type that no longer exists, are dropped. */
internal fun decodeEntries(raw: String?): List<LoggedEntry> =
    raw.orEmpty().lineSequence().mapNotNull { line ->
        val parts = line.split(";")
        if (parts.size < 3) return@mapNotNull null
        LoggedEntry(
            type = EntryType.entries.firstOrNull { it.name == parts[0] } ?: return@mapNotNull null,
            timeMillis = parts[1].toLongOrNull() ?: return@mapNotNull null,
            value = parts[2].toDoubleOrNull() ?: return@mapNotNull null,
            value2 = parts.getOrNull(3)?.toDoubleOrNull(),
        )
    }.toList()
