package tech.mmarca.openwatch.devices.wearos

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import tech.mmarca.openwatch.core.performance.DefaultDispatcherProvider
import tech.mmarca.openwatch.core.performance.DispatcherProvider
import tech.mmarca.openwatch.wearlink.DaySummary
import tech.mmarca.openwatch.wearlink.Page
import tech.mmarca.openwatch.wearlink.WearLinkClient
import tech.mmarca.openwatch.wearlink.WearLinkConnection
import tech.mmarca.openwatch.wearlink.WearLinkOutcome
import tech.mmarca.openwatch.wearlink.WearLinkProtocol
import tech.mmarca.openwatch.wearlink.WearLinkSession
import tech.mmarca.openwatch.wearlink.WearLinkSince
import tech.mmarca.openwatch.wearlink.WearLinkWorkout
import tech.mmarca.openwatch.wearlink.WearLinkWorkoutControl

/**
 * Bluetooth Classic RFCOMM implementation of [WearOsNodePort], on AOSP APIs
 * only. Finds the bonded watch, opens a socket to the OpenWatch Wear OS
 * app's UUID, and speaks `:wearlink`: a hello carrying this phone's token
 * for that watch, then the requests of one sync. The watch side is
 * `wear/.../WearLinkServerHost`.
 *
 * Every call goes through the watch's one link ([WearOsSessionCache]): one
 * connection at a time, as RFCOMM allows, which the requests of a sync
 * share with the capabilities its hello announced. A call that finds the
 * link busy waits its turn. The live heart rate is short windows on the
 * same link, so a status check, a setting or a sync runs between two.
 *
 * RFCOMM is Classic, not BLE, so no radio lease is taken; phone-to-phone
 * sync works the same way. Every outcome the watch can give is a
 * [WearOsLinkFailure]; a missing permission stays a `SecurityException`.
 */
