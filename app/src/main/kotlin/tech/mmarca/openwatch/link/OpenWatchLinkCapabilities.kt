package tech.mmarca.openwatch.link

import javax.inject.Inject
import javax.inject.Singleton
import tech.mmarca.openlink.OpenLinkDevice
import tech.mmarca.openlink.OpenLinkDeviceKind
import tech.mmarca.openlink.OpenLinkLiveSubscription
import tech.mmarca.openlink.OpenLinkProfile
import tech.mmarca.openlink.OpenLinkRecordingMode
import tech.mmarca.openlink.OpenLinkRecordingRequest
import tech.mmarca.openlink.OpenLinkWorkoutPlan
import tech.mmarca.openlink.host.OpenLinkCaller
import tech.mmarca.openlink.host.OpenLinkHostCapabilities
import tech.mmarca.openwatch.features.watches.DeviceSyncController

/** What OpenWatch offers over OpenLink. Every method returns at once; see [OpenLinkHostCapabilities]. */
@Singleton
class OpenWatchLinkCapabilities @Inject constructor(
    private val profileSync: LinkProfileSync,
    private val devices: LinkDevices,
    private val syncController: DeviceSyncController,
    private val sessions: LinkLiveSessions,
    private val recorded: LinkRecordedWorkouts,
) : OpenLinkHostCapabilities {

    override fun profile(): OpenLinkProfile = profileSync.current()

    override fun setProfile(caller: OpenLinkCaller, profile: OpenLinkProfile) {
        profileSync.accept(profile)
    }

    override fun devices(): List<OpenLinkDevice> = devices.devices.value

    override fun requestSync(caller: OpenLinkCaller, deviceId: String) {
        val device = devices.devices.value.firstOrNull { it.id == deviceId } ?: return
        if (device.kind == OpenLinkDeviceKind.SCALE) return
        // One sync at a time; a request while one runs is dropped, as a tap on the button would be.
        syncController.syncDevice(deviceId, listenAfter = DeviceSyncController.MANUAL_SYNC_LINGER)
    }

    override fun startRecording(caller: OpenLinkCaller, request: OpenLinkRecordingRequest) {
        if (request.mode == OpenLinkRecordingMode.RECORD) recorded.start(request) else sessions.startRecording(request)
    }

    override fun stopRecording(caller: OpenLinkCaller, sessionId: String) {
        if (!recorded.stop(sessionId)) sessions.stopRecording(sessionId)
    }

    override fun pauseRecording(caller: OpenLinkCaller, sessionId: String, atMillis: Long) = recorded.pause(sessionId)

    override fun resumeRecording(caller: OpenLinkCaller, sessionId: String, atMillis: Long) = recorded.resume(sessionId)

    override fun setWorkoutPlan(caller: OpenLinkCaller, sessionId: String, plan: OpenLinkWorkoutPlan?) =
        recorded.setPlan(sessionId, plan)

    override fun subscribeLive(caller: OpenLinkCaller, subscription: OpenLinkLiveSubscription) =
        sessions.subscribe(subscription)

    override fun unsubscribeLive(caller: OpenLinkCaller, sessionId: String) = sessions.unsubscribe(sessionId)
}
