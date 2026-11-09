package tech.mmarca.openwatch.features.watches

import java.time.LocalTime

/**
 * The hours a scheduled sync leaves the watch alone. A sync wakes the
 * watch's Bluetooth and its app; at night that costs the watch battery for
 * nothing, since every series syncs from a cursor and the first run after
 * [END] takes the whole night. A sync by hand still runs. A test for now:
 * the hours are fixed. Pure.
 */
object WatchAutoSyncQuietHours {

    val START: LocalTime = LocalTime.of(23, 0)
    val END: LocalTime = LocalTime.of(7, 0)

    fun isQuiet(time: LocalTime): Boolean = time >= START || time < END
}
