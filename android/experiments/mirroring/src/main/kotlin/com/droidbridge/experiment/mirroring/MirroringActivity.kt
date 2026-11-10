package com.droidbridge.experiment.mirroring

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.nsd.DiscoveryRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
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
import com.droidbridge.core.network.SecureSession
import com.droidbridge.core.security.DeviceIdentity
import com.droidbridge.core.security.KeystorePinStore
import com.droidbridge.core.security.PeerPinning
import com.droidbridge.protocol.session.ControlMessage
import com.droidbridge.protocol.session.ShortAuthenticationCode
import java.util.concurrent.Executors

/**
 * P08 — the Android half of end-to-end mirroring.
 *
 * Two steps, because they are two different authorities:
 *
 * 1. **Connect** establishes the authenticated session (P03/P13). An unknown peer needs a code
 *    comparison on both screens; a known peer verifies its stored pin inside the handshake.
 * 2. **Start mirroring** asks the system for screen-capture consent and runs the capture service.
 *    Android issues a single-use consent token per session, so this prompt appears every time —
 *    that is the platform's design, not a gap in the product.
 *
 * The pairing gate is not decoration: only an authenticated session can reach the service, and the
 * service refuses to start without one.
 */
class MirroringActivity : ComponentActivity() {

    private val tag = "P08Mirroring"

    /** Network calls stay off the main thread; the session blocks on connect and writes. */
    private val executor = Executors.newSingleThreadExecutor()
    private val uiHandler = Handler(Looper.getMainLooper())

    private lateinit var nsdManager: NsdManager
    private lateinit var identity: DeviceIdentity
    private lateinit var pinStore: PeerPinning.PinStore

    @Volatile
    private var session: SecureSession? = null

    @Volatile
    private var discovered: NsdServiceInfo? = null

    private var logText by mutableStateOf("")
    private var statusText by mutableStateOf("idle")
    private var codeText by mutableStateOf("")
    private var peerPinText by mutableStateOf("(none)")
    private var storedPinText by mutableStateOf("(none)")
    private var dataStateText by mutableStateOf("no session")
    private var mirrorStateText by mutableStateOf("not mirroring")
    private var statsText by mutableStateOf("")