@Singleton
class BluetoothWearOsNodePort internal constructor(
    private val linkStore: WearOsLinkStore,
    private val radio: WearOsRadio,
    private val dispatchers: DispatcherProvider,
    private val sessions: WearOsSessionCache,
) : WearOsNodePort {

    @Inject
    constructor(
        @ApplicationContext context: Context,
        linkStore: WearOsLinkStore,
        dispatchers: DispatcherProvider = DefaultDispatcherProvider,
    ) : this(linkStore, AndroidWearOsRadio(context), dispatchers, WearOsSessionCache(CoroutineScope(SupervisorJob() + dispatchers.io)))

    override suspend fun checkStatus(
        targetAddress: String?,
        targetName: String?,
    ): WearOsCompanionStatus = onIo { owner ->
        val match = try {
            bondedWatch(targetAddress, targetName)
        } catch (failure: WearOsLinkFailure) {
            return@onIo WearOsCompanionStatus(appStatus = failure.toAppStatus(), lastCheckedAt = Instant.now())
        }
        val status = try {
            exchange(owner, match, targetAddress, PING_TIMEOUT_MS) { it.ping() }?.let { unwrap(it) }
            WearOsAppStatus.APP_RUNNING
        } catch (failure: WearOsLinkFailure) {
            failure.toAppStatus()
        }
        WearOsCompanionStatus(
            isPaired = true,
            connectedNodeName = match.name ?: match.address,
            connectedNodeAddress = match.address,
            appStatus = status,
            lastCheckedAt = Instant.now(),
            watchName = lastWatchName,
        )
    }

    override suspend fun <T : Any> pullSeries(
        targetAddress: String?,
        targetName: String?,
        series: WearSeries<T>,
        cursor: String,
    ): WearOsSeriesPage<T>? = onIo { owner ->
        val match = bondedWatchOrNull(targetAddress, targetName) ?: return@onIo null
        // A series the watch does not record costs no request: its hello said so.
        val outcome = exchange(owner, match, targetAddress, PULL_TIMEOUT_MS, capability = series.key, ask = { it.pullSince(series.key, cursor, WearLinkSince.MAX_LIMIT) })
            ?: return@onIo WearOsSeriesPage(emptyList(), cursor, hasMore = false)
        val page = unwrap(outcome)
        WearOsSeriesPage(page.lines.mapNotNull(series.parse), page.cursor, page.more)
    }

    override suspend fun pullWorkouts(targetAddress: String?, targetName: String?, since: Instant): WearOsWorkoutPage? =
        pull(targetAddress, targetName, WearLinkProtocol.CAP_WORKOUTS, { it.pullWorkouts(since.toEpochMilli(), WearLinkProtocol.MAX_WORKOUTS_PER_REQUEST) }) { items, more ->
            WearOsWorkoutPage(items.map { it.toPortWorkout() }, more)
        }

    override suspend fun pullWatchSettings(targetAddress: String?, targetName: String?): Map<String, String>? = onIo { owner ->
        val match = bondedWatchOrNull(targetAddress, targetName) ?: return@onIo null
        exchange(owner, match, targetAddress, PING_TIMEOUT_MS, WearLinkProtocol.CAP_PREFERENCES) { it.pullPreferences() }?.let { unwrap(it) }
    }

    override suspend fun setWatchSetting(targetAddress: String?, targetName: String?, key: String, value: String): Boolean = onIo { owner ->
        val match = bondedWatchOrNull(targetAddress, targetName) ?: return@onIo false
        exchange(owner, match, targetAddress, PING_TIMEOUT_MS, WearLinkProtocol.CAP_PREFERENCES) { it.setPreference(key, value) } is WearLinkOutcome.Ok
    }

    override suspend fun controlWorkout(
        targetAddress: String?,
        targetName: String?,
        control: WearLinkWorkoutControl.Control,
        background: Boolean,
    ): WearLinkWorkoutControl.State? = onIo { owner ->
        val match = bondedWatchOrNull(targetAddress, targetName) ?: return@onIo null
        // A watch is held by the watch for up to its few seconds; the answer comes after them.
        val timeout = if (control.command == WearLinkWorkoutControl.Command.WATCH) {
            PING_TIMEOUT_MS + WearLinkWorkoutControl.MAX_WATCH_SECONDS * 1000L
        } else {
            PING_TIMEOUT_MS
        }
        val outcome = exchange(owner, match, targetAddress, timeout, WearLinkWorkoutControl.CAPABILITY, background) {
            it.workoutControl(control)
        } ?: return@onIo WearLinkWorkoutControl.State(control.sessionId, WearLinkWorkoutControl.Phase.FAILED, 0L, WearLinkWorkoutControl.REASON_UNSUPPORTED)
        unwrap(outcome)
    }

    override suspend fun notifyPhoneActive(targetAddress: String?, targetName: String?, at: Instant): Boolean = onIo { owner ->
        val match = bondedWatchOrNull(targetAddress, targetName) ?: return@onIo false
        val outcome = exchange(owner, match, targetAddress, PING_TIMEOUT_MS, WearLinkProtocol.CAP_PHONE_ACTIVE) {
            it.notifyPhoneActive(at.toEpochMilli())
        } ?: return@onIo false
        unwrap(outcome)
        true
    }

    override suspend fun pushDays(targetAddress: String?, targetName: String?, days: List<DaySummary>): Boolean = onIo { owner ->
        val match = bondedWatchOrNull(targetAddress, targetName) ?: return@onIo false
        // Every day in one turn on the watch's link: a refusal ends the turn with it.
        val outcome = exchange(owner, match, targetAddress, PULL_TIMEOUT_MS, WearLinkProtocol.CAP_DAY_SUMMARY) { session ->
            days.asSequence().map(session::putDay).firstOrNull { it !is WearLinkOutcome.Ok } ?: WearLinkOutcome.Ok(Unit)
        } ?: return@onIo false
        unwrap(outcome)
        true
    }

    override suspend fun liveHeartRate(
        targetAddress: String?,
        targetName: String?,
        onSample: (WearOsHeartRateSample) -> Unit,
    ): Boolean = onIo { owner ->
        val match = bondedWatchOrNull(targetAddress, targetName) ?: return@onIo false
        var quiet = 0
        // One short window after another, each a request in the link's turns: whatever else waits goes between two.
        while (quiet < LIVE_QUIET_WINDOWS) {
            val outcome = exchange(owner, match, targetAddress, LIVE_TIMEOUT_MS, WearLinkProtocol.CAP_LIVE_HEART_RATE, background = true) {
                it.liveHeartRate(LIVE_WINDOW_SECONDS) { sample ->
                    onSample(WearOsHeartRateSample(Instant.ofEpochMilli(sample.epochMillis), sample.bpm))
                }
            } ?: return@onIo false
            quiet = if (unwrap(outcome) == 0) quiet + 1 else 0
        }
        true
    }

    /** The name the watch gave in its last `OK`, for the status card. */
    @Volatile
    private var lastWatchName: String? = null

    /**
     * [block] on the I/O dispatcher, given the caller's job: a session opened for this call
     * belongs to the caller's coroutine, a whole sync, and not to this one call.
     */
    private suspend fun <T> onIo(block: suspend (owner: Job?) -> T): T {
        val owner = currentCoroutineContext()[Job]
        return withContext(dispatchers.io) { block(owner) }
    }

    /** One page of a series: null when no bonded watch matches, empty when the watch lacks [capability]. */
    private suspend fun <W, P> pull(
        targetAddress: String?,
        targetName: String?,
        capability: String?,
        ask: (WearLinkSession) -> WearLinkOutcome<Page<W>>,
        page: (items: List<W>, more: Boolean) -> P,
    ): P? = onIo { owner ->
        val match = bondedWatchOrNull(targetAddress, targetName) ?: return@onIo null
        val outcome = exchange(owner, match, targetAddress, PULL_TIMEOUT_MS, capability, ask = ask)
            ?: return@onIo page(emptyList(), false)
        val got = unwrap(outcome)
        page(got.items, got.more)
    }

    /**
     * [ask] over the watch's session, in its turn on the watch's one link; null, and nothing asked,
     * when its hello did not announce [capability]. A [background] request lets the others go first.
     */
    private suspend fun <T> exchange(
        owner: Job?,
        match: BondedWatch,
        registeredAddress: String?,
        timeoutMs: Long,
        capability: String? = null,
        background: Boolean = false,
        ask: (WearLinkSession) -> WearLinkOutcome<T>,
    ): WearLinkOutcome<T>? {
        // One link per bonded watch; the token follows the address the watch was added under, so a session is kept per both.
        val watch = match.address.uppercase()
        val key = "$watch ${registeredAddress.orEmpty().uppercase()}"
        return sessions.request(watch, key, owner, timeoutMs, capability, background, open = { guard -> open(match, registeredAddress, guard) }, ask)
    }

    /** The bonded entry for the registered watch. Throws [WearOsLinkFailure.BluetoothOff] or [WearOsLinkFailure.NotBonded]. */
    private fun bondedWatch(targetAddress: String?, targetName: String?): BondedWatch {
        val bonded = radio.bondedWatches() ?: throw WearOsLinkFailure.BluetoothOff()
        return WearOsBondMatcher.pick(bonded, targetAddress, targetName, linkAddress = linkStore.linkAddress(targetAddress))
            ?: throw WearOsLinkFailure.NotBonded()
    }

    /** Null when no bonded watch matches, as the pulls promise; Bluetooth off is still a failure. */
    private fun bondedWatchOrNull(targetAddress: String?, targetName: String?): BondedWatch? =
        try {
            bondedWatch(targetAddress, targetName)
        } catch (_: WearOsLinkFailure.NotBonded) {
            null
        }

    /**
     * Connects and says hello: a session the watch answered `OK`. Blocking; [guard] closes the
     * socket when the call runs out of time or is cancelled. Closes it again on any failure.
     */
    private fun open(match: BondedWatch, registeredAddress: String?, guard: WearOsLinkGuard): WearLinkSession {
        val connection = radio.connect(match, guard)
        try {
            val hello = WearLinkProtocol.Hello(
                version = WearLinkProtocol.VERSION,
                capabilities = setOf(WearLinkProtocol.CAP_CLOCK),
                token = linkStore.tokenFor(registeredAddress, match.address),
                name = radio.localName(),
            )
            val session = when (val outcome = WearLinkClient.hello(connection, hello, reconnect = { radio.connect(match, guard) })) {
                is WearLinkOutcome.Ok -> outcome.value
                else -> throw failureOf(outcome)
            }
            lastWatchName = session.peer.name
            WearOsClock.check(session.peer.clockMillis, System.currentTimeMillis())
            // The bond's Classic address is the surest way to find this watch again.
            linkStore.setLinkAddress(registeredAddress, match.address)
            linkStore.setCapabilities(registeredAddress, session.peer.capabilities.toSet())
            return session
        } catch (error: Throwable) {
            // The watch waits for this close after a refusal.
            connection.close()
            throw error
        }
    }

    private fun <T> unwrap(outcome: WearLinkOutcome<T>): T = when (outcome) {
        is WearLinkOutcome.Ok -> outcome.value
        else -> throw failureOf(outcome)
    }

    private fun failureOf(outcome: WearLinkOutcome<*>): WearOsLinkFailure = when (outcome) {
        is WearLinkOutcome.Ok -> error("not a failure")
        WearLinkOutcome.Pending -> WearOsLinkFailure.PendingConfirmation()
        is WearLinkOutcome.Unauthorized -> WearOsLinkFailure.Unauthorized(outcome.reason)
        is WearLinkOutcome.VersionMismatch -> WearOsLinkFailure.VersionMismatch(outcome.min, outcome.max)
        is WearLinkOutcome.Refused -> WearOsLinkFailure.Refused(outcome.code)
        is WearLinkOutcome.ProtocolError -> WearOsLinkFailure.Protocol(outcome.detail)
        WearLinkOutcome.Closed -> WearOsLinkFailure.NoAnswer()
    }

    private companion object {
        /**
         * Connect (paging plus service lookup), the hello and the ping together, once the call has
         * the link; it waits up to as long again for its turn.
         */
        const val PING_TIMEOUT_MS = 10_000L

        /** A full page over RFCOMM is well under a second; the connect dominates. */
        const val PULL_TIMEOUT_MS = 30_000L

        /**
         * One live window, the longest another call waits for the link while the live heart rate
         * runs. The watch's idle timer restarts when a request arrives and runs through the
         * window, and the phone reuses a session only within 10 s of its last request: a window
         * of the protocol's 10 s maximum would leave every session past that line and need a
         * connection per window, while 5 s leaves 5 s for the next request. A call that comes
         * during a window waits at most 5 s, half of what a status check may wait. The watch reads
         * about once a second, so a window carries about five readings.
         */
        const val LIVE_WINDOW_SECONDS = 5

        /** Windows in a row without a reading before the stream ends: the same 10 s of silence one window used to be. */
        const val LIVE_QUIET_WINDOWS = 2

        /** A connect and hello when the session must be opened, then the window. */
        const val LIVE_TIMEOUT_MS = PING_TIMEOUT_MS + LIVE_WINDOW_SECONDS * 1000L
    }
}

