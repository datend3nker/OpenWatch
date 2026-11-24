package tech.mmarca.openwatch.link

import android.util.Log
import dagger.Lazy
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tech.mmarca.openlink.OpenLinkDevice
import tech.mmarca.openlink.OpenLinkIntegration
import tech.mmarca.openlink.OpenLinkLiveBatch
import tech.mmarca.openlink.OpenLinkLiveSubscription
import tech.mmarca.openlink.OpenLinkRecordingMode
import tech.mmarca.openlink.OpenLinkRecordingPhase
import tech.mmarca.openlink.OpenLinkRecordingRequest
import tech.mmarca.openlink.OpenLinkRecordingState
import tech.mmarca.openlink.OpenLinkSample
import tech.mmarca.openlink.OpenLinkSampleType
import tech.mmarca.openlink.host.OpenLinkHost
import tech.mmarca.openwatch.core.performance.ApplicationScope
import tech.mmarca.openwatch.data.repository.BleDeviceRepository
import tech.mmarca.openwatch.devices.core.DeviceFamilies
import tech.mmarca.openwatch.devices.core.LiveReading
import tech.mmarca.openwatch.devices.core.LiveReadingKind

/** What one session is doing, for the Connected apps screen and the streaming notification. */
data class LinkSessionActivity(
    val sessionId: String,
    /** The devices the session follows, by name, and whether each has sent a reading yet. */
    val devices: List<LinkSessionDevice> = emptyList(),
    /** The live sample types a subscription under this session receives; empty if none. */
    val liveTypes: Set<OpenLinkSampleType> = emptySet(),
) {
    val streaming: List<LinkSessionDevice> get() = devices.filter { it.streaming }
}

data class LinkSessionDevice(val id: String, val name: String, val streaming: Boolean)

/**
 * Recording sessions and live subscriptions from another app. Neither watch can be told to
 * start a workout, so a session keeps the watch's live readings flowing for its length: a
 * Garmin's held link with its heart-rate and steps streams on, or the Wear OS app's live
 * heart rate. A device's stream runs while any session or subscription collects it.
 */
