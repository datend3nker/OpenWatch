package tech.mmarca.openwatch.wear

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import tech.mmarca.openwatch.wearlink.DaySummary
import tech.mmarca.openwatch.wearlink.Page
import tech.mmarca.openwatch.wearlink.WearLinkConnection
import tech.mmarca.openwatch.wearlink.WearLinkProtocol
import tech.mmarca.openwatch.wearlink.WearLinkWorkout
import tech.mmarca.openwatch.wearlink.WearLinkRequestHandler
import tech.mmarca.openwatch.wearlink.WearLinkWorkoutControl
import tech.mmarca.openwatch.wearlink.WearLinkServer
import tech.mmarca.openwatch.wearlink.WearLinkSince

/**
 * Hosts the RFCOMM listener and runs `WearLinkServer` for every phone that
 * connects, one thread per connection. All the protocol lives in
 * `:wearlink`; this class is the Bluetooth around it and the hands the
 * store to its request handler.
 */
class WearLinkServerHost(
    private val adapter: () -> BluetoothAdapter?,
    trust: WearTrustStore,
    store: MetricStore,
    live: LiveHeartRate,
    /** Whether the watch has the raw PPG sensor a night-time HRV window needs. */
    hrvAvailable: () -> Boolean,
    /** Whether the watch has the Samsung platform, the only source of skin temperature and SpO2. */
    skinAvailable: () -> Boolean,
    /** The phone reported the wearer active at the given time. Called on the link's thread. */
    onPhoneActive: (Long) -> Unit,
    /** The watch's settings as the phone reads them. Called on the link's thread. */
    heldPreferences: () -> Map<String, String>,
    /** Applies a setting from the phone; false when refused. Called on the link's thread. */
    setPreference: (key: String, value: String) -> Boolean,
    /** The finished workouts the phone pulls. Called on the link's thread. */
    workouts: (sinceEpochMillis: Long, limit: Int) -> Page<WearLinkWorkout>,
    /** Keeps the phone's figures for a day (`PUT_DAY`); false when refused. Called on the link's thread. */
    putDay: (DaySummary) -> Boolean,
    /** A workout recorded for an app on the phone ([WearLinkWorkoutControl]). Called on the link's thread. */
    workoutControl: (WearLinkWorkoutControl.Control) -> WearLinkWorkoutControl.State,
    /** The watch has a GPS receiver, for a recorded workout's route. */
    hasGps: () -> Boolean = { false },
    private val localName: () -> String,
    private val state: WearLinkState,
) {
    private val server = WearLinkServer(trust, Handler(store, live, hrvAvailable, skinAvailable, onPhoneActive, heldPreferences, setPreference, workouts, putDay, workoutControl, hasGps, localName))

    @Volatile
    private var serverSocket: BluetoothServerSocket? = null
    private val isListening = AtomicBoolean(false)

    /** Bumped per listener start, so a thread that is shutting down cannot stop its successor. */
    private val generation = AtomicInteger(0)

    fun start() {
        if (isListening.getAndSet(true)) return
        val mine = generation.incrementAndGet()
        Thread({ listen(mine) }, "WearLinkServerHost").start()
    }

    fun stop() {
        isListening.set(false)
        // Unblocks accept(); the listener thread then exits.
        runCatching { serverSocket?.close() }
        state.update { it.copy(listening = false) }
    }

    private fun listen(mine: Int) {
        var socket: BluetoothServerSocket? = null
        try {
            val adapter = adapter()
            if (adapter == null || !adapter.isEnabled) {
                // The state receiver starts it again once Bluetooth is on.
                Log.w(TAG, "Bluetooth unavailable or disabled")
                return
            }
            val server = try {
                adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, WearLinkProtocol.SERVICE_UUID)
            } catch (e: SecurityException) {
                Log.e(TAG, "Missing Bluetooth permission for RFCOMM server", e)
                return
            }
            socket = server
            serverSocket = server
            state.update { it.copy(listening = true) }
            Log.i(TAG, "Listening on ${WearLinkProtocol.SERVICE_UUID}")
            while (isListening.get() && generation.get() == mine) {
                val client = try {
                    server.accept()
                } catch (e: IOException) {
                    if (isListening.get()) Log.e(TAG, "Accept failed", e)
                    break
                }
                serveOnThread(client)
            }
        } catch (e: IOException) {
            Log.e(TAG, "Cannot start the listener", e)
        } finally {
            runCatching { socket?.close() }
            if (serverSocket === socket) serverSocket = null
            if (generation.get() == mine) {
                isListening.set(false)
                state.update { it.copy(listening = false) }
            }
        }
    }

    private fun serveOnThread(socket: BluetoothSocket) {
        Thread({
            val address = runCatching { socket.remoteDevice.address }.getOrNull() ?: "unknown"
            val connection = try {
                WearLinkConnection(socket.inputStream, socket.outputStream, address) { socket.close() }
            } catch (e: IOException) {
                Log.w(TAG, "No streams for the phone link: ${e::class.java.simpleName}")
                runCatching { socket.close() }
                return@Thread
            }
            state.update { it.copy(activeClients = server.activeClients + 1) }
            val outcome = server.serve(connection)
            state.update { it.copy(activeClients = server.activeClients) }
            Log.i(TAG, "Served the phone link: $outcome")
        }, "WearLinkServerHost-client").start()
    }

    private class Handler(
        private val store: MetricStore,
        private val live: LiveHeartRate,
        private val hrvAvailable: () -> Boolean,
        private val skinAvailable: () -> Boolean,
        private val onPhoneActive: (Long) -> Unit,
        private val heldPreferences: () -> Map<String, String>,
        private val setPreference: (String, String) -> Boolean,
        private val workouts: (Long, Int) -> Page<WearLinkWorkout>,
        private val putDay: (DaySummary) -> Boolean,
        private val workoutControl: (WearLinkWorkoutControl.Control) -> WearLinkWorkoutControl.State,
        private val hasGps: () -> Boolean,
        private val localName: () -> String,
    ) : WearLinkRequestHandler {
        override fun localName(): String = localName.invoke()

        override fun capabilities(): Set<String> = WearMetrics.ALL.mapTo(LinkedHashSet()) { it.key }.apply {
            if (live.hasSensor) add(WearLinkProtocol.CAP_LIVE_HEART_RATE)
            if (!hrvAvailable()) {
                remove(WearLinkProtocol.CAP_HRV)
                remove(WearLinkProtocol.CAP_RESPIRATORY_RATE)
            }
            if (!skinAvailable()) {
                remove(WearLinkProtocol.CAP_SKIN_TEMPERATURE)
                remove(WearLinkProtocol.CAP_SPO2)
            }
            add(WearLinkProtocol.CAP_PHONE_ACTIVE)
            add(WearLinkProtocol.CAP_PREFERENCES)
            add(WearLinkProtocol.CAP_DAY_SUMMARY)
            add(WearLinkWorkoutControl.CAPABILITY)
            if (hasGps()) add(WearLinkWorkoutControl.CAPABILITY_GPS)
            // The metric list holds the workouts' pauses and route too; only the header key is a capability.
            remove("wp")
            remove("wr")
        }

        /** A series the watch announces, answered by its store's sequence numbers. */
        override fun seriesSince(key: String, cursor: String, limit: Int): WearLinkSince.Page? {
            val metric = WearMetrics.SERIES.firstOrNull { it.key == key }?.takeIf { key in capabilities() } ?: return null
            val after = when (val position = WearLinkSince.position(cursor, store.storeId)) {
                is WearLinkSince.Position.AfterSeq -> position.seq
                is WearLinkSince.Position.AfterTime -> store.seqBeforeNewerThan(metric, position.epochMillis)
            }
            val changes = store.changes(metric, after, limit)
            return WearLinkSince.Page(changes.lines, changes.more, WearLinkSince.cursor(store.storeId, changes.lastSeq))
        }

        override fun phoneActive(epochMillis: Long) = onPhoneActive(epochMillis)

        override fun workoutsSince(sinceEpochMillis: Long, limit: Int): Page<WearLinkWorkout> = workouts(sinceEpochMillis, limit)

        override fun heldPreferences(): Map<String, String> = heldPreferences.invoke()

        override fun setPreference(key: String, value: String): Boolean = setPreference.invoke(key, value)

        override fun putDay(day: DaySummary): Boolean = putDay.invoke(day)

        override fun workoutControl(control: WearLinkWorkoutControl.Control): WearLinkWorkoutControl.State = workoutControl.invoke(control)

        override fun liveHeartRate(seconds: Int, emit: (WearLinkProtocol.HeartRateSample) -> Unit): Int =
            live.window(seconds, emit)
    }

    private companion object {
        const val TAG = "WearLinkServerHost"
        const val SERVICE_NAME = "OpenWatchWearApp"
    }
}
