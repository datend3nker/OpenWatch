package tech.mmarca.openwatch.devices.wearos

import androidx.health.connect.client.records.RestingHeartRateRecord
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import tech.mmarca.healthcalculations.insights.HeartRateZoneBasis
import tech.mmarca.healthcalculations.insights.observedMaxHeartRateLookbackDays
import tech.mmarca.healthcalculations.preferences.BiologicalSex
import tech.mmarca.healthcalculations.preferences.BodyProfile
import tech.mmarca.openwatch.domain.model.BleSensorDevice
import tech.mmarca.openwatch.healthconnect.HealthConnectGateway
import tech.mmarca.openwatch.wearlink.WearLinkPreferences as Preferences

/**
 * Hands the watch what its heart rate zones rest on ([HeartRateZoneBasis],
 * from health-calculations, the same as OpenVitals'): the maximum and the
 * resting heart rate, as two watch settings, after each sync. From the body
 * profile (shared with OpenVitals over OpenLink), the highest heart rate
 * Health Connect holds for the year before and the latest night's resting
 * rate, which only decides whether that maximum is believable. Without a
 * basis both are `0`. Beside them the profile a workout's energy needs
 * (weight, height, birth year, sex), from [energyProfile]. Extras: a failed
 * read skips only what it was for (a missing weight grant still sends the
 * zones), an old watch app skips both, and neither ever fails the sync.
 * Also the daily goals the connected apps share ([goals]).
 */
internal class WearOsZoneSync(
    private val healthConnect: HealthConnectGateway,
    private val nodePort: WearOsNodePort,
    private val profile: suspend () -> BodyProfile,
    private val energyProfile: suspend () -> BodyProfile,
    private val clock: () -> Instant,
    private val zone: () -> ZoneId,
    /** The daily goals the connected apps share, as watch settings; a goal never set is not in it. */
    private val goals: suspend () -> List<Pair<String, String>> = { emptyList() },
) {

    suspend fun sync(device: BleSensorDevice, retry: WearOsNightSeriesSync.Retry) {
        val zones = read("zones") {
            val now = clock()
            val observed = healthConnect.readMaxHeartRate(now.minus(Duration.ofDays(observedMaxHeartRateLookbackDays)), now)?.toInt()
            val basis = HeartRateZoneBasis.of(profile(), now.atZone(zone()).toLocalDate(), observed, latestResting(now))
            listOf(
                Preferences.HEART_RATE_MAX to (basis?.maxBpm ?: 0).toString(),
                Preferences.HEART_RATE_REST to (basis?.restingBpm ?: 0).toString(),
            )
        }
        val body = read("profile") {
            val body = energyProfile()
            listOf(
                Preferences.BODY_WEIGHT to (body.weightKg?.let { Math.round(it * 10).toInt() } ?: 0).toString(),
                Preferences.BODY_HEIGHT to (body.heightCm?.let { Math.round(it).toInt() } ?: 0).toString(),
                Preferences.BIRTH_YEAR to (body.birthYear ?: 0).toString(),
                Preferences.SEX to when (body.sex) { BiologicalSex.FEMALE -> "f"; BiologicalSex.MALE -> "m"; null -> "0" },
            )
        }
        val shared = read("goals") { goals() }
        for ((key, value) in zones.orEmpty() + body.orEmpty() + shared.orEmpty()) {
            retry.run { nodePort.setWatchSetting(device.address, device.bluetoothName, key, value) }
        }
        if (zones != null || body != null || !shared.isNullOrEmpty()) WearOsLog.log("zones, profile and goals: sent")
    }

    /** The settings [settings] reads; null, logged by [what], when a read fails. */
    private suspend fun read(what: String, settings: suspend () -> List<Pair<String, String>>): List<Pair<String, String>>? =
        try {
            settings()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            WearOsLog.log("$what: not read: ${error.javaClass.simpleName}")
            null
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
        val RESTING_LOOKBACK: Duration = Duration.ofDays(7)
    }
}
