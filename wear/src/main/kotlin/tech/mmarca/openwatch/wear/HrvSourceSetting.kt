package tech.mmarca.openwatch.wear

import android.content.Context

/**
 * Which source the night-time HRV windows use, chosen on the watch.
 *
 * The raw PPG is the default: the app finds the beats itself, on any watch
 * with the sensor. The Samsung Health Platform's own intervals are offered
 * only on a watch that has the platform, and only by choice, because outside
 * the platform's Developer Mode the platform asks Samsung about this app
 * before it answers, and the app cannot tell which mode is on.
 */
internal object HrvSourceSetting {

    enum class Source { RAW_PPG, SAMSUNG_PLATFORM }

    private const val PREFS = "hrv_source"
    private const val KEY = "source"

    fun get(context: Context): Source {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        return Source.entries.firstOrNull { it.name == stored } ?: Source.RAW_PPG
    }

    fun set(context: Context, source: Source) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, source.name).apply()
    }
}
