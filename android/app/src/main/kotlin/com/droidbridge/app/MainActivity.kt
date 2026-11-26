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

    private val controller: SessionController
        get() = (application as DroidBridgeApplication).controller
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

        // The transport binding and the mirror-frame hook live on the application, so they survive a
        // configuration change; re-registering them here would silently drop a running capture's route.

        lifecycleScope.launch {
            kotlinx.coroutines.delay(2_000)
            while (true) {
                // Tick unconditionally: gating this on `isRunning` meant the final "stopped" state
                // never rendered, so after a share ended the dashboard kept showing "mirroring …"
                // with "Stop sharing" still enabled. One recomposition a second of a small card is
                // cheaper than a dashboard that disagrees with the service.
                mirroringTick.value += 1
                kotlinx.coroutines.delay(1_000)
            }
        }

        projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == android.app.Activity.RESULT_OK && data != null) {
                MirroringService.start(
                    this,
                    result.resultCode,
                    data,
                    withAudio = audioEnabledState.value,
                )
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
            // The audio switch is a composition-level state, so it must be a remember key: read
            // inside the block it would only be sampled once and the switch would look stuck.
            val mirroringState = remember(mirroringRevision, state.phase, audioEnabledState.value) {
                val stats = MirroringService.stats
                MirroringUiState(
                    state = stats.state.get(),
                    capturing = MirroringService.isCapturing(),
                    framesSent = stats.framesSent.get(),
                    bytesSent = stats.bytesSent.get(),
                    keyframeRequests = stats.keyframeRequests.get(),
                    maxSendMs = stats.maxSendMs.get(),
                    connected = state.phase == com.droidbridge.core.pairing.Pairing.Phase.CONNECTED,
                    audioEnabled = audioEnabledState.value,
                    audioState = stats.audioState.get(),
                    audioFrames = stats.audioFrames.get(),
                    audioRms = stats.audioRms.get(),
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
                liveSessionCode = controller.liveSessionCode,
                onStartDiscovery = { controller.startDiscovery() },
                onConnect = { controller.connect(it) },
                onConfirm = { controller.confirmPairing() },
                onReject = { controller.rejectPairing() },
                onDrop = { controller.dropForTest() },
                onStop = { controller.stop() },
                onForgetPairing = { controller.forgetPinnedPeers() },
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
                onAudioEnabledChange = { audioEnabledState.value = it },
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

    /**
     * The capture service's ongoing notification is the only stop affordance that exists outside this
     * activity. It was declared in the manifest but never requested, so on a fresh install the service
     * ran with `importance=NONE` and the notification — including its Stop action — was invisible to the
     * user. Denial does not block capture; it only removes that affordance, so this never gates the run.
     */
    private val notificationLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        android.util.Log.i("P03Shell", "POST_NOTIFICATIONS granted=$granted")
        // Chained, not fired together: launching both dialogs in one frame makes the system drop one,
        // and the capture consent is the one that must never be lost.
        if (pendingMirroringRequest) {
            pendingMirroringRequest = false
            continueMirroringRequest()
        }
    }

    /**
     * Microphone consent for playback capture, asked once per share and never at launch.
     *
     * A denial is not an error state: the switch turns itself off and the share continues with the
     * screen only, because a user who said no to audio still asked for a mirror.
     */
    private val audioPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        android.util.Log.i("P03Shell", "RECORD_AUDIO granted=$granted")
        if (!granted) audioEnabledState.value = false
        if (pendingRecordAudioRequest) {
            pendingRecordAudioRequest = false
            continueMirroringRequest()
        }
    }

    /** Set while a capture consent is waiting behind the notification prompt. */
    private var pendingMirroringRequest = false

    /**
     * The user's audio choice, read when the share starts.
     *
     * It lives on the activity rather than in the composition because the consent result arrives
     * outside recomposition; the switch writes to it, and the service call reads it.
     */
    private val audioEnabledState = androidx.compose.runtime.mutableStateOf(true)

    /** Set while a capture consent is waiting behind the microphone prompt. */
    private var pendingRecordAudioRequest = false

    private fun describeDevice(): String {
        val info = BuildInfo.describe()
        android.util.Log.i(
            "P03Shell",
            "device ${info.manufacturer} ${info.model} android=${info.androidRelease} sdk=${info.sdkInt} build=${info.buildId}",
        )
        return "${info.manufacturer} ${info.model} · Android ${info.androidRelease} (API ${info.sdkInt})"
    }

    /**
     * Shows the system capture-consent dialog; the service only starts if the user allows it.
     *
     * Same inlined-constant reasoning as [needsNotificationPermission]: both uses of the API 33 permission
     * constant live behind a version guard, which lint's InlinedApi check cannot follow.
     */
    @android.annotation.SuppressLint("InlinedApi")
    private fun requestMirroring() {
        continueMirroringRequest()
    }

    /**
     * The permission chain, resumed by each launcher's callback.
     *
     * Sequential rather than concurrent: two system dialogs launched in one frame make Android drop
     * one of them, and the capture consent is the one that must never be lost. The order is
     * notifications (affordance only), microphone (audio track only), then capture consent.
     */
    @android.annotation.SuppressLint("InlinedApi")
    private fun continueMirroringRequest() {
        if (needsNotificationPermission()) {
            pendingMirroringRequest = true
            notificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        if (audioEnabledState.value && needsRecordAudioPermission()) {
            pendingRecordAudioRequest = true
            audioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
            return
        }
        launchProjectionConsent()
    }

    private fun needsRecordAudioPermission(): Boolean =
        checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /**
     * Asked exactly when capture is about to start, never at launch.
     *
     * The permission constant is inlined by the compiler, so referencing it below API 33 is safe; lint's
     * InlinedApi check cannot see that the only use is behind the version guard.
     */
    @android.annotation.SuppressLint("InlinedApi")
    private fun needsNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun launchProjectionConsent() {
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
        // The session is owned by the process, so an activity going away must NOT close it: doing that
        // killed the stream on every rotation. Disconnect is an explicit user action.
        super.onDestroy()
    }
}
