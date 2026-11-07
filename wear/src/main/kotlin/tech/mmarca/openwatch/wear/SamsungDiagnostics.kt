package tech.mmarca.openwatch.wear

import android.content.ContentResolver
import android.provider.Settings

/**
 * Samsung's diagnostic-data consent, read from the public system settings.
 *
 * The Samsung Health Platform sends its analytics events only while this
 * setting is 1 (see `docs/engineering/samsung-health-and-privacy.md`). The
 * app can read it but not change it, and it cannot see or set a network rule
 * for another package, so the status screen shows this value and nothing more.
 */
internal object SamsungDiagnostics {
    private const val KEY = "setting_diagnostic_data_agree"

    /** True when the consent is on, false when off, null when the watch has no such setting. */
    fun consentOn(resolver: ContentResolver): Boolean? =
        try {
            Settings.System.getInt(resolver, KEY) != 0
        } catch (_: Settings.SettingNotFoundException) {
            null
        }
}
