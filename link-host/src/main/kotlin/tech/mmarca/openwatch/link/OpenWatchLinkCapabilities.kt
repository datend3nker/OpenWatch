package tech.mmarca.openwatch.link

import javax.inject.Inject
import javax.inject.Singleton
import tech.mmarca.openlink.OpenLinkDevice
import tech.mmarca.openlink.OpenLinkLiveSubscription
import tech.mmarca.openlink.OpenLinkProfile
import tech.mmarca.openlink.OpenLinkRecordingRequest
import tech.mmarca.openlink.OpenLinkSettings
import tech.mmarca.openlink.host.OpenLinkCaller
import tech.mmarca.openlink.host.OpenLinkHostCapabilities
import tech.mmarca.openwatch.devices.core.sync.DeviceSyncController

/** What OpenWatch offers over OpenLink. Every method returns at once; see [OpenLinkHostCapabilities]. */
@Singleton
class OpenWatchLinkCapabilities @Inject constructor(
    private val profileSync: LinkProfileSync,
    private val devices: LinkDevices,
    private val syncController: DeviceSyncController,
    private val sessions: LinkLiveSessions,
    private val settings: LinkSettingsStore,
) : OpenLinkHostCapabilities {

    override fun profile(): OpenLinkProfile = profileSync.current()

    override fun setProfile(caller: OpenLinkCaller, profile: OpenLinkProfile) {
        profileSync.accept(profile)
    }

    override fun settings(): OpenLinkSettings = settings.linkSettings()

    /** The store keeps the newer value per key and publishes what changed, which tells the other clients. */
    override fun setSettings(caller: OpenLinkCaller, settings: OpenLinkSettings) {
        this.settings.accept(settings)
    }

    override fun devices(): List<OpenLinkDevice> = devices.devices.value

    override fun requestSync(caller: OpenLinkCaller, deviceId: String) {
        val device = devices.devices.value.firstOrNull { it.id == deviceId } ?: return
        // A scale, or any device whose family pulls nothing.
        if (!device.supportsSync) return
        // One sync at a time; a request while one runs is dropped, as a tap on the button would be.
        syncController.syncDevice(deviceId)
    }

    override fun startRecording(caller: OpenLinkCaller, request: OpenLinkRecordingRequest) =
        sessions.startRecording(request, caller.packageName)

    override fun stopRecording(caller: OpenLinkCaller, sessionId: String) = sessions.stopRecording(sessionId)

    override fun subscribeLive(caller: OpenLinkCaller, subscription: OpenLinkLiveSubscription) =
        sessions.subscribe(subscription)

    override fun unsubscribeLive(caller: OpenLinkCaller, sessionId: String) = sessions.unsubscribe(sessionId)
}
