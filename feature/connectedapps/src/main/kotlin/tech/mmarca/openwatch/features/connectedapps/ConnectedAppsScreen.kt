package tech.mmarca.openwatch.features.connectedapps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.mmarca.openlink.OpenLink
import tech.mmarca.openlink.OpenLinkTrust
import tech.mmarca.openwatch.core.presentation.localizedList
import tech.mmarca.openwatch.link.LinkSessionStatus
import tech.mmarca.openwatch.ui.components.ObscuredTouchGuard
import tech.mmarca.openwatch.ui.components.BulletRow
import tech.mmarca.openwatch.ui.components.OpenWatchCard
import tech.mmarca.openwatch.ui.components.ScreenColumn
import tech.mmarca.openwatch.ui.components.SwitchRow
import tech.mmarca.openwatch.ui.theme.OpenWatchTheme
import tech.mmarca.openwatch.ui.theme.Spacing

/**
 * The Connected apps destination: the only place that knows the ViewModel. It keeps other apps
 * from drawing over the screen while it shows; [ConnectedAppsScreen] draws.
 */
@Composable
fun ConnectedAppsRoute(viewModel: ConnectedAppsViewModel = hiltViewModel()) {
    val apps by viewModel.apps.collectAsStateWithLifecycle()
    HideOverlaysWhileShown()

    ConnectedAppsScreen(
        apps = apps,
        onAllow = viewModel::allow,
        onBlock = viewModel::block,
        onForget = viewModel::forget,
        onCapabilityChange = viewModel::setCapability,
    )
}

/**
 * The apps that asked to use OpenWatch over OpenLink: allow or block one that asks, take a
 * capability back from one that is connected, or forget it. Each action names the app by its package.
 */
