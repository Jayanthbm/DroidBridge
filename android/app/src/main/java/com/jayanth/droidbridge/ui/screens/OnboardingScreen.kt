package com.jayanth.droidbridge.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.ScreenShare
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jayanth.droidbridge.AppState
import com.jayanth.droidbridge.PermissionItem
import com.jayanth.droidbridge.PermissionKind
import com.jayanth.droidbridge.ui.components.BrandMark
import com.jayanth.droidbridge.ui.components.GlowCard
import com.jayanth.droidbridge.ui.components.IconTile
import com.jayanth.droidbridge.ui.components.StatusPill

@Composable
fun OnboardingScreen(
    state: AppState,
    onTogglePermission: (PermissionKind) -> Unit,
    onAllowAll: () -> Unit,
    onContinue: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(
                start = 22.dp,
                end = 22.dp,
                top = 18.dp,
                bottom = 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(11.dp),
                    ) {
                        BrandMark(size = 36.dp)
                        Text(
                            text = "DroidBridge",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    StatusPill(
                        text = "Local only",
                        icon = Icons.Rounded.Lock,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }

            item { Spacer(Modifier.height(8.dp)) }

            item {
                Text(
                    text = "Your phone,",
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "on your Mac.",
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            item {
                Text(
                    text = "Mirror your screen, share a clipboard, and use your Mac's keyboard and mouse — all over your local network.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FeatureChip(Icons.Rounded.WifiTethering, "Wi-Fi")
                    FeatureChip(Icons.Rounded.Bluetooth, "Bluetooth HID")
                    FeatureChip(Icons.Rounded.Lock, "No ADB")
                }
            }

            item {
                GlowCard(contentPadding = PaddingValues(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(13.dp),
                    ) {
                        IconTile(
                            icon = Icons.Rounded.ScreenShare,
                            size = 44.dp,
                            container = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.7f),
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "Private by design",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = "Your screen and clipboard stay between your own devices. No cloud relay, no accessibility service.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text(
                            text = "Permissions",
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            text = "Android asks once for each item below.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    StatusPill(
                        text = "${state.grantedCount}/${state.permissions.size} ready",
                        color = if (state.allGranted) {
                            MaterialTheme.colorScheme.secondary
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                }
            }

            items(state.permissions, key = { it.kind.name }) { permission ->
                PermissionRow(
                    permission = permission,
                    onToggle = { onTogglePermission(permission.kind) },
                )
            }

            item {
                Text(
                    text = "Preview build · Permissions are simulated in Phase 1. Real prompts arrive with the connection layer.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
                )
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
            tonalElevation = 3.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp, vertical = 12.dp),
            ) {
                Button(
                    onClick = if (state.allGranted) onContinue else onAllowAll,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Text(
                        text = if (state.allGranted) "Continue" else "Allow permissions",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (state.allGranted) {
                        "Next: confirm a six-digit code with your Mac."
                    } else {
                        "You can change these anytime in Android settings."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun FeatureChip(
    icon: ImageVector,
    label: String,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.72f),
        shape = CircleShape,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PermissionRow(
    permission: PermissionItem,
    onToggle: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val accent = if (permission.granted) colors.secondary else colors.primary
    Surface(
        onClick = onToggle,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = if (permission.granted) {
            colors.secondaryContainer.copy(alpha = 0.32f)
        } else {
            colors.surfaceContainer.copy(alpha = 0.82f)
        },
        border = BorderStroke(
            width = 1.dp,
            color = accent.copy(alpha = 0.22f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            IconTile(
                icon = permission.kind.icon(),
                size = 44.dp,
                container = accent.copy(alpha = 0.14f),
                tint = accent,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = permission.title,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = permission.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = if (permission.granted) "Granted" else "Tap to allow",
                    style = MaterialTheme.typography.labelSmall,
                    color = accent,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Icon(
                imageVector = if (permission.granted) {
                    Icons.Rounded.CheckCircle
                } else {
                    Icons.Rounded.RadioButtonUnchecked
                },
                contentDescription = if (permission.granted) "Granted" else "Not granted",
                tint = accent,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

private fun PermissionKind.icon(): ImageVector = when (this) {
    PermissionKind.Notifications -> Icons.Rounded.NotificationsActive
    PermissionKind.NearbyDevices -> Icons.Rounded.WifiTethering
    PermissionKind.ScreenCapture -> Icons.Rounded.ScreenShare
    PermissionKind.Clipboard -> Icons.Rounded.ContentPaste
}
