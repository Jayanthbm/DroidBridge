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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.droidbridge.core.network.ServiceDiscovery
import com.droidbridge.core.pairing.Pairing
import com.droidbridge.feature.clipboard.ClipboardCard

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

/** Mirroring state the screen renders. Counts only: no frame data ever reaches the UI. */
data class MirroringUiState(
    val state: String = "idle",
    val capturing: Boolean = false,
    val framesSent: Long = 0,
    val bytesSent: Long = 0,
    val keyframeRequests: Long = 0,
    val maxSendMs: Long = 0,
    val connected: Boolean = false,
    /** The user's audio choice for the next share; audio and screen start together. */
    val audioEnabled: Boolean = true,
    /** Measured audio state: "off", "capturing 44100Hz stereo", or why it is off. */
    val audioState: String = "off",
    val audioFrames: Long = 0,
    /** RMS of the captured PCM, so silence can be shown as silence rather than as "working". */
    val audioRms: Long = 0,
)

/** Clipboard state the screen renders; no clipboard content is ever held here. */
data class ClipboardUiState(
    val status: String = "not connected",
    val paused: Boolean = false,
    val hasIncoming: Boolean = false,
    val incomingBytes: Int = 0,
    val connected: Boolean = false,
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
    liveSessionCode: String? = null,
    onStartDiscovery: () -> Unit,
    onConnect: (ServiceDiscovery.Peer) -> Unit,
    onConfirm: () -> Unit,
    onReject: () -> Unit,
    onDrop: () -> Unit,
    onStop: () -> Unit,
    onForgetPairing: () -> Unit = {},
    discovery: DiscoveryUiState = DiscoveryUiState(),
    onGrantLocalNetwork: () -> Unit = {},
    clipboard: ClipboardUiState = ClipboardUiState(),
    mirroring: MirroringUiState = MirroringUiState(),
    onStartMirroring: () -> Unit = {},
    onStopMirroring: () -> Unit = {},
    onAudioEnabledChange: (Boolean) -> Unit = {},
    onSendClipboard: () -> Unit = {},
    onApplyClipboard: () -> Unit = {},
    onDismissClipboard: () -> Unit = {},
    onPauseClipboard: (Boolean) -> Unit = {},
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
                    Text(
                    "Connection",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() },
                )
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
                    if (state.phase == Pairing.Phase.FAILED) {
                        // A failed state with no next step reads as a dead app. The retry always
                        // existed (start discovery again), but nothing on screen said so.
                        Button(onClick = onStartDiscovery, modifier = Modifier.fillMaxWidth()) {
                            Text("Try again")
                        }
                    }
                    if (state.phase == Pairing.Phase.CONNECTED) {
                        Text(
                            "round trips: $roundTrips" + if (lastRttMs >= 0) " · last ${lastRttMs}ms" else "",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        // Shown while connected so a one-sided re-pair (the Mac forgot the pin,
                        // the phone did not) still has a code to compare against the Mac's
                        // confirmation dialog. The code is derived from public keys, not a secret.
                        liveSessionCode?.let { code ->
                            Text(
                                "your pairing code: ${code.take(3)} ${code.drop(3)} — compare it " +
                                    "if the Mac asks you to confirm a re-pair",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = onStartDiscovery, modifier = Modifier.weight(1f)) { Text("Find the Mac") }
                OutlinedButton(onClick = onStop, modifier = Modifier.weight(1f)) { Text("Disconnect") }
            }
            // Pairing revocation: the stored pin is what makes reconnects silent. Forgetting it
            // makes the next connection a first pairing again, code comparison included.
            OutlinedButton(onClick = onForgetPairing, modifier = Modifier.fillMaxWidth()) {
                Text("Forget the Mac's pin")
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

            ClipboardCard(
                status = clipboard.status,
                paused = clipboard.paused,
                hasIncoming = clipboard.hasIncoming,
                incomingBytes = clipboard.incomingBytes,
                connected = clipboard.connected,
                onSend = onSendClipboard,
                onApply = onApplyClipboard,
                onDismiss = onDismissClipboard,
                onPausedChange = onPauseClipboard,
            )

            MirroringCard(
                state = mirroring,
                onStart = onStartMirroring,
                onStop = onStopMirroring,
                onAudioEnabledChange = onAudioEnabledChange,
            )

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
    // The old copy said "when the Mac is back", which blamed the peer for a cause measured on the
    // phone: with nothing holding the session, the display going off kills it and the radio then
    // eats every retry (ADR-061). Name what actually happened instead of sending the user to look
    // at a Mac that never left.
    Pairing.Failure.GAVE_UP -> "the connection dropped and retrying stopped; tap Try again - a screen left off is usually what ends it"
}

/**
 * The Phase 4 surface.
 *
 * Start goes through the system capture dialog every time: Android grants one capture per consent, and
 * a remembered grant would be a permission the user never re-approved.
 */
@Composable
private fun MirroringCard(
    state: MirroringUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onAudioEnabledChange: (Boolean) -> Unit,
) {
    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                    "Screen",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() },
                )
            Text(state.state, style = MaterialTheme.typography.bodySmall)
            if (!state.capturing && state.connected) {
                // Permission explanation: the notification permission is requested as part of the
                // start flow; say why before the system dialog appears.
                Text(
                    "Starting a share asks Android to post a notification. That notification " +
                        "carries the Stop control, so the share can be ended from anywhere.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // Audio is a separate, explicit choice, and its copy states the boundary the platform
            // enforces: media from apps that allow capture, never call audio (ADR-045, measured).
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Switch(
                    checked = state.audioEnabled,
                    onCheckedChange = onAudioEnabledChange,
                    enabled = !state.capturing,
                )
                Column(Modifier.weight(1f)) {
                    Text("Send playback audio", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Hear the phone's music and video on the Mac. Calls and notifications are " +
                            "never captured.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            if (state.capturing) {
                Text(
                    "audio: ${state.audioState} · ${state.audioFrames} frames · rms ${state.audioRms}" +
                        if (state.audioFrames > 0 && state.audioRms < 32) " (silence)" else "",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = onStart,
                    enabled = state.connected && !state.capturing,
                    modifier = Modifier.weight(1f),
                ) { Text("Share this screen") }
                OutlinedButton(onClick = onStop, enabled = state.capturing, modifier = Modifier.weight(1f)) {
                    Text("Stop sharing")
                }
            }

            Text(
                "frames ${state.framesSent} · ${state.bytesSent / 1024} KiB · " +
                    "keyframe requests ${state.keyframeRequests} · worst send ${state.maxSendMs} ms",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