    private val requestLocalNetwork =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            record("ACCESS_LOCAL_NETWORK granted=$granted")
        }

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            record("POST_NOTIFICATIONS granted=$granted (the session continues either way)")
        }

    private val requestConsent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                val active = session
                if (active == null || !active.isAuthenticated) {
                    record("consent granted but there is no authenticated session; not starting")
                    return@registerForActivityResult
                }
                MirrorSessionHolder.session = active
                record("screen-capture consent granted; starting the capture service")
                MirroringService.start(this, result.resultCode, data)
            } else {
                record("screen-capture consent denied (the system prompt was cancelled)")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        nsdManager = getSystemService(NsdManager::class.java)
        identity = DeviceIdentity.loadOrCreate()
        pinStore = KeystorePinStore(applicationContext)
        storedPinText = pinStore.pinFor(PEER_ID) ?: "(none)"
        record("identity pin=${identity.pin}")
        record("stored peer pin for $PEER_ID: $storedPinText")

        setContent {
            MaterialTheme {
                MirrorScreen(
                    status = statusText,
                    ownPin = identity.pin,
                    storedPin = storedPinText,
                    peerPin = peerPinText,
                    code = codeText,
                    dataState = dataStateText,
                    mirrorState = mirrorStateText,
                    stats = statsText,
                    log = logText,
                    onRequestPermission = {
                        requestLocalNetwork.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
                    },
                    onDiscover = ::discover,
                    onConnect = ::connect,
                    onConfirmPairing = ::confirmPairing,
                    onRejectPairing = ::rejectPairing,
                    onStartMirroring = ::startMirroring,
                    onStopMirroring = { MirroringService.stop(this) },
                    onCloseSession = ::closeSession,
                    onForgetPin = ::forgetPin,
                )
            }
        }

        uiHandler.post(refreshRunnable)
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(refreshRunnable)
        // While mirroring, the service owns the session; closing it here would kill the stream the
        // user asked for. Otherwise the session has no owner left and is closed.
        if (!MirroringService.isRunning) {
            closeSession()
        }
        super.onDestroy()
    }

    // ---------------------------------------------------------------------------------------
    // Discovery
    // ---------------------------------------------------------------------------------------

    private fun discover() {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_LOCAL_NETWORK,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            record("discover refused: ACCESS_LOCAL_NETWORK not granted")
            return
        }

        val request = DiscoveryRequest.Builder(SERVICE_TYPE)
            .setFlags(DiscoveryRequest.FLAG_SHOW_PICKER)
            .build()

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                record("discovery started: $serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                record("found: '${serviceInfo.serviceName}'")
                nsdManager.stopServiceDiscovery(this)
                resolve(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                record("lost: '${serviceInfo.serviceName}'")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                record("discovery stopped")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                record("start failed: errorCode=$errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                record("stop failed: errorCode=$errorCode")
            }
        }

        nsdManager.discoverServices(request, executor, listener)
        record("discoverServices(picker) called")
    }

    private fun resolve(serviceInfo: NsdServiceInfo) {
        nsdManager.resolveService(
            serviceInfo,
            executor,
            object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    record("resolve failed: errorCode=$errorCode")
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    discovered = info
                    record("resolved: ${info.host?.hostAddress}:${info.port}")
                }
            },
        )
    }

    // ---------------------------------------------------------------------------------------
    // Session
    // ---------------------------------------------------------------------------------------

    /**
     * Connects, choosing the trust path from what is stored.
     *
     * A stored pin means "verify it inside the handshake"; no pin means a first pairing whose code
     * the user must compare. There is no third path, so a changed Mac identity cannot be accepted by
     * picking a different button.
     */
    private fun connect() {
        val target = discovered
        if (target?.host == null) {
            record("connect refused: no resolved peer, run Discover first")
            return
        }

        val stored = pinStore.pinFor(PEER_ID)
        val host = target.host!!.hostAddress
        val port = target.port
        record("connecting to $host:$port expectedPin=${stored ?: "(none, first pairing)"}")

        executor.execute {
            try {
                val connected = SecureSession.connect(host, port, identity, stored)
                session = connected
                val code = connected.shortAuthenticationCode(identity)
                uiHandler.post {
                    peerPinText = connected.peerPin
                    codeText = ShortAuthenticationCode.formatForDisplay(code)
                    dataStateText = if (connected.isAuthenticated) {
                        "available (authenticated)"
                    } else {
                        "locked (compare the code)"
                    }
                    statusText = if (connected.isAuthenticated) {
                        "connected (stored pin verified)"
                    } else {
                        "connected (awaiting code confirmation)"
                    }
                }
                record(
                    "handshake ok: protocol=TLSv1.3 peerPin=${connected.peerPin} " +
                        "authenticated=${connected.isAuthenticated} code=$code",
                )
            } catch (e: Exception) {
                record("connect FAILED: ${e.javaClass.simpleName}: ${e.message}")
                uiHandler.post { statusText = "connect failed" }
            }
        }
    }

    private fun confirmPairing() {
        val active = session ?: run { record("confirm refused: no session"); return }
        if (active.isAuthenticated) {
            record("confirm ignored: session is already authenticated")
            return
        }

        executor.execute {
            try {
                if (pinStore.pinFor(PEER_ID) != null) {
                    record("confirm refused: a pin appeared while pairing; not overwriting it")
                    return@execute
                }
                PeerPinning.confirm(PEER_ID, active.peerCertificate, pinStore)
                val persisted = pinStore.pinFor(PEER_ID) == active.peerPin
                if (!persisted) throw IllegalStateException("pin did not persist")
                active.authenticate()
                uiHandler.post {
                    statusText = "authenticated (pairing confirmed)"
                    dataStateText = "available (authenticated)"
                    storedPinText = pinStore.pinFor(PEER_ID) ?: "(none)"
                }
                record("pairing confirmed: peerPin=${active.peerPin} pin persisted=true")
            } catch (e: Exception) {
                record("confirm FAILED: ${e.javaClass.simpleName}: ${e.message}")
                active.close("pairing confirmation failed")
                session = null
                uiHandler.post { dataStateText = "no session" }
            }
        }
    }

    private fun rejectPairing() {
        val active = session ?: run { record("reject ignored: no session"); return }
        if (active.isAuthenticated) {
            record("reject ignored: the pin is already stored; use Forget stored pin")
            return
        }
        active.close("pairing rejected by user")
        session = null
        uiHandler.post {
            codeText = ""
            peerPinText = "(none)"
            statusText = "pairing rejected"
            dataStateText = "no session"
        }
        record("pairing rejected: no pin stored")
    }

    private fun closeSession() {
        val active = session ?: return
        active.close("closed from UI")
        session = null
        MirrorSessionHolder.clear()
        record("session closed")
    }

    private fun forgetPin() {
        pinStore.forget(PEER_ID)
        storedPinText = "(none)"
        record("forgot pin for $PEER_ID")
    }

    // ---------------------------------------------------------------------------------------
    // Mirroring
    // ---------------------------------------------------------------------------------------

    private fun startMirroring() {
        val active = session
        if (active == null || !active.isAuthenticated) {
            record("start refused: connect and authenticate first")
            return
        }
        if (MirroringService.isRunning) {
            record("start ignored: already mirroring")
            return
        }

        // Requested in context, at the moment the notification first matters (R-21), not at launch.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val manager = getSystemService(MediaProjectionManager::class.java)
        record("requesting screen-capture consent")
        requestConsent.launch(manager.createScreenCaptureIntent())
    }

    // ---------------------------------------------------------------------------------------
    // Logging and state
    // ---------------------------------------------------------------------------------------

    private val refreshRunnable = object : Runnable {
        override fun run() {
            val stats = MirroringService.stats
            mirrorStateText = if (MirroringService.isRunning) {
                "mirroring ${stats.encodeWidth.get()}x${stats.encodeHeight.get()}"
            } else {
                stats.lastStopReason.get().let { reason ->
                    if (reason == "none") "not mirroring" else "stopped: $reason"
                }
            }
            statsText = if (stats.startedAtMs.get() == 0L) "" else stats.summaryLine()
            uiHandler.postDelayed(this, 1_000)
        }
    }

    private fun record(line: String) {
        Log.i(tag, line)
        uiHandler.post {
            val next = if (logText.isEmpty()) line else "$logText\n$line"
            logText = if (next.length > 6_000) next.takeLast(6_000) else next
        }
    }

    companion object {
        private const val SERVICE_TYPE = "_droidbridge-dev._tcp"

        /**
         * Pin key for the Mac. Must be the Bonjour service name the Mac advertises, because that is
         * what the user selected and what they will recognise when an identity changes.
         */
        private const val PEER_ID = "DroidBridge-P08-Mac"
    }
}

