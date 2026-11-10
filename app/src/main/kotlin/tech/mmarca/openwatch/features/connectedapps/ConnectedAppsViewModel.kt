package tech.mmarca.openwatch.features.connectedapps

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import tech.mmarca.openlink.OpenLink
import tech.mmarca.openlink.OpenLinkTrust
import tech.mmarca.openlink.host.OpenLinkClientRecord
import tech.mmarca.openlink.host.OpenLinkHost
import tech.mmarca.openlink.host.OpenLinkHostCore
import tech.mmarca.openwatch.link.FirstPartyApps
import tech.mmarca.openwatch.link.LinkLiveSessions
import tech.mmarca.openwatch.link.LinkSessionStatus
import tech.mmarca.openwatch.link.linkSessionStatuses

/** One app that asked to use OpenWatch over OpenLink, as the screen shows it. */
data class ConnectedApp(
    val packageName: String,
    val label: String,
    val trust: OpenLinkTrust,
    /** What it asked for, in the order the screen lists them. */
    val requested: List<String>,
    val granted: Set<String>,
    /** Bound with a listener now. */
    val inUse: Boolean = false,
    /** When it last connected or went away; 0 if never seen since this was recorded. */
    val lastSeenAtMillis: Long = 0L,
    /** When OpenWatch last took a body profile from it; 0 if never. */
    val profileReceivedAtMillis: Long = 0L,
    /** What its recording sessions are doing to a watch now. */
    val sessions: List<LinkSessionStatus> = emptyList(),
    /** The clock the relative times are read against; ticks while the screen shows. */
    val nowMillis: Long = 0L,
    /** It uses a first-party name but is not signed by a first-party key; the screen warns. */
    val unknownSignerForKnownName: Boolean = false,
)

@HiltViewModel
class ConnectedAppsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val core: OpenLinkHostCore,
    host: OpenLinkHost,
    liveSessions: LinkLiveSessions,
) : ViewModel() {

    private val statuses = combine(host.activeSessions, liveSessions.activity, ::linkSessionStatuses)

    private val firstParty = FirstPartyApps.from(context)

    val apps: StateFlow<List<ConnectedApp>> =
        combine(core.clients, host.connected, statuses, ticker()) { records, connected, sessions, now ->
            records.map { record ->
                record.toConnectedApp().copy(
                    inUse = record.packageName in connected,
                    sessions = sessions.filter { it.packageName == record.packageName },
                    nowMillis = now,
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun allow(packageName: String) = core.allow(packageName)

    fun block(packageName: String) = core.block(packageName)

    /** Forgets the app: a blocked one may ask again, a trusted one must. */
    fun forget(packageName: String) = core.forget(packageName)

    fun setCapability(packageName: String, capability: String, granted: Boolean) {
        if (granted) core.grant(packageName, capability) else core.revoke(packageName, capability)
    }

    private fun OpenLinkClientRecord.toConnectedApp() = ConnectedApp(
        packageName = packageName,
        label = appLabel(context, packageName),
        trust = trust,
        requested = CapabilityOrder.filter { it in requested },
        granted = capabilities,
        lastSeenAtMillis = lastSeenAtMillis,
        profileReceivedAtMillis = profileReceivedAtMillis,
        unknownSignerForKnownName = firstParty.usesNameWithoutKey(packageName, knownSigner),
    )

    /** The time now, again every half minute, so "5 min ago" moves on an open screen. */
    private fun ticker(): Flow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(30_000)
        }
    }

    companion object {
        val CapabilityOrder = listOf(
            OpenLink.CAPABILITY_DEVICES,
            OpenLink.CAPABILITY_PROFILE,
            OpenLink.CAPABILITY_RECORDING,
            OpenLink.CAPABILITY_LIVE,
        )
    }
}

/** The app's name as the launcher shows it, or its package name if it is not visible. */
internal fun appLabel(context: Context, packageName: String): String = runCatching {
    val pm = context.packageManager
    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        pm.getApplicationInfo(packageName, 0)
    }
    pm.getApplicationLabel(info).toString()
}.getOrDefault(packageName)
