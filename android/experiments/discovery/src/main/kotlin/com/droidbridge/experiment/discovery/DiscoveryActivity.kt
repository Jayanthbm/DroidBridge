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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

/**
 * P02 experiment UI.
 *
 * Every button is one measurement, and the log panel is the evidence. The permission state is shown
 * at the top because it is the precondition for everything below it: without
 * `ACCESS_LOCAL_NETWORK` the picker cannot appear, and a report that does not state the permission
 * state is not interpretable.
 */
class DiscoveryActivity : ComponentActivity() {

    private lateinit var probe: DiscoveryProbe

    private var permissionState by mutableStateOf("unknown")
    private var logText by mutableStateOf("")
    private var resolveText by mutableStateOf("")

    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            permissionState = if (granted) "GRANTED" else "DENIED"
            refresh()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val nsdManager = getSystemService(NsdManager::class.java)
        probe = DiscoveryProbe(nsdManager)

        permissionState = currentPermissionState()

        setContent {
            MaterialTheme {
                DiscoveryScreen(
                    sdkInt = Build.VERSION.SDK_INT,
                    permissionState = permissionState,
                    logText = logText,
                    resolveText = resolveText,
                    onRequestPermission = { requestPermission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK) },
                    onStartPicker = {
                        probe.startWithPicker()
                        refresh()
                    },
                    onStartApprovedOnly = {
                        probe.startWithUserApprovedOnly()
                        refresh()
                    },
                    onResolve = {
                        probe.resolveLast { result ->
                            resolveText = result
                            refresh()
                        }
                    },
                    onStop = {
                        probe.stop()
                        refresh()
                    },
                    onRefresh = { refresh() },
                )
            }
        }
    }

    override fun onDestroy() {
        // Discovery must not outlive the screen: a listener left registered holds the NSD service
        // and would make the next run report FAILURE_ALREADY_ACTIVE for the wrong reason.
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
     * Re-reads the permission and the event log.
     *
     * The permission is re-read rather than cached because the user can revoke it in Settings while
     * the experiment is open, and a stale "GRANTED" would make a later failure look like a bug.
     */
    private fun refresh() {
        permissionState = currentPermissionState()
        logText = probe.events().joinToString("\n")
    }
}

@Composable
private fun DiscoveryScreen(
    sdkInt: Int,
    permissionState: String,
    logText: String,
    resolveText: String,
    onRequestPermission: () -> Unit,
    onStartPicker: () -> Unit,
    onStartApprovedOnly: () -> Unit,
    onResolve: () -> Unit,
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
            "Measures the system picker path. Nothing is transmitted.",
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

        OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
            Text("Stop discovery")
        }

        OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
            Text("Refresh log")
        }

        if (resolveText.isNotEmpty()) {
            Text("resolve: $resolveText", style = MaterialTheme.typography.bodySmall)
        }

        Text("Events:", style = MaterialTheme.typography.titleSmall)
        Text(
            if (logText.isEmpty()) "(none yet)" else logText,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
