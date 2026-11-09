package tech.mmarca.openwatch.devices.wearos

import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import tech.mmarca.openwatch.data.repository.ImportedRecordRepository
import tech.mmarca.openwatch.data.repository.RecordWrite
import tech.mmarca.openwatch.data.repository.insertWithOutcomes
import tech.mmarca.openwatch.domain.insights.HeartRateZoneBasis
import tech.mmarca.openwatch.domain.model.BleSensorDevice
import tech.mmarca.openwatch.domain.preferences.BiologicalSex
import tech.mmarca.openwatch.domain.preferences.BodyProfile
import tech.mmarca.openwatch.healthconnect.HealthConnectGateway
import tech.mmarca.openwatch.core.presentation.isPermissionFailure
import tech.mmarca.openwatch.wearlink.WearLinkDaily
import tech.mmarca.openwatch.wearlink.WearLinkPreferences as Preferences

/**
 * The watch app's own screens, both ways: the entries made on the watch
 * (water, weight, blood pressure, a breathing session, a spot heart rate or
 * HRV) come to Health Connect, and the phone's figures for the last days
 * (the night, the resting heart rate, active energy, distance) go to the
 * watch. Both are extras: a missing grant or an old watch app skips them,
 * and neither fails the sync.
 */
internal class WearOsDayLinkSync(
    private val importRepository: ImportedRecordRepository,
    private val healthConnect: HealthConnectGateway,
    private val cursors: WearOsSyncCursorStore,
    private val nodePort: WearOsNodePort,
    private val clock: () -> Instant,
    private val zone: () -> ZoneId,
    /** The body profile, which OpenLink shares with OpenVitals: the zones' maximum and resting heart rate. */
    private val profile: suspend () -> BodyProfile = { BodyProfile() },
) {

    /** Null when no bonded watch matches. A link failure throws. */
    suspend fun sync(device: BleSensorDevice, retry: WearOsNightSeriesSync.Retry): Unit? {
        writeEntries(device, retry) ?: return null
        pushDays(device, retry)
        return Unit
    }

    /**
     * Each entry whose grant is in, page by page from the cursor. The cursor
     * moves only past a page written whole, so an entry a grant held back is
     * pulled again (and its siblings written again under the same ids).
     */
    private suspend fun writeEntries(device: BleSensorDevice, retry: WearOsNightSeriesSync.Retry): Unit? {
        val granted = healthConnect.grantedPermissions()
        val mindfulness = healthConnect.isMindfulnessAvailable()
        var since = cursors.entryCursor(device.id)
        var written = 0
        repeat(MAX_PAGES) {
            val page = retry.run { nodePort.pullEntries(device.address, device.bluetoothName, since) } ?: return null
            if (page.entries.isEmpty()) return done(written)
            val writable = page.entries.filter { entry ->
                WearOsEntryImport.writePermissions[entry.type] in granted &&
                    (entry.type != WearLinkDaily.MINDFULNESS || mindfulness)
            }
            val records = writable.mapNotNull { WearOsEntryImport.record(it, zone()) }
            written += try {
                importRepository.insertWithOutcomes(records).count { it == RecordWrite.WRITTEN }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (error !is SecurityException && !error.isPermissionFailure()) throw error
                WearOsLog.log("entries: not written, Health Connect permission missing")
                return done(written)
            }
            if (writable.size < page.entries.size) {
                WearOsLog.log("entries: ${page.entries.size - writable.size} held back, a grant or feature is missing")
                return done(written)
            }
            since = Instant.ofEpochMilli(page.entries.maxOf { it.epochMillis })
            cursors.setEntryCursor(device.id, since)
            if (!page.hasMore) return done(written)
        }
        return done(written)
    }

    private fun done(written: Int): Unit {
        WearOsLog.log("entries: $written written")
        return Unit
    }

    /** The last [DAYS] days to the watch; what cannot be read stays out. Never fails the sync. */
    private suspend fun pushDays(device: BleSensorDevice, retry: WearOsNightSeriesSync.Retry) {
        val zone = zone()
        val days = WearOsDaySummaries.lastDays(clock(), zone, DAYS)
        val summaries = try {
            val from = days.first().minusDays(1).atStartOfDay(zone).toInstant()
            val to = days.last().plusDays(1).atStartOfDay(zone).toInstant()
            val sleep = ArrayList<SleepSessionRecord>()
            val resting = ArrayList<RestingHeartRateRecord>()
            healthConnect.forEachSyncRecordPage(SleepSessionRecord::class, from, to) { page -> page.filterIsInstanceTo(sleep) }
            healthConnect.forEachSyncRecordPage(RestingHeartRateRecord::class, from, to) { page -> page.filterIsInstanceTo(resting) }
            val totals = healthConnect.readDayTotals(days.first(), days.last())
                .mapValues { (_, value) -> WearOsDaySummaries.Totals(value.first, value.second) }
            WearOsDaySummaries.summaries(days, zone, sleep, resting, totals)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            WearOsLog.log("days: not read: ${error.javaClass.simpleName}")
            return
        }
        val pushed = retry.run { nodePort.pushDays(device.address, device.bluetoothName, summaries) }
        WearOsLog.log("days: ${if (pushed) summaries.size else 0} sent")
        pushZones(device, retry, summaries.lastOrNull { it.restingBpm != null }?.restingBpm)
    }

    /**
     * The zones' maximum and resting heart rate ([HeartRateZoneBasis]) as the
     * watch's settings; `0` clears one the profile no longer gives. Never
     * fails the sync.
     */
    private suspend fun pushZones(device: BleSensorDevice, retry: WearOsNightSeriesSync.Retry, nightResting: Int?) {
        val basis = try {
            val now = clock()
            val observed = healthConnect.readMaxHeartRate(now.minus(OBSERVED_MAX_SPAN), now)?.toInt()
            HeartRateZoneBasis.of(profile(), now.atZone(zone()).toLocalDate(), observed, nightResting)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            WearOsLog.log("zones: not read: ${error.javaClass.simpleName}")
            return
        }
        val body = profile()
        val values = listOf(
            Preferences.HEART_RATE_MAX to (basis.maxBpm ?: 0).toString(),
            Preferences.HEART_RATE_REST to (basis.restingBpm ?: 0).toString(),
            Preferences.BODY_WEIGHT to (body.weightKg?.let { Math.round(it * 10).toInt() } ?: 0).toString(),
            Preferences.BODY_HEIGHT to (body.heightCm?.let { Math.round(it).toInt() } ?: 0).toString(),
            Preferences.BIRTH_YEAR to (body.birthYear ?: 0).toString(),
            Preferences.SEX to when (body.sex) { BiologicalSex.FEMALE -> "f"; BiologicalSex.MALE -> "m"; null -> "0" },
        )
        for ((key, value) in values) retry.run { nodePort.setWatchSetting(device.address, device.bluetoothName, key, value) }
        WearOsLog.log("zones and profile: sent")
    }

    private companion object {
        const val DAYS = 7
        const val MAX_PAGES = 100

        /** How far back the highest heart rate on record is looked for: a year of training. */
        val OBSERVED_MAX_SPAN: Duration = Duration.ofDays(365)
    }
}
