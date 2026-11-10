package tech.mmarca.openwatch.wear

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.time.LocalDate
import tech.mmarca.openwatch.wearlink.DaySummary
import tech.mmarca.openwatch.wearlink.WearLinkDaily

/**
 * The phone's figures per day (`PUT_DAY`): the night, the resting heart
 * rate, the walking energy and the distance, which the watch cannot work
 * out by itself. Kept for [KEEP_DAYS] days in the line format of the link.
 */
class DaySummaryStore(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun put(day: DaySummary) {
        prefs.edit {
            putString(key(day.epochDay), WearLinkDaily.formatPutDay(day))
            for (old in prefs.all.keys) {
                val epochDay = old.removePrefix(KEY_PREFIX).toLongOrNull() ?: continue
                if (epochDay <= day.epochDay - KEEP_DAYS) remove(old)
            }
        }
    }

    /** Every kept day by its day number. */
    fun all(): Map<Long, DaySummary> = prefs.all.values
        .mapNotNull { (it as? String)?.let(WearLinkDaily::parsePutDay) }
        .associateBy { it.epochDay }

    /** The phone's figures for the local day that is running, or null before it sent any. */
    fun today(): DaySummary? =
        (prefs.getString(key(LocalDate.now().toEpochDay()), null))?.let(WearLinkDaily::parsePutDay)

    private fun key(epochDay: Long) = "$KEY_PREFIX$epochDay"

    private companion object {
        const val PREFS_NAME = "wear_day_summaries"
        const val KEY_PREFIX = "day_"
        const val KEEP_DAYS = 14
    }
}