@Composable
fun ConnectedAppsScreen(
    apps: List<ConnectedApp>,
    onAllow: (packageName: String) -> Unit,
    onBlock: (packageName: String) -> Unit,
    onForget: (packageName: String) -> Unit,
    onCapabilityChange: (packageName: String, capability: String, granted: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    ScreenColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Text(
            text = stringResource(R.string.connected_apps_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (apps.isEmpty()) {
            Text(text = stringResource(R.string.connected_apps_empty), style = MaterialTheme.typography.bodyMedium)
        }
        // Pending first: they are waiting for an answer.
        apps.sortedBy { it.trust != OpenLinkTrust.PENDING }.forEach { app ->
            ConnectedAppCard(
                app = app,
                onAllow = { onAllow(app.packageName) },
                onBlock = { onBlock(app.packageName) },
                onForget = { onForget(app.packageName) },
                onCapability = { capability, granted -> onCapabilityChange(app.packageName, capability, granted) },
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ConnectedAppsScreenPreview() {
    val now = 1_763_800_000_000L
    OpenWatchTheme {
        ConnectedAppsScreen(
            apps = listOf(
                ConnectedApp(
                    packageName = "tech.mmarca.openvitals",
                    label = "OpenVitals",
                    trust = OpenLinkTrust.TRUSTED,
                    requested = listOf(
                        OpenLink.CAPABILITY_DEVICES,
                        OpenLink.CAPABILITY_PROFILE,
                        OpenLink.CAPABILITY_RECORDING,
                        OpenLink.CAPABILITY_LIVE,
                    ),
                    granted = setOf(OpenLink.CAPABILITY_DEVICES, OpenLink.CAPABILITY_PROFILE, OpenLink.CAPABILITY_LIVE),
                    inUse = true,
                    profileReceivedAtMillis = now - 2 * 60 * 60_000L,
                    sessions = listOf(
                        LinkSessionStatus("tech.mmarca.openvitals", LinkSessionStatus.Kind.HEART_RATE, listOf("Forerunner 265")),
                    ),
                    nowMillis = now,
                    firstParty = true,
                    signerFingerprint = "0416 C065 1543 E951",
                ),
                ConnectedApp(
                    packageName = "com.example.vitals",
                    label = "OpenVitals Plus",
                    trust = OpenLinkTrust.PENDING,
                    requested = listOf(OpenLink.CAPABILITY_PROFILE, OpenLink.CAPABILITY_LIVE),
                    granted = emptySet(),
                    lastSeenAtMillis = now - 5 * 60_000L,
                    nowMillis = now,
                    signerFingerprint = "9F2A 61C0 D4E7 3B58",
                    lookalikeOf = "OpenVitals",
                ),
            ),
            onAllow = {},
            onBlock = {},
            onForget = {},
            onCapabilityChange = { _, _, _ -> },
        )
    }
}

@Composable
private fun ConnectedAppCard(
    app: ConnectedApp,
    onAllow: () -> Unit,
    onBlock: () -> Unit,
    onForget: () -> Unit,
    onCapability: (String, Boolean) -> Unit,
) {
    OpenWatchCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(text = app.label, style = MaterialTheme.typography.titleMedium)
            // A label is the app's own choice; the package and the key are what identify it.
            if (!app.firstParty) {
                Text(
                    text = stringResource(R.string.connected_apps_identity, app.packageName, app.signerFingerprint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = stringResource(
                    when (app.trust) {
                        OpenLinkTrust.PENDING -> R.string.connected_apps_state_pending
                        OpenLinkTrust.TRUSTED -> R.string.connected_apps_state_trusted
                        OpenLinkTrust.BLOCKED -> R.string.connected_apps_state_blocked
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (app.unknownSignerForKnownName && app.trust != OpenLinkTrust.BLOCKED) {
                Text(
                    text = stringResource(R.string.connected_apps_unknown_signer, app.label),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (app.lookalikeOf != null && app.trust != OpenLinkTrust.BLOCKED) {
                Text(
                    text = stringResource(R.string.connected_apps_lookalike, app.label, app.lookalikeOf),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (app.trust != OpenLinkTrust.BLOCKED) ActivityLines(app)
            when (app.trust) {
                OpenLinkTrust.PENDING -> {
                    Text(text = stringResource(R.string.connected_apps_asks_for), style = MaterialTheme.typography.bodyMedium)
                    app.requested.forEach { capability ->
                        BulletRow(text = stringResource(capabilityLabel(capability)), style = MaterialTheme.typography.bodyMedium)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Button(onClick = onAllow) { Text(stringResource(R.string.connected_apps_allow)) }
                        OutlinedButton(onClick = onBlock) { Text(stringResource(R.string.connected_apps_block)) }
                    }
                }
                OpenLinkTrust.TRUSTED -> {
                    app.requested.forEach { capability ->
                        // The card's column already holds the inset.
                        SwitchRow(
                            title = stringResource(capabilityLabel(capability)),
                            checked = capability in app.granted,
                            onCheckedChange = { onCapability(capability, it) },
                            contentPadding = PaddingValues(0.dp),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        TextButton(onClick = onBlock) { Text(stringResource(R.string.connected_apps_block)) }
                        TextButton(onClick = onForget) { Text(stringResource(R.string.connected_apps_forget)) }
                    }
                }
                OpenLinkTrust.BLOCKED -> {
                    TextButton(onClick = onForget) { Text(stringResource(R.string.connected_apps_unblock)) }
                }
            }
        }
    }
}

/** In use now or when it last was, what its sessions do to a watch, and when its profile came. */
@Composable
private fun ActivityLines(app: ConnectedApp) {
    val lines = buildList {
        when {
            app.inUse -> add(stringResource(R.string.connected_apps_in_use))
            app.lastSeenAtMillis.isRealTime() ->
                add(stringResource(R.string.connected_apps_last_connected, relativeTime(app.lastSeenAtMillis, app.nowMillis)))
        }
        app.sessions.forEach { add(sessionLine(it)) }
        if (app.profileReceivedAtMillis.isRealTime()) {
            add(stringResource(R.string.connected_apps_profile_received, relativeTime(app.profileReceivedAtMillis, app.nowMillis)))
        }
    }
    lines.forEach { line ->
        Text(text = line, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun sessionLine(status: LinkSessionStatus): String {
    val devices = localizedList(status.deviceNames)
    return when (status.kind) {
        LinkSessionStatus.Kind.HEART_RATE -> stringResource(R.string.connected_apps_session_heart_rate, devices)
        LinkSessionStatus.Kind.HEART_RATE_AND_STEPS -> stringResource(R.string.connected_apps_session_heart_rate_steps, devices)
        LinkSessionStatus.Kind.KEEPING_STREAMING -> stringResource(R.string.connected_apps_session_keeping, devices)
        LinkSessionStatus.Kind.RECORDING_WORKOUT -> stringResource(R.string.connected_apps_session_workout, devices)
        LinkSessionStatus.Kind.WAITING -> stringResource(R.string.connected_apps_session_waiting, devices)
    }
}

internal fun capabilityLabel(capability: String): Int = when (capability) {
    OpenLink.CAPABILITY_PROFILE -> R.string.connected_apps_capability_profile
    OpenLink.CAPABILITY_DEVICES -> R.string.connected_apps_capability_devices
    OpenLink.CAPABILITY_RECORDING -> R.string.connected_apps_capability_recording
    OpenLink.CAPABILITY_SETTINGS -> R.string.connected_apps_capability_settings
    else -> R.string.connected_apps_capability_live
}

/**
 * While this screen shows, a touch that passes through another app's window is dropped, and
 * from API 31 other apps' overlays are hidden. Another app cannot then draw over the screen
 * and steer a tap on Allow or on a capability switch. The activity drops the touches: Compose
 * does not apply a view's own `filterTouchesWhenObscured`.
 */
// HIDE_OVERLAY_WINDOWS is declared by the app's manifest, which this module's lint does not
// see; a normal permission, granted at install.
@SuppressLint("MissingPermission")
@Composable
private fun HideOverlaysWhileShown() {
    val view = LocalView.current
    DisposableEffect(view) {
        val activity = view.context.findActivity()
        val window = activity?.window
        (activity as? ObscuredTouchGuard)?.dropObscuredTouches = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) window?.setHideOverlayWindows(true)
        onDispose {
            (activity as? ObscuredTouchGuard)?.dropObscuredTouches = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) window?.setHideOverlayWindows(false)
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
