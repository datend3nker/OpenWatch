package tech.mmarca.openwatch.features.connectedapps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.mmarca.openlink.OpenLink
import tech.mmarca.openwatch.MainActivity
import tech.mmarca.openlink.OpenLinkTrust
import tech.mmarca.openwatch.R
import tech.mmarca.openwatch.link.LinkSessionStatus
import tech.mmarca.openwatch.ui.components.OpenWatchCard
import tech.mmarca.openwatch.ui.theme.Spacing

/**
 * The apps that asked to use OpenWatch over OpenLink: allow or block one that asks, take a
 * capability back from one that is connected, or forget it.
 */
@Composable
fun ConnectedAppsScreen(viewModel: ConnectedAppsViewModel) {
    val apps by viewModel.apps.collectAsStateWithLifecycle()
    HideOverlaysWhileShown()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
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
                onAllow = { viewModel.allow(app.packageName) },
                onBlock = { viewModel.block(app.packageName) },
                onForget = { viewModel.forget(app.packageName) },
                onCapability = { capability, granted -> viewModel.setCapability(app.packageName, capability, granted) },
            )
        }
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
            }
            if (app.trust != OpenLinkTrust.BLOCKED) ActivityLines(app)
            when (app.trust) {
                OpenLinkTrust.PENDING -> {
                    Text(text = stringResource(R.string.connected_apps_asks_for), style = MaterialTheme.typography.bodyMedium)
                    app.requested.forEach { capability ->
                        Text(text = "• " + stringResource(capabilityLabel(capability)), style = MaterialTheme.typography.bodyMedium)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Button(onClick = onAllow) { Text(stringResource(R.string.connected_apps_allow)) }
                        OutlinedButton(onClick = onBlock) { Text(stringResource(R.string.connected_apps_block)) }
                    }
                }
                OpenLinkTrust.TRUSTED -> {
                    app.requested.forEach { capability ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = stringResource(capabilityLabel(capability)),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(modifier = Modifier.width(Spacing.sm))
                            Switch(checked = capability in app.granted, onCheckedChange = { onCapability(capability, it) })
                        }
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
    val devices = status.deviceNames.joinToString(", ")
    return when (status.kind) {
        LinkSessionStatus.Kind.HEART_RATE -> stringResource(R.string.connected_apps_session_heart_rate, devices)
        LinkSessionStatus.Kind.HEART_RATE_AND_STEPS -> stringResource(R.string.connected_apps_session_heart_rate_steps, devices)
        LinkSessionStatus.Kind.KEEPING_STREAMING -> stringResource(R.string.connected_apps_session_keeping, devices)
        LinkSessionStatus.Kind.WAITING -> stringResource(R.string.connected_apps_session_waiting, devices)
    }
}

internal fun capabilityLabel(capability: String): Int = when (capability) {
    OpenLink.CAPABILITY_PROFILE -> R.string.connected_apps_capability_profile
    OpenLink.CAPABILITY_DEVICES -> R.string.connected_apps_capability_devices
    OpenLink.CAPABILITY_RECORDING -> R.string.connected_apps_capability_recording
    else -> R.string.connected_apps_capability_live
}

/**
 * While this screen shows, a touch that passes through another app's window is dropped, and
 * from API 31 other apps' overlays are hidden. Another app cannot then draw over the screen
 * and steer a tap on Allow or on a capability switch. The activity drops the touches: Compose
 * does not apply a view's own `filterTouchesWhenObscured`.
 */
@Composable
private fun HideOverlaysWhileShown() {
    val view = LocalView.current
    DisposableEffect(view) {
        val activity = view.context.findActivity()
        val window = activity?.window
        (activity as? MainActivity)?.dropObscuredTouches = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) window?.setHideOverlayWindows(true)
        onDispose {
            (activity as? MainActivity)?.dropObscuredTouches = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) window?.setHideOverlayWindows(false)
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
