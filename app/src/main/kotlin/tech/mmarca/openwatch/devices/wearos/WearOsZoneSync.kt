package tech.mmarca.openwatch.devices.wearos

import androidx.health.connect.client.records.RestingHeartRateRecord
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import tech.mmarca.openwatch.domain.insights.HeartRateZoneBasis
import tech.mmarca.openwatch.domain.model.BleSensorDevice
import tech.mmarca.openwatch.domain.preferences.BodyProfile
import tech.mmarca.openwatch.healthconnect.HealthConnectGateway
import tech.mmarca.openwatch.wearlink.WearLinkProtocol.Preferences

/**
 * Hands the watch what its heart rate zones rest on ([HeartRateZoneBasis]):
 * the maximum and the resting heart rate, as two watch settings, after each
 * sync. From the body profile (shared with OpenVitals over OpenLink), the
 * highest heart rate Health Connect holds for the last year and the latest
 * night's resting rate. An extra: a failed read or an old watch app skips
 * it, and it never fails the sync.
 */
internal class WearOsZoneSync(
    private val healthConnect: HealthConnectGateway,
    private val nodePort: WearOsNodePort,
    private val profile: suspend () -> BodyProfile,
    private val clock: () -> Instant,
    private val zone: () -> ZoneId,
) {

    suspend fun sync(device: BleSensorDevice, retry: WearOsNightSeriesSync.Retry) {
        val basis = try {
            val now = clock()
            val observed = healthConnect.readMaxHeartRate(now.minus(OBSERVED_MAX_SPAN), now)?.toInt()
            HeartRateZoneBasis.of(profile(), now.atZone(zone()).toLocalDate(), observed, latestResting(now))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            WearOsLog.log("zones: not read: ${error.javaClass.simpleName}")
            return
        }
        for ((key, bpm) in listOf(Preferences.HEART_RATE_MAX to basis.maxBpm, Preferences.HEART_RATE_REST to basis.restingBpm)) {
            retry.run { nodePort.setWatchSetting(device.address, device.bluetoothName, key, (bpm ?: 0).toString()) }
        }
        WearOsLog.log("zones: sent")
    }

    /** The latest night's resting heart rate of the last week, from any app. */
    private suspend fun latestResting(now: Instant): Int? {
        var latest: RestingHeartRateRecord? = null
        healthConnect.forEachSyncRecordPage(RestingHeartRateRecord::class, now.minus(RESTING_LOOKBACK), now) { page ->
            for (record in page.filterIsInstance<RestingHeartRateRecord>()) if (latest == null || record.time > latest!!.time) latest = record
        }
        return latest?.beatsPerMinute?.toInt()
    }

    private companion object {
        /** How far back the highest heart rate on record is looked for: a year of training. */
        val OBSERVED_MAX_SPAN: Duration = Duration.ofDays(365)
        val RESTING_LOOKBACK: Duration = Duration.ofDays(7)
    }
}
