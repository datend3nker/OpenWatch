package tech.mmarca.openwatch.healthconnect

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.reflect.KClass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tech.mmarca.openwatch.core.performance.AppForegroundGate
import tech.mmarca.openwatch.domain.model.HealthConnectAvailability

/**
 * The one place that touches the Health Connect client. The devices write
 * through it and read the little they need: the granted set, a weight, the
 * workouts a minute falls in, the sleep sessions a night already holds.
 *
 * Strict by construction: a read that fails throws. Nothing here turns a
 * refusal into an empty answer, because every caller saves what it read.
 */
@Singleton
class HealthConnectGateway(
    private val context: Context,
    private val syncGate: HealthConnectSyncGate,
    private val foregroundGate: AppForegroundGate,
    private val clientProvider: () -> HealthConnectClient,
    private val elapsedMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        syncGate: HealthConnectSyncGate,
        foregroundGate: AppForegroundGate,
    ) : this(context, syncGate, foregroundGate, CachedClient(context))

    private val diagnostics = HealthConnectDiagnostics(context)
    private val availabilityService = HealthConnectAvailabilityService(
        context = context,
        diagnostics = diagnostics,
        foregroundEpoch = { foregroundGate.foregroundEpoch },
    )

    fun availability(): HealthConnectAvailability = availabilityService.availability()

    fun permissionContract() = PermissionController.createRequestPermissionResultContract()

    /** Every health permission the manifest declares: what the setup screen asks for. */
    val declaredPermissions: Set<String> by lazy { declaredHealthPermissions(context) }

    private class CachedPermissions(val permissions: Set<String>, val loadedAtMs: Long, val epoch: Int)

    @Volatile
    private var grantedPermissionsCache: CachedPermissions? = null
    private val grantedPermissionsMutex = Mutex()

    /**
     * The granted set, kept for thirty seconds and dropped when the app comes back to
     * the foreground, since the Health Connect permission screen is another app. A
     * failed call is never cached.
     */
    suspend fun grantedPermissions(): Set<String> {
        freshGrantedPermissions()?.let { return it }
        return grantedPermissionsMutex.withLock {
            freshGrantedPermissions()?.let { return@withLock it }
            loadGrantedPermissions()
        }
    }

    /** After a permission request: the next call asks again, and so does the provider status. */
    fun invalidateGrantedPermissions() {
        grantedPermissionsCache = null
        availabilityService.invalidate()
    }

    private fun freshGrantedPermissions(): Set<String>? {
        val nowMs = elapsedMillis()
        return grantedPermissionsCache
            ?.takeIf { it.epoch == foregroundGate.foregroundEpoch && nowMs - it.loadedAtMs <= GrantedPermissionsCacheMillis }
            ?.permissions
    }

    private suspend fun loadGrantedPermissions(): Set<String> {
        val granted = withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Platform permissions: a local check, no call into the provider.
                declaredPermissions.filterTo(mutableSetOf()) { permission ->
                    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
                }
            } else {
                clientProvider().permissionController.getGrantedPermissions()
            }
        }
        grantedPermissionsCache = CachedPermissions(granted, elapsedMillis(), foregroundGate.foregroundEpoch)
        Log.d(TAG, "grantedPermissions count=${granted.size} ${diagnostics.summary()}")
        return granted
    }

    /** True when this Health Connect stores skin temperature (a feature of newer versions). */
    fun isSkinTemperatureAvailable(): Boolean {
        if (availability() != HealthConnectAvailability.AVAILABLE) return false
        return runCatching {
            clientProvider().features.getFeatureStatus(HealthConnectFeatures.FEATURE_SKIN_TEMPERATURE)
        }.getOrNull() == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
    }

    /** True when this Health Connect stores mindfulness sessions (a feature of newer versions). */
    fun isMindfulnessAvailable(): Boolean {
        if (availability() != HealthConnectAvailability.AVAILABLE) return false
        return runCatching {
            clientProvider().features.getFeatureStatus(HealthConnectFeatures.FEATURE_MINDFULNESS_SESSION)
        }.getOrNull() == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
    }

    /**
     * The active energy (kcal) and distance (m) per local day from [first] to [last], from
     * any app. A metric whose read grant is missing is not asked for and stays null.
     */
    suspend fun readDayTotals(first: LocalDate, last: LocalDate): Map<LocalDate, Pair<Double?, Double?>> {
        val granted = grantedPermissions()
        val metrics = buildSet {
            if (HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class) in granted) add(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)
            if (HealthPermission.getReadPermission(DistanceRecord::class) in granted) add(DistanceRecord.DISTANCE_TOTAL)
        }
        if (metrics.isEmpty()) return emptyMap()
        val groups = withContext(Dispatchers.IO) {
            clientProvider().aggregateGroupByPeriod(
                AggregateGroupByPeriodRequest(
                    metrics = metrics,
                    timeRangeFilter = TimeRangeFilter.between(first.atStartOfDay(), last.plusDays(1).atStartOfDay()),
                    timeRangeSlicer = Period.ofDays(1),
                ),
            )
        }
        return groups.associate { group ->
            group.startTime.toLocalDate() to Pair(
                group.result[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories,
                group.result[DistanceRecord.DISTANCE_TOTAL]?.inMeters,
            )
        }
    }

    /** The highest heart rate Health Connect holds in `[start, end)`, from any app; null without the read grant or a sample. */
    suspend fun readMaxHeartRate(start: Instant, end: Instant): Long? {
        if (HealthPermission.getReadPermission(HeartRateRecord::class) !in grantedPermissions()) return null
        return withContext(Dispatchers.IO) {
            clientProvider().aggregate(
                AggregateRequest(metrics = setOf(HeartRateRecord.BPM_MAX), timeRangeFilter = TimeRangeFilter.between(start, end)),
            )[HeartRateRecord.BPM_MAX]
        }
    }

    /** True when this Health Connect offers the background-read grant at all. */
    fun isBackgroundReadAvailable(): Boolean {
        if (availability() != HealthConnectAvailability.AVAILABLE) return false
        return runCatching {
            clientProvider().features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND)
        }.getOrNull() == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
    }

    /**
     * False in the background without the background-read grant. Health Connect then answers
     * a read with this app's own records only, and says nothing. A caller that saves what it
     * read checks this first and skips the pass. The foreground here is a started Activity.
     */
    suspend fun readsOtherAppsDataNow(): Boolean {
        if (foregroundGate.isForeground) return true
        if (!isBackgroundReadAvailable()) return false
        return HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND in grantedPermissions()
    }

    /**
     * True when this Health Connect offers the background-read grant and the user has not
     * given it. A feature that reads while no screen is up asks for it when it is switched on.
     */
    suspend fun isBackgroundReadGrantMissing(): Boolean =
        isBackgroundReadAvailable() &&
            HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND !in grantedPermissions()

    suspend fun insertImportedRecords(records: List<Record>) {
        withSyncEnabled { clientProvider().insertRecords(records) }
    }

    /** Deletes this app's records of [recordType] by `clientRecordId`. Unknown ids are ignored. */
    suspend fun deleteImportedRecordsByClientIds(recordType: KClass<out Record>, clientRecordIds: List<String>) {
        if (clientRecordIds.isEmpty()) return
        withSyncEnabled {
            clientProvider().deleteRecords(
                recordType = recordType,
                recordIdsList = emptyList(),
                clientRecordIdsList = clientRecordIds,
            )
        }
    }

    /** Every record of [recordType] in `[start, end)`, a page at a time. */
    suspend fun forEachSyncRecordPage(
        recordType: KClass<out Record>,
        start: Instant,
        end: Instant,
        action: suspend (List<Record>) -> Unit,
    ) = SyncRecordsReader(clientProvider).forEachRecordPage(recordType, start, end, action)

    @Suppress("UNCHECKED_CAST")
    suspend fun findMatchingImportedClientRecordIds(
        recordType: KClass<out Record>,
        start: Instant,
        end: Instant,
        wantedIds: Set<String>,
    ): Set<String> {
        if (wantedIds.isEmpty()) return emptySet()
        val typedRecordType = recordType as KClass<Record>
        val matches = mutableSetOf<String>()
        var pageToken: String? = null
        do {
            val response = clientProvider().readRecords(
                ReadRecordsRequest(
                    recordType = typedRecordType,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                    pageSize = ImportedClientRecordIdPageSize,
                    pageToken = pageToken,
                ),
            )
            for (record in response.records) {
                val clientRecordId = record.metadata.clientRecordId ?: continue
                if (clientRecordId in wantedIds) {
                    matches += clientRecordId
                }
            }
            if (matches.size == wantedIds.size) break
            pageToken = response.pageToken
        } while (!pageToken.isNullOrBlank())
        return matches
    }

    /** The time spans of every exercise session overlapping `[start, end)`, from any app. */
    suspend fun readExerciseSessionRanges(start: Instant, end: Instant): List<ClosedRange<Instant>> {
        val ranges = mutableListOf<ClosedRange<Instant>>()
        forEachSyncRecordPage(ExerciseSessionRecord::class, start, end) { page ->
            page.forEach { record ->
                val session = record as ExerciseSessionRecord
                ranges += session.startTime..session.endTime
            }
        }
        return ranges
    }

    /** The newest weight Health Connect holds, from any app, or null when none. */
    suspend fun readLatestWeightKg(): Double? =
        latest(WeightRecord::class)?.weight?.inKilograms

    /** The newest height Health Connect holds, from any app, or null when none. */
    suspend fun readLatestHeightCm(): Double? =
        latest(HeightRecord::class)?.height?.inMeters?.times(100.0)

    private suspend fun <T : Record> latest(recordType: KClass<T>): T? = withContext(Dispatchers.IO) {
        clientProvider().readRecords(
            ReadRecordsRequest(
                recordType = recordType,
                timeRangeFilter = TimeRangeFilter.before(Instant.now()),
                ascendingOrder = false,
                pageSize = 1,
            ),
        ).records.firstOrNull()
    }

    /** The raw client, for the activity writer only. Everything else goes through a method here. */
    internal fun client(): HealthConnectClient = clientProvider()

    private suspend fun <T> withSyncEnabled(block: suspend () -> T): T {
        syncGate.requireEnabled()
        return block()
    }

    /** Built once: below Android 14 `getOrCreate` makes a new service-backed client on every call. */
    private class CachedClient(private val context: Context) : () -> HealthConnectClient {
        @Volatile
        private var cached: HealthConnectClient? = null

        override fun invoke(): HealthConnectClient =
            cached ?: HealthConnectClient.getOrCreate(context).also { cached = it }
    }

    private companion object {
        const val TAG = "HealthConnectGateway"
        const val GrantedPermissionsCacheMillis = 30_000L
        const val ImportedClientRecordIdPageSize = 500
    }
}

/** The `android.permission.health.*` entries of this app's manifest. */
private fun declaredHealthPermissions(context: Context): Set<String> {
    val info = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        }
    }.getOrNull() ?: return emptySet()
    return info.requestedPermissions.orEmpty()
        .filterTo(linkedSetOf()) { it.startsWith("android.permission.health.") }
}
