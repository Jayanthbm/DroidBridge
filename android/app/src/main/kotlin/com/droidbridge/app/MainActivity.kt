package com.droidbridge.app

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.lifecycleScope
import com.droidbridge.core.common.BuildInfo
import com.droidbridge.core.network.SecureSession
import com.droidbridge.core.network.ServiceDiscovery
import com.droidbridge.core.network.SessionController
import com.droidbridge.core.security.DeviceIdentity
import com.droidbridge.core.security.KeystorePinStore
import com.droidbridge.feature.dashboard.DashboardScreen
import androidx.activity.result.contract.ActivityResultContracts
import android.media.projection.MediaProjectionManager
import com.droidbridge.feature.clipboard.ClipboardBridge
import com.droidbridge.feature.mirroring.MirrorTransportHolder
import com.droidbridge.feature.mirroring.MirroringService
import com.droidbridge.feature.dashboard.ClipboardUiState
import com.droidbridge.feature.dashboard.DiscoveryUiState
import com.droidbridge.feature.dashboard.MirroringUiState
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
    private lateinit var clipboard: ClipboardBridge

    /** True while this activity is the resumed one, i.e. while Android will let us touch the clipboard. */
    @Volatile private var isForeground = false

    /** Changes whenever clipboard-visible state changes, so Compose recomposes without polling. */
    private val clipboardTick = kotlinx.coroutines.flow.MutableStateFlow(0)

    /** Drives the mirroring counters once a second while a capture is running. */
    private val mirroringTick = kotlinx.coroutines.flow.MutableStateFlow(0)

    /**
     * Screen capture needs the system's own consent dialog, and the resulting grant is an `Intent` that
     * is only valid for one capture. It is launched from a button, never at startup.
     */
    private lateinit var projectionLauncher: androidx.activity.result.ActivityResultLauncher<android.content.Intent>

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

        // The clipboard bridge must exist before the composition reads its state, or the first frame
        // throws UninitializedPropertyAccessException.
        clipboard = ClipboardBridge(
            context = this,
            ownOriginId = DeviceIdentity.pinOf(identity.certificate),
            send = { type, payload -> controller.sendControl(type, payload) },
        )
        controller.onClipboardMessage = { type, payload ->
            clipboard.onMessage(type, payload, isForeground)
            // The card renders bridge state, which changes off the UI thread; nudging the revision is
            // what makes an arriving offer visible without polling.
            clipboardTick.value += 1
        }
        controller.onStateChanged = { clipboardTick.value += 1 }

        // The capture service writes through this adapter, which forwards into the one session.
        MirrorTransportHolder.transport = SessionMirrorTransport(controller)
        controller.onMirrorMessage = { type, _ ->
            // The only mirroring frame the Mac sends mid-stream is a keyframe request, after it flushed
            // its decoder. Ignoring it would leave the Mac blank until the next scheduled GOP.
            if (type == com.droidbridge.protocol.session.ControlMessage.Type.MIRROR_KEYFRAME_REQUEST) {
                MirroringService.requestKeyframe()
            }
        }

        lifecycleScope.launch {
            kotlinx.coroutines.delay(2_000)
            while (true) {
                if (MirroringService.isRunning) mirroringTick.value += 1
                kotlinx.coroutines.delay(1_000)
            }
        }

        projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == android.app.Activity.RESULT_OK && data != null) {
                MirroringService.start(this, result.resultCode, data)
            }
        }

        setContent {
            val state by controller.state.collectAsState()
            val peers by controller.peers.collectAsState()
            val discoveryStatus by controller.discoveryStatus.collectAsState()
            // Read the revision so every bridge update recomposes the card.
            val clipboardRevision by clipboardTick.collectAsState()
            // Mirroring counters are atomics in the feature; this recomposes them once a second while
            // capture runs, which is cheaper than exposing four flows for a debug line.
            val mirroringRevision by mirroringTick.collectAsState()
            val mirroringState = remember(mirroringRevision, state.phase) {
                val stats = MirroringService.stats
                MirroringUiState(
                    state = stats.state.get(),
                    capturing = MirroringService.isCapturing(),
                    framesSent = stats.framesSent.get(),
                    bytesSent = stats.bytesSent.get(),
                    keyframeRequests = stats.keyframeRequests.get(),
                    maxSendMs = stats.maxSendMs.get(),
                    connected = state.phase == com.droidbridge.core.pairing.Pairing.Phase.CONNECTED,
                )
            }
            val clipboardState = remember(clipboardRevision, state.phase) {
                ClipboardUiState(
                    status = clipboard.status,
                    paused = clipboard.paused,
                    hasIncoming = clipboard.incoming != null,
                    incomingBytes = clipboard.incoming?.byteLength ?: 0,
                    connected = state.phase == com.droidbridge.core.pairing.Pairing.Phase.CONNECTED,
                )
            }
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
                clipboard = clipboardState,
                onSendClipboard = {
                    clipboard.sendLocalClipboard()
                    clipboardTick.value += 1
                },
                onApplyClipboard = {
                    // Only reached by a tap, so the app is by definition foreground here; the pending
                    // text is applied through the same rules as an automatic delivery would use.
                    // Only reached by a tap, so the app is foreground here; the body was validated
                    // against its offer when it arrived.
                    clipboard.applyPending(isForeground)
                    clipboardTick.value += 1
                },
                onDismissClipboard = {
                    clipboard.dismissPending()
                    clipboardTick.value += 1
                },
                onPauseClipboard = { paused ->
                    clipboard.setPaused(paused)
                    clipboardTick.value += 1
                },
                mirroring = mirroringState,
                onStartMirroring = { requestMirroring() },
                onStopMirroring = {
                    MirroringService.stop(this)
                    clipboardTick.value += 1
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

    /** Shows the system capture-consent dialog; the service only starts if the user allows it. */
    private fun requestMirroring() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    override fun onResume() {
        super.onResume()
        isForeground = true
        clipboardTick.value += 1
    }

    override fun onPause() {
        isForeground = false
        clipboardTick.value += 1
        super.onPause()
    }

    override fun onDestroy() {
        controller.stop()
        super.onDestroy()
    }
}
