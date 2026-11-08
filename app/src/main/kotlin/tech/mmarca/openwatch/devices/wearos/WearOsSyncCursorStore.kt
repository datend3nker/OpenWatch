package tech.mmarca.openwatch.devices.wearos

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext

/**
 * How far each Wear OS watch has been pulled, per device id: the time of the
 * newest heart rate sample, step minute and floor minute pulled, and the
 * time of the newest sleep minute an estimate was made from. The next sync asks the
 * watch for what is newer. A re-pull is harmless: the imports' record ids
 * are deterministic, so an upsert follows.
 */
@Singleton
class WearOsSyncCursorStore(private val prefs: SharedPreferences) {

    @Inject
    constructor(@ApplicationContext context: Context) : this(
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE),
    )

    /** The cursor, or [Instant.EPOCH] for a watch never synced. */
    fun heartRateCursor(deviceId: String): Instant =
        Instant.ofEpochMilli(prefs.getLong(key(deviceId), 0L))

    fun setHeartRateCursor(deviceId: String, at: Instant) {
        prefs.edit { putLong(key(deviceId), at.toEpochMilli()) }
    }

    /** The sleep cursor, or [Instant.EPOCH] for a watch never synced. */
    fun sleepCursor(deviceId: String): Instant =
        Instant.ofEpochMilli(prefs.getLong(sleepKey(deviceId), 0L))

    fun setSleepCursor(deviceId: String, at: Instant) {
        prefs.edit { putLong(sleepKey(deviceId), at.toEpochMilli()) }
    }

    /** The step cursor, or [Instant.EPOCH] for a watch never synced. */
    fun stepsCursor(deviceId: String): Instant =
        Instant.ofEpochMilli(prefs.getLong(stepsKey(deviceId), 0L))

    fun setStepsCursor(deviceId: String, at: Instant) {
        prefs.edit { putLong(stepsKey(deviceId), at.toEpochMilli()) }
    }

    /** The floor cursor, or [Instant.EPOCH] for a watch never synced. */
    fun ascentCursor(deviceId: String): Instant =
        Instant.ofEpochMilli(prefs.getLong(ascentKey(deviceId), 0L))

    fun setAscentCursor(deviceId: String, at: Instant) {
        prefs.edit { putLong(ascentKey(deviceId), at.toEpochMilli()) }
    }

    /** The HRV cursor: the start of the newest window written. [Instant.EPOCH] for a watch never synced. */
    fun hrvCursor(deviceId: String): Instant =
        Instant.ofEpochMilli(prefs.getLong(hrvKey(deviceId), 0L))

    fun setHrvCursor(deviceId: String, at: Instant) {
        prefs.edit { putLong(hrvKey(deviceId), at.toEpochMilli()) }
    }

    /** The breathing rate cursor: the start of the newest window written. [Instant.EPOCH] for a watch never synced. */
    fun respiratoryCursor(deviceId: String): Instant =
        Instant.ofEpochMilli(prefs.getLong(respiratoryKey(deviceId), 0L))

    fun setRespiratoryCursor(deviceId: String, at: Instant) {
        prefs.edit { putLong(respiratoryKey(deviceId), at.toEpochMilli()) }
    }

    /** The SpO2 cursor: the time of the newest measurement written. [Instant.EPOCH] for a watch never synced. */
    fun spo2Cursor(deviceId: String): Instant =
        Instant.ofEpochMilli(prefs.getLong(spo2Key(deviceId), 0L))

    fun setSpo2Cursor(deviceId: String, at: Instant) {
        prefs.edit { putLong(spo2Key(deviceId), at.toEpochMilli()) }
    }

    /** The skin temperature cursor: the end of the newest night run written. [Instant.EPOCH] for a watch never synced. */
    fun skinCursor(deviceId: String): Instant =
        Instant.ofEpochMilli(prefs.getLong(skinKey(deviceId), 0L))

    fun setSkinCursor(deviceId: String, at: Instant) {
        prefs.edit { putLong(skinKey(deviceId), at.toEpochMilli()) }
    }

    /** The medians of the nights written so far, oldest first: the skin temperature baseline's memory. */
    fun skinNightMedians(deviceId: String): List<Double> =
        prefs.getString(skinMediansKey(deviceId), null).orEmpty().split(',').mapNotNull { it.toDoubleOrNull() }

    fun setSkinNightMedians(deviceId: String, medians: List<Double>) {
        prefs.edit { putString(skinMediansKey(deviceId), medians.joinToString(",")) }
    }

    fun clear(deviceId: String) {
        prefs.edit {
            remove(key(deviceId))
            remove(sleepKey(deviceId))
            remove(stepsKey(deviceId))
            remove(ascentKey(deviceId))
            remove(hrvKey(deviceId))
            remove(respiratoryKey(deviceId))
            remove(spo2Key(deviceId))
            remove(skinKey(deviceId))
            remove(skinMediansKey(deviceId))
        }
    }

    private fun key(deviceId: String) = "hr_cursor_$deviceId"

    private fun sleepKey(deviceId: String) = "sleep_cursor_$deviceId"

    private fun stepsKey(deviceId: String) = "steps_cursor_$deviceId"

    private fun ascentKey(deviceId: String) = "ascent_cursor_$deviceId"

    private fun hrvKey(deviceId: String) = "hrv_cursor_$deviceId"

    private fun respiratoryKey(deviceId: String) = "respiratory_cursor_$deviceId"

    private fun spo2Key(deviceId: String) = "spo2_cursor_$deviceId"

    private fun skinKey(deviceId: String) = "skin_cursor_$deviceId"

    private fun skinMediansKey(deviceId: String) = "skin_medians_$deviceId"

    private companion object {
        const val PREFS_FILE = "wearos_sync_cursors"
    }
}
