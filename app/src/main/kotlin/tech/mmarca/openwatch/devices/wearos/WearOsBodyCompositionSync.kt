package tech.mmarca.openwatch.devices.wearos

import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import tech.mmarca.openwatch.core.presentation.isPermissionFailure
import tech.mmarca.openwatch.data.repository.ImportedRecordRepository
import tech.mmarca.openwatch.data.repository.RecordWrite
import tech.mmarca.openwatch.data.repository.insertWithOutcomes
import tech.mmarca.openwatch.domain.model.BleSensorDevice
import tech.mmarca.openwatch.healthconnect.HealthConnectGateway

/**
 * Writes the body composition measurements taken on the watch to Health
 * Connect, a page at a time from the phone's cursor.
 *
 * A measurement is three records with a write permission each. The ones
 * that are granted are written; the cursor moves only when all three are, so
 * a measurement waits on the watch (it keeps a month) for the grant that is
 * missing, and what was already written is written again as the same
 * records. A missing grant never fails the sync.
 */
internal class WearOsBodyCompositionSync(
    private val importRepository: ImportedRecordRepository,
    private val healthConnect: HealthConnectGateway,
    private val cursors: WearOsSyncCursorStore,
    private val nodePort: WearOsNodePort,
    private val zone: () -> ZoneId,
) {
    /** [written] records, and the write grants that were missing. */
    class Outcome(val written: Int, val withheld: Set<String>)

    /** Null when no bonded watch matches. A failure other than a missing grant throws. */
    suspend fun sync(device: BleSensorDevice, retry: WearOsNightSeriesSync.Retry): Outcome? {
        var since = cursors.bodyCompositionCursor(device.id)
        var granted: Set<String>? = null
        var written = 0
        repeat(MAX_PAGES) {
            val page = retry.run { nodePort.pullBodyComposition(device.address, device.bluetoothName, since) } ?: return null
            if (page.measurements.isEmpty()) return Outcome(written, emptySet())
            // Asked only when there is something to write: most syncs carry no measurement.
            val allowed = granted ?: healthConnect.grantedPermissions().also { granted = it }
            val missing = WearOsBodyCompositionImport.writePermissions - allowed
            val records = page.measurements.flatMap { WearOsBodyCompositionImport.records(it, zone(), allowed) }
            written += try {
                importRepository.insertWithOutcomes(records).count { it == RecordWrite.WRITTEN }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (error !is SecurityException && !error.isPermissionFailure()) throw error
                return Outcome(written, WearOsBodyCompositionImport.writePermissions)
            }
            if (missing.isNotEmpty()) return Outcome(written, missing)
            since = page.measurements.maxOf { it.time }
            cursors.setBodyCompositionCursor(device.id, since)
            if (!page.hasMore) return Outcome(written, emptySet())
        }
        return Outcome(written, emptySet())
    }

    private companion object {
        /** A safety net against a watch that always says "more", as the other pulls have. */
        const val MAX_PAGES = 1000
    }
}
