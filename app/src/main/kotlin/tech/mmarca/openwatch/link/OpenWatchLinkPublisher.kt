package tech.mmarca.openwatch.link

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import tech.mmarca.openlink.host.OpenLinkHost
import tech.mmarca.openwatch.data.repository.LinkSettingsStore
import tech.mmarca.openwatch.data.repository.contract.BodyProfilePreferences

/**
 * Sends the device list and local profile changes to the bound OpenLink clients, and keeps
 * the streaming notification up while a client holds a session.
 */
@Singleton
class OpenWatchLinkPublisher @Inject constructor(
    private val host: OpenLinkHost,
    private val devices: LinkDevices,
    private val preferences: BodyProfilePreferences,
    private val profileSync: LinkProfileSync,
    private val streamingNotifier: LinkStreamingNotifier,
    private val settings: LinkSettingsStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false

    @Synchronized
    fun start() {
        if (started) return
        started = true
        scope.launch {
            devices.devices.drop(1).collect { host.publishDevices(it) }
        }
        scope.launch {
            preferences.bodyProfileFlow.collect { profile ->
                profileSync.onLocalProfile(profile)?.let(host::publishProfile)
            }
        }
        scope.launch { settings.changes.collect { host.publishSettings(it) } }
        scope.launch { streamingNotifier.run() }
    }
}
