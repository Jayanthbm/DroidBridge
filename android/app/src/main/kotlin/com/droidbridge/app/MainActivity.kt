package com.droidbridge.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import com.droidbridge.core.common.BuildInfo
import com.droidbridge.core.network.SecureSession
import com.droidbridge.core.network.ServiceDiscovery
import com.droidbridge.core.network.SessionController
import com.droidbridge.core.security.DeviceIdentity
import com.droidbridge.core.security.KeystorePinStore
import com.droidbridge.feature.dashboard.DashboardScreen
import com.droidbridge.feature.dashboard.DiscoveryUiState
import kotlinx.coroutines.launch

/**
 * The Phase 2 shell: identity, discovery, pairing and the live session, wired to the dashboard.
 *
 * The activity owns nothing but lifetimes. Whether a peer is trusted, when data may move and when a
 * reconnect is due all live in [SessionController] and the pure pairing state machine it drives, so
 * a rotated or recreated activity cannot change those answers — it only re-subscribes to them.
 */
class MainActivity : ComponentActivity() {

    private lateinit var controller: SessionController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val identity = DeviceIdentity.loadOrCreate()
        val pinStore = KeystorePinStore(this)
        controller = SessionController(
            identity = identity,
            pinStore = pinStore,
            discovery = ServiceDiscovery(this),
            scope = lifecycleScope,
        )

        // API 37 gates direct LAN discovery behind ACCESS_LOCAL_NETWORK; the system picker does not
        // need it (ADR-010). The request is made in context, on the first discovery attempt, and a
        // refusal is shown as its own state rather than as "no Mac found".
        if (needsLocalNetworkPermission()) {
            launcher.launch(ServiceDiscovery.PERMISSION_LOCAL_NETWORK)
        } else {
            controller.startDiscovery()
        }

        setContent {
            val state by controller.state.collectAsState()
            val peers by controller.peers.collectAsState()
            val discoveryStatus by controller.discoveryStatus.collectAsState()
            DashboardScreen(
                // The dashboard shows a one-line environment baseline; the full record is logged.
                buildInfo = describeDevice(),
                state = state,
                peers = peers,
                roundTrips = controller.roundTrips,
                lastRttMs = controller.lastRttMs,
                onStartDiscovery = { controller.startDiscovery() },
                onConnect = { controller.connect(it) },
                onConfirm = { controller.confirmPairing() },
                onReject = { controller.rejectPairing() },
                onDrop = { controller.dropForTest() },
                onStop = { controller.stop() },
                discovery = DiscoveryUiState(
                    status = discoveryStatus,
                    needsPermission = discoveryStatus is ServiceDiscovery.Status.NeedsPermission,
                ),
            )
        }
    }

    /** The runtime permission request, resolved once and never re-prompted without a user action. */
    private val launcher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        android.util.Log.i("P03Shell", "ACCESS_LOCAL_NETWORK granted=$granted")
        controller.startDiscovery()
    }

    private fun needsLocalNetworkPermission(): Boolean {
        if (Build.VERSION.SDK_INT < 37) return false
        return checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED
    }

    private fun describeDevice(): String {
        val info = BuildInfo.describe()
        android.util.Log.i(
            "P03Shell",
            "device ${info.manufacturer} ${info.model} android=${info.androidRelease} sdk=${info.sdkInt} build=${info.buildId}",
        )
        return "${info.manufacturer} ${info.model} · Android ${info.androidRelease} (API ${info.sdkInt})"
    }

    override fun onDestroy() {
        controller.stop()
        super.onDestroy()
    }
}
