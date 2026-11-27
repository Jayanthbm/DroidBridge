package com.jayanth.droidbridge.ui.screens

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
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jayanth.droidbridge.AppState
import com.jayanth.droidbridge.ui.components.DeviceBridgeGraphic
import com.jayanth.droidbridge.ui.components.GlowCard
import com.jayanth.droidbridge.ui.components.PairingCode
import com.jayanth.droidbridge.ui.components.StatusPill
import com.jayanth.droidbridge.ui.components.StepNumber

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PairingScreen(
    state: AppState,
    onBack: () -> Unit,
    onPair: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.Rounded.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "Pair with Mac",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            StatusPill(
                text = "TLS 1.3",
                icon = Icons.Rounded.Lock,
                color = MaterialTheme.colorScheme.secondary,
            )
            Spacer(Modifier.width(12.dp))
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(10.dp))
            DeviceBridgeGraphic(
                connected = false,
                modifier = Modifier.padding(vertical = 2.dp),
            )
            Spacer(Modifier.height(18.dp))
            Text(
                text = "Confirm the code",
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Confirm this six-digit code matches ${state.session.name}.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            PairingCode(code = state.pairingCode)
            Spacer(Modifier.height(12.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Bluetooth,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Compare the same code on both devices.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(22.dp))
            GlowCard(contentPadding = PaddingValues(18.dp)) {
                Text(
                    text = "On your Mac",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(12.dp))
                PairingStep(1, "Open DroidBridge on your Mac.")
                Spacer(Modifier.height(10.dp))
                PairingStep(2, "Keep both devices on the same Wi-Fi network.")
                Spacer(Modifier.height(10.dp))
                PairingStep(3, "Confirm the same code shown above.")
            }

            Spacer(Modifier.height(18.dp))
            if (state.isPairing || !state.isPairingCodeReady) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    LoadingIndicator(
                        modifier = Modifier.size(17.dp),
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    Text(
                        text = state.pairingStatus,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.secondary,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                StatusPill(
                    text = "Code ready · compare on both devices",
                    icon = Icons.Rounded.Link,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onPair,
                enabled = state.isPairingCodeReady && !state.isPairing,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                if (state.isPairing) {
                    LoadingIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Pairing…", style = MaterialTheme.typography.titleMedium)
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Link,
                        contentDescription = null,
                        modifier = Modifier.size(19.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (state.isPairingCodeReady) "Pair with Mac" else "Looking for Mac…",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
            TextButton(onClick = onBack, enabled = !state.isPairing) {
                Text("Cancel")
            }
            Text(
                text = "Secure TLS 1.3 · code verified on both devices",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }
    }
}

@Composable
private fun PairingStep(number: Int, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        StepNumber(number)
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
