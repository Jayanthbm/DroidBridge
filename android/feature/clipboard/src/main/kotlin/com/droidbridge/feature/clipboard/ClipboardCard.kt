package com.droidbridge.feature.clipboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The Phase 3 clipboard surface.
 *
 * Sending is a button, not a watcher, because Android only lets the app read the clipboard while it
 * has focus (P04: a backgrounded read returns no primary clip at all). Showing a "sync" switch that
 * implied a background watch would be a lie the user pays for later.
 */
@Composable
fun ClipboardCard(
    status: String,
    paused: Boolean,
    hasIncoming: Boolean,
    incomingBytes: Int,
    connected: Boolean,
    onSend: () -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
    onPausedChange: (Boolean) -> Unit,
) {
    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Clipboard", style = MaterialTheme.typography.titleMedium)
            Text(status, style = MaterialTheme.typography.bodySmall)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = onSend, enabled = connected && !paused, modifier = Modifier.weight(1f)) {
                    Text("Send phone clipboard")
                }
                OutlinedButton(onClick = { onPausedChange(!paused) }, modifier = Modifier.weight(1f)) {
                    Text(if (paused) "Resume sync" else "Pause sync")
                }
            }

            if (hasIncoming) {
                Text(
                    "The Mac sent $incomingBytes bytes. Android can only write the clipboard while " +
                        "this app is on screen.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = onApply, modifier = Modifier.weight(1f)) { Text("Put it on the clipboard") }
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Dismiss") }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Sync", style = MaterialTheme.typography.bodySmall)
                Switch(
                    checked = !paused,
                    onCheckedChange = { onPausedChange(!it) },
                    // A bare switch next to a label is two focus targets for TalkBack; naming the
                    // control itself makes the row one understandable stop.
                    modifier = Modifier.semantics { contentDescription = "Sync clipboard" },
                )
            }
        }
    }
}
