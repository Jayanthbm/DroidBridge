package com.droidbridge.experiment.discovery

import android.Manifest
import android.content.pm.PackageManager
import android.net.nsd.NsdManager
import android.os.Build
import android.os.Bundle
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
import java.util.Collections
import kotlin.concurrent.timer

/**
 * P02 experiment UI.
 *
 * Every button is one measurement, and the log panel is the evidence. The permission state is shown
 * at the top because it is the precondition for everything below it: without
 * `ACCESS_LOCAL_NETWORK` the picker may not appear, and a report that does not state the permission
 * state is not interpretable.
 *
 * The sustained-echo controls exist because P02's remaining questions are about recovery: a
 * 60-second run proves stability, and a 180-second run with Wi-Fi toggled in the middle proves
 * whether approved-only re-discovery reconnects without a picker.
 */
class DiscoveryActivity : ComponentActivity() {

    private lateinit var probe: DiscoveryProbe
    private lateinit var echo: EchoClient
    private lateinit var inbound: InboundProbe

    private var permissionState by mutableStateOf("unknown")
    private var logText by mutableStateOf("")
    private var echoText by mutableStateOf("")
    private var inboundText by mutableStateOf("")
    private var resolveText by mutableStateOf("")

    private val echoLines = Collections.synchronizedList(mutableListOf<String>())
    private val inboundLines = Collections.synchronizedList(mutableListOf<String>())

    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            permissionState = if (granted) "GRANTED" else "DENIED"
            refresh()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val nsdManager = getSystemService(NsdManager::class.java)
        probe = DiscoveryProbe(nsdManager)
        echo = EchoClient(probe) { line ->
            echoLines.add(line)
            android.util.Log.i("P02Echo", line)
        }
        inbound = InboundProbe(nsdManager) { line ->
            inboundLines.add(line)
            android.util.Log.i("P02Inbound", line)
        }

        permissionState = currentPermissionState()

        setContent {
            androidx.compose.material3.MaterialTheme {
                var tick by remember { mutableIntStateOf(0) }
                DisposableEffect(Unit) {
                    // Refresh, not just tick: the permission can be granted or revoked while the
                    // screen is open, and a stale banner would mislabel the next run. Dispose on
                    // activity destruction so the timer cannot keep a dead screen reachable.
                    val poller = timer(period = 500) { runOnUiThread { refresh(); tick++ } }
                    onDispose { poller.cancel() }
                }
                DiscoveryScreen(
                    sdkInt = Build.VERSION.SDK_INT,
                    permissionState = permissionState,
                    logText = logText,
                    echoText = echoText,
                    inboundText = inboundText,
                    resolveText = resolveText,
                    tick = tick,
                    echoRunning = echo.isRunning,
                    onRequestPermission = {
                        requestPermission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
                    },
                    onStartPicker = { probe.startWithPicker(); refresh() },
                    onStartApprovedOnly = { probe.startWithUserApprovedOnly(); refresh() },
                    onResolve = {
                        probe.resolveLast { result ->
                            runOnUiThread {
                                resolveText = result
                                refresh()
                            }
                        }
                    },
                    onCheckPermission = {
                        probe.checkPermissionForService("DroidBridge") { result ->
                            runOnUiThread {
                                resolveText = result
                                refresh()
                            }
                        }
                    },
                    onStartEcho60 = { startEcho(60) },
                    onStartEcho180 = { startEcho(180) },
                    onStopEcho = { echo.stop(); refresh() },
                    onStartInbound = { inbound.start(); refresh() },
                    onAdvertise = { inbound.advertise(); refresh() },
                    onStopInbound = { inbound.stop(); refresh() },
                    onStop = { probe.stop(); refresh() },
                    onRefresh = { refresh() },
                )
            }
        }
    }

    private fun startEcho(seconds: Int) {
        echoLines.clear()
        echo.start(seconds, probe.resolvedEndpoint())
        refresh()
    }

    override fun onDestroy() {
        // Discovery must not outlive the screen: a listener left registered holds the NSD service
        // and would make the next run report FAILURE_ALREADY_ACTIVE for the wrong reason. The echo
        // and inbound probes are stopped for the same reason, and because a background socket in
        // an experiment app is a resource leak with no upside.
        echo.stop()
        inbound.stop()
        probe.stop()
        super.onDestroy()
    }

    private fun currentPermissionState(): String {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_LOCAL_NETWORK,
        ) == PackageManager.PERMISSION_GRANTED
        return if (granted) "GRANTED" else "NOT GRANTED"
    }

    /**
     * Re-reads the permission and the event logs.
     *
     * The permission is re-read rather than cached because the user can revoke it in Settings while
     * the experiment is open, and a stale "GRANTED" would make a later failure look like a bug.
     */
    private fun refresh() {
        permissionState = currentPermissionState()
        logText = probe.events().joinToString("\n")
        echoText = buildEchoText()
        inboundText = buildInboundText()
    }

    private fun buildEchoText(): String {
        val recent = synchronized(echoLines) { echoLines.takeLast(8) }
        val header =
            "running=${echo.isRunning} sent=${echo.sent.get()} received=${echo.received.get()} " +
                "mismatches=${echo.mismatches.get()} errors=${echo.errors.get()} " +
                "connectFailures=${echo.connectFailures.get()} connects=${echo.connectCount.get()} " +
                "rttLastUs=${echo.rttLastUs.get()} " +
                "rttMinUs=${if (echo.received.get() > 0) echo.rttMinUs.get() else 0} " +
                "rttMaxUs=${echo.rttMaxUs.get()}"
        return (listOf(header) + recent).joinToString("\n")
    }

    private fun buildInboundText(): String {
        val recent = synchronized(inboundLines) { inboundLines.takeLast(6) }
        val header =
            "listening=${inbound.isListening.get()} port=${inbound.port.get()} " +
                "advertising=${inbound.isAdvertising.get()} connections=${inbound.connections.get()} " +
                "bytesReceived=${inbound.bytesReceived.get()}"
        return (listOf(header) + recent).joinToString("\n")
    }
}

