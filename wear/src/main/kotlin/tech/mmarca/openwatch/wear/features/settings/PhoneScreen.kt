package tech.mmarca.openwatch.wear.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import tech.mmarca.openwatch.wear.PendingPhone
import tech.mmarca.openwatch.wear.R
import tech.mmarca.openwatch.wear.TrustedPhone
import tech.mmarca.openwatch.wear.WearLinkUiState
import tech.mmarca.openwatch.wear.ui.preview.WearPreviews
import tech.mmarca.openwatch.wear.ui.theme.OpenWatchWearTheme

/**
 * The phones that may read this watch, and the link they use: whether it is
 * up, which phones are allowed and whether each is still paired, and a phone
 * asking to be allowed, to answer on the spot.
 */
@Composable
internal fun PhoneScreen(
    link: WearLinkUiState,
    bluetoothGranted: Boolean,
    onAllow: (PendingPhone) -> Unit,
    onBlock: (PendingPhone) -> Unit,
    onOpenBluetoothSettings: () -> Unit,
) {
    val listState = rememberTransformingLazyColumnState()
    val transformationSpec = rememberTransformationSpec()
    val rowModifier = Modifier.fillMaxWidth()

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
            item {
                ListHeader(
                    modifier = rowModifier.transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec),
                ) { Text(stringResource(R.string.settings_phone)) }
            }
            item {
                InfoRow(
                    label = stringResource(R.string.phone_link),
                    value = stringResource(
                        when {
                            !bluetoothGranted -> R.string.status_link_not_granted
                            !link.bluetoothOn -> R.string.status_link_bluetooth_off
                            link.listening -> R.string.status_link_listening
                            else -> R.string.status_link_starting
                        },
                    ),
                    modifier = rowModifier.transformedHeight(this, transformationSpec),
                )
            }
            link.pending?.let { pending ->
                item {
                    Column(
                        modifier = rowModifier.transformedHeight(this, transformationSpec),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = stringResource(
                                if (pending.newKey) R.string.status_phone_new_key else R.string.status_phone_pending,
                                pending.name,
                            ),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        if (pending.newKey) {
                            Text(
                                text = stringResource(R.string.phone_new_key_reason),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onAllow(pending) }) { Text(stringResource(R.string.action_allow)) }
                            Button(
                                onClick = { onBlock(pending) },
                                colors = ButtonDefaults.outlinedButtonColors(),
                            ) { Text(stringResource(R.string.action_block)) }
                        }
                    }
                }
            }
            items(link.trusted.size) { index ->
                val phone = link.trusted[index]
                InfoRow(
                    label = phone.name,
                    value = stringResource(phone.stateLabel(link)),
                    modifier = rowModifier.transformedHeight(this, transformationSpec),
                )
            }
            if (link.trusted.isEmpty() && link.pending == null) {
                item {
                    Text(
                        text = stringResource(R.string.phone_none_hint),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = rowModifier.transformedHeight(this, transformationSpec).padding(horizontal = 12.dp),
                    )
                }
            }
            if (link.bondLost.isNotEmpty()) {
                item {
                    Button(
                        onClick = onOpenBluetoothSettings,
                        label = { Text(stringResource(R.string.action_open_bluetooth)) },
                        colors = ButtonDefaults.filledTonalButtonColors(),
                        modifier = rowModifier.transformedHeight(this, transformationSpec),
                        transformation = SurfaceTransformation(transformationSpec),
                    )
                }
            }
        }
    }
}

/** What to say about an allowed phone: reachable, no longer paired, or waiting for the link. */
private fun TrustedPhone.stateLabel(link: WearLinkUiState): Int = when {
    link.bondLost.any { it.address == address } -> R.string.phone_state_pairing_lost
    link.listening -> R.string.settings_phone_connected
    else -> R.string.phone_state_waiting
}

/** A label over its value, for a fact the user reads and does not tap. */
@Composable
internal fun InfoRow(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        Text(text = label, style = MaterialTheme.typography.titleSmall)
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@WearPreviews
@Composable
private fun PhoneScreenPreview() {
    val phone = TrustedPhone(address = "AA:BB", name = "Pixel 7", trustedAtMillis = 0L)
    OpenWatchWearTheme {
        AppScaffold {
            PhoneScreen(
                link = WearLinkUiState(listening = true, trusted = listOf(phone)),
                bluetoothGranted = true,
                onAllow = {},
                onBlock = {},
                onOpenBluetoothSettings = {},
            )
        }
    }
}
