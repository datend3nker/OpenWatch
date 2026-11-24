package tech.mmarca.openwatch.wear.samsung

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import androidx.core.content.edit

/**
 * The tracker types the Samsung Health Platform lists on this watch (code 5), kept in a
 * preferences file of their own, so what the watch offers is read without waiting on the
 * platform: [refresh] asks it again, without blocking, at each start of the watch app's service;
 * [read] is the last answer, empty before the first one. An ECG or a body composition is offered
 * only where its type is in it.
 */
internal object SamsungTrackerTypes {

    fun read(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_TYPES, null).orEmpty()

    /** Asks the platform for its types and keeps the answer; no answer keeps the last one. */
    fun refresh(context: Context) {
        val app = context.applicationContext
        if (!SamsungHealthPlatform.isInstalled(app)) return
        // The answer is a binder call made in the connection's callback: off the main thread.
        val thread = HandlerThread("SamsungTrackerTypes").apply { start() }
        SamsungHealthPlatform.supportedTypes(app, Handler(thread.looper)) { types ->
            if (types != null) app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putStringSet(KEY_TYPES, types) }
            thread.quitSafely()
        }
    }

    private const val PREFS = "samsung_platform"
    private const val KEY_TYPES = "tracker_types"
}