@Composable
private fun DiscoveryScreen(
    sdkInt: Int,
    permissionState: String,
    logText: String,
    echoText: String,
    inboundText: String,
    resolveText: String,
    tick: Int,
    echoRunning: Boolean,
    onRequestPermission: () -> Unit,
    onStartPicker: () -> Unit,
    onStartApprovedOnly: () -> Unit,
    onResolve: () -> Unit,
    onCheckPermission: () -> Unit,
    onStartEcho60: () -> Unit,
    onStartEcho180: () -> Unit,
    onStopEcho: () -> Unit,
    onStartInbound: () -> Unit,
    onAdvertise: () -> Unit,
    onStopInbound: () -> Unit,
    onStop: () -> Unit,
    onRefresh: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("P02 — LAN discovery (API 37)", style = MaterialTheme.typography.titleLarge)
        Text(
            "Picker discovery, duplex echo, inbound test. Nothing leaves the local network.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text("SDK_INT: $sdkInt")
        Text("ACCESS_LOCAL_NETWORK: $permissionState")

        Button(onClick = onRequestPermission, modifier = Modifier.fillMaxWidth()) {
            Text("Request local-network permission")
        }

        Button(onClick = onStartPicker, modifier = Modifier.fillMaxWidth()) {
            Text("Discover with picker")
        }

        Button(onClick = onStartApprovedOnly, modifier = Modifier.fillMaxWidth()) {
            Text("Discover approved-only")
        }

        OutlinedButton(onClick = onResolve, modifier = Modifier.fillMaxWidth()) {
            Text("Resolve last found")
        }

        OutlinedButton(onClick = onCheckPermission, modifier = Modifier.fillMaxWidth()) {
            Text("Check permission for service")
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onStartEcho60,
                enabled = !echoRunning,
                modifier = Modifier.weight(1f),
            ) { Text("Echo 60s") }
            Button(
                onClick = onStartEcho180,
                enabled = !echoRunning,
                modifier = Modifier.weight(1f),
            ) { Text("Echo 180s (recovery)") }
        }
        OutlinedButton(
            onClick = onStopEcho,
            enabled = echoRunning,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Stop echo") }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onStartInbound, modifier = Modifier.weight(1f)) {
                Text("Inbound listen")
            }
            OutlinedButton(onClick = onAdvertise, modifier = Modifier.weight(1f)) {
                Text("Advertise")
            }
        }
        OutlinedButton(onClick = onStopInbound, modifier = Modifier.fillMaxWidth()) {
            Text("Stop inbound")
        }

        OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
            Text("Stop discovery")
        }
        OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
            Text("Refresh log")
        }

        if (resolveText.isNotEmpty()) {
            Text("resolve: $resolveText", style = MaterialTheme.typography.bodySmall)
        }

        Text("Echo:", style = MaterialTheme.typography.titleSmall)
        Text(echoText, style = MaterialTheme.typography.bodySmall)
        Text("Inbound:", style = MaterialTheme.typography.titleSmall)
        Text(inboundText, style = MaterialTheme.typography.bodySmall)

        Text("Events:", style = MaterialTheme.typography.titleSmall)
        Text(
            if (logText.isEmpty()) "(none yet)" else logText,
            style = MaterialTheme.typography.bodySmall,
        )
        // Reading the tick is what recomposes this screen while the probes run.
        if (tick < 0) Text("tick=$tick")
    }
}