@Composable
private fun MirrorScreen(
    status: String,
    ownPin: String,
    storedPin: String,
    peerPin: String,
    code: String,
    dataState: String,
    mirrorState: String,
    stats: String,
    log: String,
    onRequestPermission: () -> Unit,
    onDiscover: () -> Unit,
    onConnect: () -> Unit,
    onConfirmPairing: () -> Unit,
    onRejectPairing: () -> Unit,
    onStartMirroring: () -> Unit,
    onStopMirroring: () -> Unit,
    onCloseSession: () -> Unit,
    onForgetPin: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("P08 — screen mirroring", style = MaterialTheme.typography.titleLarge)
        Text(
            "View-only. The phone stays in control; there is no remote input and no audio.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text("status: $status")
        Text("own pin: $ownPin", style = MaterialTheme.typography.bodySmall)
        Text("stored peer pin: $storedPin", style = MaterialTheme.typography.bodySmall)
        Text("peer pin: $peerPin", style = MaterialTheme.typography.bodySmall)
        Text("application data: $dataState", style = MaterialTheme.typography.bodyMedium)
        Text("mirroring: $mirrorState", style = MaterialTheme.typography.bodyMedium)
        if (code.isNotEmpty()) {
            Text("code: $code", style = MaterialTheme.typography.headlineSmall)
        }
        if (stats.isNotEmpty()) {
            Text(stats, style = MaterialTheme.typography.bodySmall)
        }

        Button(onClick = onRequestPermission, modifier = Modifier.fillMaxWidth()) {
            Text("Request local-network permission")
        }
        Button(onClick = onDiscover, modifier = Modifier.fillMaxWidth()) {
            Text("Discover the Mac (system picker)")
        }
        Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) {
            Text("Connect")
        }
        Button(onClick = onConfirmPairing, modifier = Modifier.fillMaxWidth()) {
            Text("Codes match — confirm pairing")
        }
        OutlinedButton(onClick = onRejectPairing, modifier = Modifier.fillMaxWidth()) {
            Text("Codes don't match — reject")
        }
        Button(onClick = onStartMirroring, modifier = Modifier.fillMaxWidth()) {
            Text("Start mirroring (asks for screen-capture consent)")
        }
        OutlinedButton(onClick = onStopMirroring, modifier = Modifier.fillMaxWidth()) {
            Text("Stop mirroring")
        }
        OutlinedButton(onClick = onCloseSession, modifier = Modifier.fillMaxWidth()) {
            Text("Close session")
        }
        OutlinedButton(onClick = onForgetPin, modifier = Modifier.fillMaxWidth()) {
            Text("Forget stored pin")
        }

        Text("Log:", style = MaterialTheme.typography.titleSmall)
        Text(if (log.isEmpty()) "(none yet)" else log, style = MaterialTheme.typography.bodySmall)
    }
}