@Singleton
class LinkLiveSessions @Inject constructor(
    private val devices: LinkDevices,
    private val deviceRepository: BleDeviceRepository,
    private val families: DeviceFamilies,
    private val host: Lazy<OpenLinkHost>,
    @ApplicationScope appScope: CoroutineScope,
) {
    private val scope = appScope
    private val streams = mutableMapOf<String, SharedFlow<OpenLinkSample>>()
    private val recordings = mutableMapOf<String, Job>()
    private val subscriptions = mutableMapOf<String, Job>()

    private val _activity = MutableStateFlow<Map<String, LinkSessionActivity>>(emptyMap())

    /** Each running session's devices and live types, by session id. */
    val activity: StateFlow<Map<String, LinkSessionActivity>> = _activity.asStateFlow()

    @Synchronized
    fun startRecording(request: OpenLinkRecordingRequest, owner: String) {
        // A recorded workout (version 2) has the watch record it and OpenWatch save it; no watch can do that
        // yet, so it is refused rather than streamed, which would save nothing.
        if (request.mode == OpenLinkRecordingMode.RECORD) {
            publish(
                OpenLinkRecordingState(request.sessionId, request.deviceId.orEmpty(), OpenLinkRecordingPhase.FAILED, OpenLinkRecordingState.REASON_UNSUPPORTED, now()),
                owner,
            )
            return
        }
        if (request.sessionId in recordings) return
        val targets = targets(request.deviceId)
        if (targets.isEmpty()) {
            publish(OpenLinkRecordingState(request.sessionId, request.deviceId.orEmpty(), OpenLinkRecordingPhase.FAILED, OpenLinkRecordingState.REASON_NO_DEVICE, now()))
            return
        }
        follow(request.sessionId, targets)
        recordings[request.sessionId] = scope.launch {
            targets.forEach { device ->
                launch { followDevice(request.sessionId, device) }
            }
        }
    }

    @Synchronized
    fun stopRecording(sessionId: String) {
        val job = recordings.remove(sessionId) ?: return
        job.cancel()
        settle(sessionId)
        publish(OpenLinkRecordingState(sessionId, "", OpenLinkRecordingPhase.STOPPED, OpenLinkRecordingState.REASON_STOPPED_BY_CLIENT, now()))
    }

    @Synchronized
    fun subscribe(subscription: OpenLinkLiveSubscription) {
        if (subscription.sessionId in subscriptions) return
        val targets = targets(subscription.deviceId).filter { device -> subscription.types.any { it in device.liveSampleTypes } }
        if (targets.isEmpty()) return
        follow(subscription.sessionId, targets)
        _activity.update { all ->
            val types = subscription.types intersect targets.flatMap { it.liveSampleTypes }.toSet()
            all + (subscription.sessionId to all.getValue(subscription.sessionId).copy(liveTypes = types))
        }
        subscriptions[subscription.sessionId] = scope.launch {
            targets.forEach { device ->
                launch { relay(subscription, device) }
            }
        }
    }

    @Synchronized
    fun unsubscribe(sessionId: String) {
        subscriptions.remove(sessionId)?.cancel() ?: return
        _activity.update { all -> all[sessionId]?.let { all + (sessionId to it.copy(liveTypes = emptySet())) } ?: all }
        settle(sessionId)
    }

    /** Adds [targets] to the session's devices, not yet streaming unless already. */
    private fun follow(sessionId: String, targets: List<OpenLinkDevice>) = _activity.update { all ->
        val current = all[sessionId] ?: LinkSessionActivity(sessionId)
        val known = current.devices.map { it.id }.toSet()
        val added = targets.filter { it.id !in known }.map { LinkSessionDevice(it.id, it.displayName, streaming = false) }
        all + (sessionId to current.copy(devices = current.devices + added))
    }

    /** A device of the session sent its first reading. */
    private fun markStreaming(sessionId: String, deviceId: String) = _activity.update { all ->
        val current = all[sessionId] ?: return@update all
        if (current.devices.none { it.id == deviceId && !it.streaming }) return@update all
        all + (sessionId to current.copy(devices = current.devices.map { if (it.id == deviceId) it.copy(streaming = true) else it }))
    }

    /** Drops the session's activity once neither a recording nor a subscription holds it. */
    private fun settle(sessionId: String) {
        if (sessionId in recordings || sessionId in subscriptions) return
        _activity.update { it - sessionId }
    }

    private fun targets(deviceId: String?): List<OpenLinkDevice> =
        devices.devices.value.filter { it.supportsRecording && (deviceId == null || it.id == deviceId) }

    /**
     * STARTING, then RECORDING on the first reading; FAILED if none comes in time, and
     * RECORDING after all if one comes later. One collection for the whole session, so the
     * watch's stream is not stopped and reopened in between.
     */
    private suspend fun followDevice(sessionId: String, device: OpenLinkDevice): Unit = coroutineScope {
        publish(OpenLinkRecordingState(sessionId, device.id, OpenLinkRecordingPhase.STARTING, null, now()))
        var reading = false
        val watchdog = launch {
            delay(FIRST_READING_TIMEOUT)
            publish(OpenLinkRecordingState(sessionId, device.id, OpenLinkRecordingPhase.FAILED, OpenLinkRecordingState.REASON_NOT_CONNECTED, now()))
        }
        stream(device).collect {
            if (!reading) {
                reading = true
                watchdog.cancel()
                markStreaming(sessionId, device.id)
                publish(OpenLinkRecordingState(sessionId, device.id, OpenLinkRecordingPhase.RECORDING, null, now()))
            }
        }
    }

    /** Batches a device's samples of the asked types once a second into the subscription. */
    private suspend fun relay(subscription: OpenLinkLiveSubscription, device: OpenLinkDevice) {
        val pending = mutableListOf<OpenLinkSample>()
        val flusher = scope.launch {
            while (true) {
                delay(BATCH_MILLIS)
                val batch = synchronized(pending) { pending.toList().also { pending.clear() } }
                if (batch.isNotEmpty()) host.get().publishLive(OpenLinkLiveBatch(subscription.sessionId, device.id, batch))
            }
        }
        try {
            stream(device).collect { sample ->
                markStreaming(subscription.sessionId, device.id)
                if (sample.type in subscription.types) synchronized(pending) { pending += sample }
            }
        } finally {
            flusher.cancel()
        }
    }

    @Synchronized
    private fun stream(device: OpenLinkDevice): SharedFlow<OpenLinkSample> = streams.getOrPut(device.id) {
        // The family streams it; the scale is no registered device and streams nothing.
        val source: Flow<OpenLinkSample> = flow {
            val registered = deviceRepository.devices.firstOrNull { it.id == device.id }
            val readings = registered?.let { families.of(it)?.liveReadings(it) } ?: return@flow
            emitAll(readings.map { it.toSample() })
        }
        // Runs while anyone collects; a few seconds after the last collector goes, the watch's
        // stream stops. The grace covers a session and its subscription arriving apart.
        source.shareIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = STREAM_GRACE_MILLIS), replay = 0)
    }

    /** [owner]: the app a state goes to alone, where the session is not yet the library's. */
    private fun publish(state: OpenLinkRecordingState, owner: String? = null) {
        runCatching { host.get().publishRecordingState(state, owner) }.onFailure { Log.w(TAG, "publishing a session state failed", it) }
    }

    private fun now(): Long = System.currentTimeMillis()

    private fun LiveReading.toSample() = OpenLinkSample(
        type = when (kind) {
            LiveReadingKind.HEART_RATE -> OpenLinkSampleType.HEART_RATE
            LiveReadingKind.STEPS -> OpenLinkSampleType.STEPS
        },
        atMillis,
        value,
    )

    private companion object {
        const val TAG = "LinkLiveSessions"
        const val BATCH_MILLIS = 1_000L
        const val FIRST_READING_TIMEOUT = 45_000L
        const val STREAM_GRACE_MILLIS = 5_000L
    }
}
