package com.jayanth.droidbridge.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.LaptopMac
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.ScreenShare
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jayanth.droidbridge.AppState
import com.jayanth.droidbridge.MacSession
import com.jayanth.droidbridge.ui.components.BrandMark
import com.jayanth.droidbridge.ui.components.GlowCard
import com.jayanth.droidbridge.ui.components.IconTile
import com.jayanth.droidbridge.ui.components.StatusPill

@Composable
fun HomeScreen(
    state: AppState,
    onToggleMirroring: () -> Unit,
    onToggleClipboard: () -> Unit,
    onSyncClipboard: () -> Unit,
    onDisconnect: () -> Unit,
    onReconnect: () -> Unit,
    onPairAnotherMac: () -> Unit,
) {
    val session = state.session
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            BrandMark(size = 36.dp)
            Column(Modifier.weight(1f)) {
                Text(
                    text = "DroidBridge",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "Local session",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusPill(
                text = if (session.connected) "Connected" else "Offline",
                color = if (session.connected) {
                    MaterialTheme.colorScheme.secondary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = when {
                    !session.connected -> "Your Mac is offline."
                    session.mirroring -> "Mirroring preview."
                    else -> "Your Mac is ready."
                },
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = when {
                    !session.connected -> "Reconnect when both devices are back on the same local network."
                    session.mirroring -> "Screen mirroring is running in preview mode. Input routing arrives with the stream."
                    else -> "Mirror your screen, send a clipboard, or keep your phone within reach."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MiniChip(Icons.Rounded.Lock, "TLS 1.3")
                MiniChip(Icons.Rounded.Wifi, "On-device")
            }
            Spacer(Modifier.height(16.dp))

            MacCard(
                session = session,
                onToggleMirroring = onToggleMirroring,
                onReconnect = onReconnect,
            )

            Spacer(Modifier.height(12.dp))

            if (session.connected) {
                OutlinedButton(
                    onClick = onDisconnect,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(17.dp),
                    border = BorderStroke(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                    ),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.LinkOff,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Disconnect Mac", style = MaterialTheme.typography.labelLarge)
                }
            } else {
                OutlinedButton(
                    onClick = onPairAnotherMac,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(17.dp),
                    border = BorderStroke(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                    ),
                ) {
                    Text("Pair a different Mac", style = MaterialTheme.typography.labelLarge)
                }
            }

            Spacer(Modifier.height(14.dp))

            ClipboardCard(
                session = session,
                onToggleClipboard = onToggleClipboard,
                onSyncClipboard = onSyncClipboard,
            )

            Spacer(Modifier.height(12.dp))
            Text(
                text = "Everything stays on your local network. No account, no cloud relay.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                modifier = Modifier.padding(bottom = 14.dp),
            )
        }
    }
}

@Composable
private fun MacCard(
    session: MacSession,
    onToggleMirroring: () -> Unit,
    onReconnect: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    GlowCard(contentPadding = PaddingValues(18.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IconTile(
                icon = Icons.Rounded.LaptopMac,
                size = 46.dp,
                container = colors.primaryContainer.copy(alpha = 0.65f),
                tint = colors.onPrimaryContainer,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = session.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = session.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            StatusPill(
                text = when {
                    !session.connected -> "Offline"
                    session.mirroring -> "Mirroring"
                    else -> "Ready"
                },
                color = when {
                    !session.connected -> colors.error
                    session.mirroring -> colors.tertiary
                    else -> colors.secondary
                },
            )
        }

        Spacer(Modifier.height(16.dp))

        if (session.connected) {
            Button(
                onClick = onToggleMirroring,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(18.dp),
                colors = if (session.mirroring) {
                    ButtonDefaults.buttonColors(
                        containerColor = colors.tertiaryContainer,
                        contentColor = colors.onTertiaryContainer,
                    )
                } else {
                    ButtonDefaults.buttonColors(
                        containerColor = colors.primary,
                        contentColor = colors.onPrimary,
                    )
                },
            ) {
                Icon(
                    imageVector = Icons.Rounded.ScreenShare,
                    contentDescription = null,
                    modifier = Modifier.size(19.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (session.mirroring) "Stop Screen Mirroring" else "Start Screen Mirroring",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Wifi,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                    tint = colors.onSurfaceVariant,
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = if (session.mirroring) "Preview stream · Phase 1" else "Ready over Wi-Fi + Bluetooth",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
            }
        } else {
            Button(
                onClick = onReconnect,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(18.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(19.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("Reconnect", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun ClipboardCard(
    session: MacSession,
    onToggleClipboard: () -> Unit,
    onSyncClipboard: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    GlowCard(contentPadding = PaddingValues(18.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IconTile(
                icon = Icons.Rounded.ContentPaste,
                size = 46.dp,
                container = colors.secondaryContainer.copy(alpha = 0.55f),
                tint = colors.onSecondaryContainer,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Clipboard Sync",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "Copy on one device, paste on the other.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            Switch(
                checked = session.clipboardSync,
                onCheckedChange = { onToggleClipboard() },
                enabled = session.connected,
            )
        }

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 14.dp),
            color = colors.outlineVariant.copy(alpha = 0.4f),
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Last sync",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
                Text(
                    text = when {
                        !session.connected -> "Unavailable offline"
                        !session.clipboardSync -> "Paused"
                        else -> session.lastClipboardSync
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            OutlinedButton(
                onClick = onSyncClipboard,
                enabled = session.connected && session.clipboardSync,
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 11.dp, vertical = 0.dp),
                border = BorderStroke(
                    width = 1.dp,
                    color = colors.outlineVariant.copy(alpha = 0.7f),
                ),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Sync,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "Sync Clipboard Now",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun MiniChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
) {
    Row(
        modifier = Modifier
            .background(
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.65f),
                shape = RoundedCornerShape(50),
            )
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(12.dp),
            tint = MaterialTheme.colorScheme.secondary,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
