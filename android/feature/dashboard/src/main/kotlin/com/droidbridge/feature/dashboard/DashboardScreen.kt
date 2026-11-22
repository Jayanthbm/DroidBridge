package com.droidbridge.feature.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.droidbridge.core.network.ServiceDiscovery
import com.droidbridge.core.pairing.Pairing

/**
 * What discovery is doing, as the screen needs it.
 *
 * The distinction that matters is between "nothing found yet", "waiting for you in the system
 * picker" and "the OS refuses LAN access". Collapsing those into an empty list is what makes a
 * permission problem look like a broken product.
 */
data class DiscoveryUiState(
    val status: ServiceDiscovery.Status = ServiceDiscovery.Status.Idle,
    val needsPermission: Boolean = false,
)

/**
 * The Phase 2 dashboard: find the Mac, pair, and show the live session.
 *
 * Everything the user sees here is derived from [Pairing.State] — the screen does not decide whether
 * a peer is trusted, it renders the decision the state machine made. In particular the "Codes match"
 * button only exists in [Pairing.Phase.CONFIRMING], so there is no path from this UI to a trusted
 * session that skipped the comparison.
 */
@Composable
fun DashboardScreen(
    buildInfo: String,
    state: Pairing.State,
    peers: List<ServiceDiscovery.Peer>,
    roundTrips: Int,
    lastRttMs: Long,
    onStartDiscovery: () -> Unit,
    onConnect: (ServiceDiscovery.Peer) -> Unit,
    onConfirm: () -> Unit,
    onReject: () -> Unit,
    onDrop: () -> Unit,
    onStop: () -> Unit,
    discovery: DiscoveryUiState = DiscoveryUiState(),
    onGrantLocalNetwork: () -> Unit = {},
) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("DroidBridge", style = MaterialTheme.typography.headlineSmall)
            Text(buildInfo, style = MaterialTheme.typography.bodySmall)

            Card {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Connection", style = MaterialTheme.typography.titleMedium)
                    Text("state: ${describe(state)}", style = MaterialTheme.typography.bodyLarge)
                    state.code?.let { code ->
                        Text(code, style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Monospace)
                        Text(
                            "Compare this code with the one on the Mac. Only confirm if they match exactly.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    state.failure?.let { failure ->
                        Text("reason: ${explain(failure)}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (state.phase == Pairing.Phase.CONNECTED) {
                        Text(
                            "round trips: $roundTrips" + if (lastRttMs >= 0) " · last ${lastRttMs}ms" else "",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = onStartDiscovery, modifier = Modifier.weight(1f)) { Text("Find the Mac") }
                OutlinedButton(onClick = onStop, modifier = Modifier.weight(1f)) { Text("Disconnect") }
            }

            if (state.phase == Pairing.Phase.CONFIRMING) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = onConfirm, modifier = Modifier.weight(1f)) { Text("Codes match — confirm") }
                    OutlinedButton(onClick = onReject, modifier = Modifier.weight(1f)) { Text("They differ — reject") }
                }
            }

            if (state.phase == Pairing.Phase.CONNECTED) {
                OutlinedButton(onClick = onDrop, modifier = Modifier.fillMaxWidth()) {
                    Text("Drop the connection (proves reconnect)")
                }
            }

            Text("Nearby", style = MaterialTheme.typography.titleMedium)
            when {
                discovery.needsPermission -> Card {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Local network access is off", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Android 17 needs this permission before the app can find your Mac. " +
                                "Nothing is scanned until you allow it.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(onClick = onGrantLocalNetwork, modifier = Modifier.fillMaxWidth()) {
                            Text("Allow local network access")
                        }
                    }
                }
                discovery.status is ServiceDiscovery.Status.AwaitingPicker -> Text(
                    "Choose DroidBridge on your Mac in the system picker",
                    style = MaterialTheme.typography.bodySmall,
                )
                discovery.status is ServiceDiscovery.Status.Failed -> Text(
                    "discovery failed: ${(discovery.status as ServiceDiscovery.Status.Failed).detail}",
                    style = MaterialTheme.typography.bodySmall,
                )
                peers.isEmpty() -> Text("no Mac found yet", style = MaterialTheme.typography.bodySmall)
            }
            peers.forEach { peer ->
                Card {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(peer.serviceName, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${peer.host}:${peer.port} · protocol ${peer.protocolVersion ?: "unstated"}" +
                                    if (peer.isCompatible) "" else " · incompatible",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Button(
                            onClick = { onConnect(peer) },
                            enabled = peer.isCompatible && state.phase != Pairing.Phase.CONNECTED,
                        ) { Text("Connect") }
                    }
                }
            }
        }
    }
}

private fun describe(state: Pairing.State): String = when (state.phase) {
    Pairing.Phase.IDLE -> "idle"
    Pairing.Phase.DISCOVERING -> "looking for the Mac"
    Pairing.Phase.CONNECTING -> "connecting"
    Pairing.Phase.CONFIRMING -> "waiting for you to compare the code"
    Pairing.Phase.CONNECTED -> "connected and authenticated"
    Pairing.Phase.RECONNECTING -> "reconnecting (attempt ${state.attempt})"
    Pairing.Phase.FAILED -> "stopped"
}

private fun explain(failure: Pairing.Failure): String = when (failure) {
    Pairing.Failure.INCOMPATIBLE_VERSION -> "the Mac speaks a protocol version this build does not"
    Pairing.Failure.HANDSHAKE -> "the secure connection could not be established"
    Pairing.Failure.PIN_MISMATCH -> "the Mac's identity does not match the one you paired with"
    Pairing.Failure.CODE_REJECTED -> "you rejected the code; nothing was saved"
    Pairing.Failure.GAVE_UP -> "the connection kept dropping, so retrying stopped"
}
