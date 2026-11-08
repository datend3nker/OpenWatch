package tech.mmarca.openwatch.devices.wearos

import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import tech.mmarca.openwatch.core.presentation.isPermissionFailure
import tech.mmarca.openwatch.data.repository.ImportedRecordRepository
import tech.mmarca.openwatch.data.repository.RecordWrite
import tech.mmarca.openwatch.data.repository.insertWithOutcomes

/**
 * Writes the watch's night-time HRV windows to Health Connect, one page at
 * a time from the phone's cursor ([WearOsSyncCursorStore.hrvCursor]); the
 * cursor moves after each page is written, so a sync that dies mid-way
 * resumes where it stopped.
 *
 * Without the write grant nothing is written and the cursor stays, so the
 * windows wait on the watch (it keeps a week) for the next sync after the
 * grant. That never fails the sync.
 */
internal class WearOsHrvSync(
    private val importRepository: ImportedRecordRepository,
    private val cursors: WearOsSyncCursorStore,
    private val granted: suspend () -> Set<String>,
    private val zone: () -> ZoneId,
) {

    /** [written] records, and whether the missing write grant [withheld] some windows. */
    class Outcome(val written: Int, val withheld: Boolean)

    /** Null when [pullPage] has no bonded watch to ask. A failure other than a missing grant throws. */
    suspend fun sync(deviceId: String, pullPage: suspend (Instant) -> WearOsHrvPage?): Outcome? {
        var since = cursors.hrvCursor(deviceId)
        val allowed = WearOsHrvImport.writePermission in granted()
        var written = 0
        var pages = 0
        while (true) {
            val page = pullPage(since) ?: return null
            if (page.windows.isEmpty()) break
            if (!allowed) return Outcome(written, withheld = true)
            val records = page.windows.mapNotNull { WearOsHrvImport.record(it, zone()) }
            if (records.size < page.windows.size) WearOsLog.log("hrv: ${page.windows.size - records.size} window(s) not written, Health Connect refused the value")
            written += try {
                importRepository.insertWithOutcomes(records).count { it == RecordWrite.WRITTEN }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (error !is SecurityException && !error.isPermissionFailure()) throw error
                return Outcome(written, withheld = true)
            }
            val newest = page.windows.maxOf { it.start }
            cursors.setHrvCursor(deviceId, newest)
            since = newest
            if (!page.hasMore) break
            if (++pages >= MAX_PAGES) break
        }
        return Outcome(written, withheld = false)
    }

    private companion object {
        /** A safety net against a watch that always says "more", as the other pulls have. */
        const val MAX_PAGES = 1000
    }
}
