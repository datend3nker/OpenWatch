package tech.mmarca.openwatch.devices.wearos

import androidx.health.connect.client.records.Record
import java.time.Instant
import kotlinx.coroutines.CancellationException
import tech.mmarca.openwatch.core.presentation.isPermissionFailure
import tech.mmarca.openwatch.data.repository.ImportedRecordRepository
import tech.mmarca.openwatch.data.repository.RecordWrite
import tech.mmarca.openwatch.data.repository.insertWithOutcomes

/**
 * Writes one series of the watch's night-time windows (the HRV, the
 * breathing rate) to Health Connect, one page at a time from the phone's
 * cursor; the cursor moves after each page is written, so a sync that dies
 * mid-way resumes where it stopped.
 *
 * Without the write grant nothing is written and the cursor stays, so the
 * windows wait on the watch (it keeps a week) for the next sync after the
 * grant. That never fails the sync.
 */
internal class WearOsWindowSync<W>(
    private val importRepository: ImportedRecordRepository,
    private val granted: suspend () -> Set<String>,
    private val writePermission: String,
    private val cursor: (deviceId: String) -> Instant,
    private val setCursor: (deviceId: String, at: Instant) -> Unit,
    private val startOf: (W) -> Instant,
    /** Null for a value Health Connect would refuse; it is dropped, never retried. */
    private val record: (W) -> Record?,
    /** The series' name in the sync log. */
    private val label: String,
) {

    /** [written] records, and whether the missing write grant [withheld] some windows. */
    class Outcome(val written: Int, val withheld: Boolean)

    /** Null when [pullPage] has no bonded watch to ask. A failure other than a missing grant throws. */
    suspend fun sync(deviceId: String, pullPage: suspend (Instant) -> Pair<List<W>, Boolean>?): Outcome? {
        var since = cursor(deviceId)
        val allowed = writePermission in granted()
        var written = 0
        var pages = 0
        while (true) {
            val (windows, hasMore) = pullPage(since) ?: return null
            if (windows.isEmpty()) break
            if (!allowed) return Outcome(written, withheld = true)
            val records = windows.mapNotNull(record)
            if (records.size < windows.size) WearOsLog.log("$label: ${windows.size - records.size} window(s) not written, Health Connect refused the value")
            written += try {
                importRepository.insertWithOutcomes(records).count { it == RecordWrite.WRITTEN }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (error !is SecurityException && !error.isPermissionFailure()) throw error
                return Outcome(written, withheld = true)
            }
            val newest = windows.maxOf(startOf)
            setCursor(deviceId, newest)
            since = newest
            if (!hasMore) break
            if (++pages >= MAX_PAGES) break
        }
        return Outcome(written, withheld = false)
    }

    private companion object {
        /** A safety net against a watch that always says "more", as the other pulls have. */
        const val MAX_PAGES = 1000
    }
}