/** The phone's Bluetooth Classic radio as the port uses it; a seam, so a test runs the port over a loopback. */
internal interface WearOsRadio {
    /** The bonded devices, or null while Bluetooth is off. A missing BLUETOOTH_CONNECT throws a `SecurityException`. */
    fun bondedWatches(): List<BondedWatch>?

    /**
     * An RFCOMM connection to the OpenWatch app on [watch]. Blocking; [guard] closes the socket
     * when the call runs out of time. Throws [WearOsLinkFailure.NoAnswer] when nothing answers.
     */
    fun connect(watch: BondedWatch, guard: WearOsLinkGuard): WearLinkConnection

    /** This phone's name, for the hello. */
    fun localName(): String
}

private class AndroidWearOsRadio(private val context: Context) : WearOsRadio {

    @SuppressLint("MissingPermission")
    override fun bondedWatches(): List<BondedWatch>? {
        val adapter = adapter()
        if (adapter == null || !adapter.isEnabled) return null
        // A missing BLUETOOTH_CONNECT propagates: the screen shows the grant affordance.
        return adapter.bondedDevices.orEmpty().map { BondedWatch(it.address, it.name) }
    }

    @SuppressLint("MissingPermission")
    override fun connect(watch: BondedWatch, guard: WearOsLinkGuard): WearLinkConnection {
        val adapter = adapter() ?: throw WearOsLinkFailure.BluetoothOff()
        val socket = try {
            adapter.getRemoteDevice(watch.address).createRfcommSocketToServiceRecord(WearLinkProtocol.SERVICE_UUID)
        } catch (e: IOException) {
            Log.d(TAG, "No RFCOMM socket for the watch", e)
            throw WearOsLinkFailure.NoAnswer(e)
        }
        val close = { runCatching { socket.close() }; Unit }
        guard.watch(close)
        var connection: WearLinkConnection? = null
        try {
            // A running discovery slows the connect down. Cancelling it needs
            // BLUETOOTH_SCAN, which the exchange itself does not.
            runCatching { adapter.cancelDiscovery() }
            try {
                socket.connect()
            } catch (e: IOException) {
                // Off, out of range, app not listening, or the watchdog closed the socket.
                Log.d(TAG, "No answer from the watch", e)
                throw WearOsLinkFailure.NoAnswer(e)
            }
            connection = WearLinkConnection(socket.inputStream, socket.outputStream, watch.address, close)
            return connection
        } finally {
            if (connection == null) close()
        }
    }

    @SuppressLint("MissingPermission")
    override fun localName(): String =
        adapter()?.let { runCatching { it.name }.getOrNull() }?.takeIf { it.isNotBlank() } ?: Build.MODEL

    private fun adapter(): BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter

    private companion object {
        const val TAG = "BluetoothWearOsNodePort"
    }
}

private fun WearLinkWorkout.toPortWorkout() = WearOsWorkout(
    start = Instant.ofEpochMilli(startMillis),
    end = Instant.ofEpochMilli(endMillis),
    exerciseType = exerciseType,
    steps = steps,
    distanceMetres = distanceMetres,
    ascentMetres = ascentDecimetres?.let { it / 10.0 },
    pauses = pauses.map { Instant.ofEpochMilli(it.startMillis)..Instant.ofEpochMilli(it.endMillis) },
    route = route.map {
        WearOsWorkoutPoint(
            time = Instant.ofEpochMilli(it.epochMillis),
            latitude = it.latitudeE7 / 1e7,
            longitude = it.longitudeE7 / 1e7,
            altitudeMetres = it.altitudeDecimetres?.let { d -> d / 10.0 },
            accuracyMetres = it.accuracyDecimetres?.let { d -> d / 10.0 },
        )
    },
)
