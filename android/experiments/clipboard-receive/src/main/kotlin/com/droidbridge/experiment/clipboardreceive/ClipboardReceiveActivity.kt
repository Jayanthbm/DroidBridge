package com.droidbridge.experiment.clipboardreceive

import android.Manifest
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.timer

/**
 * P05 experiment UI.
 *
 * The screen shows only lengths and hash prefixes, never clipboard text. The Mac sender is
 * `scripts/p05-clipboard-sender.py`; the host/port can be preset with intent extras, which is how
 * the scripted device runs avoid typing on the phone:
 *
 *   adb shell am start -n com.droidbridge.experiment.clipboardreceive/.ClipboardReceiveActivity \
 *       --es host 192.168.31.109 --ei port 47900
 */
class ClipboardReceiveActivity : ComponentActivity() {

    private var host by mutableStateOf("192.168.31.109")
    private var portText by mutableStateOf("47900")
    private var statusText by mutableStateOf("idle")
    private var readbackText by mutableStateOf("not read yet")
    private var logText by mutableStateOf("")

    private val requesting = AtomicBoolean(false)

    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            statusText = "notification permission granted=$granted"
            requesting.set(false)
            // A denied FGS notification must not block the experiment: the service still runs and
            // the notification is simply hidden. Skip the permission check this time so the
            // callback cannot loop back into another request.
            startReceiver(skipPermissionCheck = true)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        host = intent.getStringExtra("host") ?: host
        portText = intent.getIntExtra("port", 47900).toString()

        setContent {
            MaterialTheme {
                Scaffold { inner ->
                    var tick by remember { mutableIntStateOf(0) }
                    DisposableEffect(Unit) {
                        val poller = timer(period = 500) {
                            runOnUiThread {
                                logText = ClipboardReceiverService.events.takeLast(12)
                                    .joinToString("\n")
                                tick++
                            }
                        }
                        onDispose { poller.cancel() }
                    }
                    ClipboardReceiveScreen(
                        modifier = Modifier.padding(inner),
                        host = host,
                        portText = portText,
                        status = statusText,
                        readback = readbackText,
                        log = logText,
                        tick = tick,
                        onHost = { host = it },
                        onPort = { portText = it },
                        onStart = ::startReceiver,
                        onStop = {
                            ClipboardReceiverService.stop(this)
                            statusText = "stop requested"
                        },
                        onReadBack = ::readBack,
                        onClear = ::clearClipboard,
                    )
                }
            }
        }
    }

    private fun startReceiver(skipPermissionCheck: Boolean = false) {
        val port = portText.trim().toIntOrNull()
        if (host.isBlank() || port == null || port !in 1..65535) {
            statusText = "invalid host/port"
            return
        }
        if (!skipPermissionCheck &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requesting.set(true)
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        ClipboardReceiverService.start(this, host.trim(), port)
        statusText = "receiver start requested"
    }

    /**
     * Reads the clipboard from the focused activity and compares it with what the service last
     * wrote. This is the part that proves a **background** write actually landed: the service
     * cannot read the clipboard itself, but the focused activity can.
     */
    private fun readBack() {
        val manager = getSystemService(ClipboardManager::class.java)
        val hasClip = manager?.hasPrimaryClip() == true
        val clip = if (hasClip) manager?.primaryClip else null
        val text = if (clip != null && clip.itemCount > 0) {
            clip.getItemAt(0).coerceToText(this)?.toString()
        } else {
            null
        }
        val hash = text?.let { ClipboardPayload.sha256Hex(it) } ?: ""
        val matches = hash.isNotEmpty() && hash == ClipboardReceiverService.lastHash.get()
        val line =
            "READBACK hasClip=$hasClip len=${text?.length ?: 0} " +
                "hash=${if (hash.isEmpty()) "-" else ClipboardPayload.shortHash(hash)} " +
                "matchesLastDelivered=$matches"
        Log.i("P05Clipboard", line)
        readbackText = line
    }

    private fun clearClipboard() {
        val manager = getSystemService(ClipboardManager::class.java) ?: return
        manager.clearPrimaryClip()
        Log.i("P05Clipboard", "CLEARED")
        readbackText = "clipboard cleared"
    }
}

@Composable
private fun ClipboardReceiveScreen(
    modifier: Modifier,
    host: String,
    portText: String,
    status: String,
    readback: String,
    log: String,
    tick: Int,
    onHost: (String) -> Unit,
    onPort: (String) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onReadBack: () -> Unit,
    onClear: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("P05 — clipboard Mac → Android", style = MaterialTheme.typography.titleLarge)
        Text(
            "Receives framed text from the Mac. The UI exposes lengths and hashes only.",
            style = MaterialTheme.typography.bodySmall,
        )

        OutlinedTextField(
            value = host,
            onValueChange = onHost,
            label = { Text("Mac host") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = portText,
            onValueChange = onPort,
            label = { Text("Port") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onStart) { Text("Start receiver") }
            OutlinedButton(onClick = onStop) { Text("Stop") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onReadBack) { Text("Read back clipboard") }
            OutlinedButton(onClick = onClear) { Text("Clear clipboard") }
        }

        Text("status: $status", style = MaterialTheme.typography.bodySmall)
        Text(
            "state=${ClipboardReceiverService.state.get()} " +
                "received=${ClipboardReceiverService.received.get()} " +
                "written=${ClipboardReceiverService.written.get()} " +
                "duplicates=${ClipboardReceiverService.duplicates.get()} " +
                "rejected=${ClipboardReceiverService.rejected.get()} " +
                "lastLen=${ClipboardReceiverService.lastLength.get()} " +
                "lastHash=${ClipboardReceiverService.lastHash.get().take(12).ifEmpty { "-" }}",
            style = MaterialTheme.typography.bodySmall,
        )
        Text("readback: $readback", style = MaterialTheme.typography.bodySmall)
        Text("events:", style = MaterialTheme.typography.titleSmall)
        Text(log.ifEmpty { "(none yet)" }, style = MaterialTheme.typography.bodySmall)
        if (tick < 0) Text("tick=$tick")
    }
}
