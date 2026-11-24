package tech.mmarca.openwatch.link

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import tech.mmarca.openlink.host.OpenLinkHost
import tech.mmarca.openwatch.core.performance.ApplicationScope
import tech.mmarca.openwatch.data.repository.contract.BodyProfilePreferences
import tech.mmarca.openwatch.healthconnect.HealthConnectWrites

/**
 * Sends the device list, local profile changes, the shared settings (the daily goals) and what
 * OpenWatch wrote to Health Connect (types and times, `onDataWritten`) to the bound OpenLink clients, and keeps
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
    private val writes: HealthConnectWrites,
    @ApplicationScope appScope: CoroutineScope,
) {
    private val scope = appScope
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
        scope.launch { streamingNotifier.run() }
        // Every change, from a connected app or from a watch's card, goes to every client that may see it.
        scope.launch { settings.changes.collect { host.publishSettings(it) } }
        // Record types and the time they cover, never a value; one per burst of a sync's pages.
        scope.launch { writes.written.asDataWritten().collect { host.publishDataWritten(it) } }
    }
}
