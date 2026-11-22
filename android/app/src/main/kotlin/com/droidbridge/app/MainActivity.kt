package com.droidbridge.app

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

        // Discovery starts immediately on every platform. API 37 uses the system picker, which is
        // the scoped consent path and needs no broad permission (ADR-010) — measured on the S24 with
        // ACCESS_LOCAL_NETWORK explicitly denied: the picker still resolved the Mac and the session
        // connected. So the broad permission is never requested unless the picker path itself fails,
        // and then only because the user asks for it.
        controller.startDiscovery()

        setContent {
            val state by controller.state.collectAsState()
            val peers by controller.peers.collectAsState()
            val discoveryStatus by controller.discoveryStatus.collectAsState()
            val needsPermission = discoveryStatus is ServiceDiscovery.Status.NeedsPermission
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
                discovery = DiscoveryUiState(status = discoveryStatus, needsPermission = needsPermission),
                onGrantLocalNetwork = {
                    if (Build.VERSION.SDK_INT >= 37) launcher.launch(ServiceDiscovery.PERMISSION_LOCAL_NETWORK)
                },
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
