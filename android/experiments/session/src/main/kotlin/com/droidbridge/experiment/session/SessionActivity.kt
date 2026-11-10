package com.droidbridge.experiment.session

import android.Manifest
import android.content.pm.PackageManager
import android.net.nsd.DiscoveryRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
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
import com.droidbridge.core.network.SessionNotAuthenticatedException
import com.droidbridge.core.security.DeviceIdentity
import com.droidbridge.core.security.KeystorePinStore
import com.droidbridge.core.security.PeerPinning
import com.droidbridge.protocol.session.ControlMessage
import com.droidbridge.protocol.session.ShortAuthenticationCode
import java.util.concurrent.Executors

/**
 * P03 experiment UI.
 *
 * Each control is one acceptance criterion. The log is the evidence, and every line is written to
 * logcat under `P03Session` so a scripted run can read it without scraping the screen.
 *
 * ## The pairing confirmation is the point of this screen
 *
 * The flow is deliberately two-step for an unknown peer:
 *
 * 1. **Connect (first pairing)** runs a TLS 1.3 handshake with no expected pin and derives a six
 *    digit code from both public keys. The session is [SecureSession.State.AWAITING_CONFIRMATION]
 *    and both sides refuse application data.
 * 2. The user reads the code on both screens and presses **Codes match — confirm pairing** (or
 *    rejects). Only confirming stores the peer pin and calls [SecureSession.authenticate].
 *
 * The buttons are therefore not decoration: they are the only path from an unauthenticated to an
 * authenticated session, and [SecureSession] independently refuses data while unconfirmed.
 *
 * A peer that already has a stored pin skips step 1's code comparison: the pin is checked inside the
 * handshake, and re-prompting on every reconnect would train the user to confirm without looking.
 */
class SessionActivity : ComponentActivity() {

    private val tag = "P03Session"
    private val executor = Executors.newSingleThreadExecutor()

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

    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            record("ACCESS_LOCAL_NETWORK granted=$granted")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        nsdManager = getSystemService(NsdManager::class.java)
        identity = DeviceIdentity.loadOrCreate()
        pinStore = KeystorePinStore(applicationContext)
        storedPinText = pinStore.pinFor(PEER_ID) ?: "(none)"
        record("identity loaded: pin=${identity.pin}")
        record("stored peer pin for $PEER_ID: ${storedPinText}")

        setContent {
            MaterialTheme {
                SessionScreen(
                    status = statusText,
                    ownPin = identity.pin,
                    storedPin = storedPinText,
                    peerPin = peerPinText,
                    code = codeText,
                    dataState = dataStateText,
                    log = logText,
                    onRequestPermission = {
                        requestPermission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
                    },
                    onDiscover = ::discover,
                    onConnectFirstPairing = { connect(firstPairing = true) },
                    onConnectKnownPeer = { connect(firstPairing = false) },
                    onConfirmPairing = ::confirmPairing,
                    onRejectPairing = ::rejectPairing,
                    onSendPing = ::sendPing,
                    onReceive = ::receiveOnce,
                    onClose = ::closeSession,
                    onForgetPin = ::forgetPin,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val current = session
        if (current != null) {
            record("foreground: session state=${current.state}")
        }
    }

    override fun onStop() {
        // The prototype owns the session from the activity, so backgrounding keeps it alive only as
        // long as the process lives. The product runs it in a connectedDevice foreground service
        // (ADR-023, P14); P03 does not claim background liveness.
        val current = session
        record("background: session state=${current?.state ?: "none"} (prototype keeps the socket)")
        super.onStop()
    }

    override fun onDestroy() {
        closeSession()
        super.onDestroy()
    }

    // --- discovery -------------------------------------------------------------------------

    /**
     * Discovers the Mac through the API 37 picker, then resolves it.
     *
     * P02 proved this path works; P03 reuses it rather than re-testing it, so that the session is
     * established the same way the product will.
     */
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
                discovered = serviceInfo
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

    // --- session ---------------------------------------------------------------------------

    /**
     * Connects to the discovered peer.
     *
     * @param firstPairing true runs a no-pin handshake for code comparison and is refused when a pin
     *   is already stored; false verifies the stored pin inside the handshake and is refused when
     *   there is none. A changed identity can therefore never be accepted by choosing the other
     *   button: it fails the stored-pin handshake, and the first-pairing path will not run.
     */
    private fun connect(firstPairing: Boolean) {
        val target = discovered
        if (target?.host == null) {
            record("connect refused: no resolved peer, run Discover first")
            return
        }

        val stored = pinStore.pinFor(PEER_ID)
        val expectedPin: String?
        if (firstPairing) {
            if (stored != null) {
                record(
                    "connect refused: a pin is already stored for $PEER_ID; " +
                        "use 'Connect (verify stored pin)' or forget it to re-pair",
                )
                return
            }
            expectedPin = null
        } else {
            if (stored == null) {
                record("connect refused: no stored pin for $PEER_ID; run a first pairing")
                return
            }
            expectedPin = stored
        }

        val host = target.host!!.hostAddress
        val port = target.port
        record("connecting to $host:$port expectedPin=${expectedPin ?: "(none, first pairing)"}")

        executor.execute {
            try {
                val s = SecureSession.connect(host, port, identity, expectedPin)
                session = s
                val code = s.shortAuthenticationCode(identity)
                runOnUiThread {
                    peerPinText = s.peerPin
                    codeText = ShortAuthenticationCode.formatForDisplay(code)
                    dataStateText = if (s.isAuthenticated) {
                        "available (authenticated)"
                    } else {
                        "locked (confirm the code)"
                    }
                    statusText = if (s.isAuthenticated) {
                        "connected (stored pin verified)"
                    } else {
                        "connected (awaiting code confirmation)"
                    }
                }
                record(
                    "handshake ok: protocol=TLSv1.3 peerPin=${s.peerPin} " +
                        "authenticated=${s.isAuthenticated} code=$code",
                )
            } catch (e: Exception) {
                record("connect FAILED: ${e.javaClass.simpleName}: ${e.message}")
                runOnUiThread {
                    statusText = "connect failed"
                    dataStateText = "no session"
                }
            }
        }
    }

    /**
     * Completes a first pairing after the user compared the codes.
     *
     * Persists the pin **before** marking the session authenticated, and refuses to overwrite a pin
     * that appeared in the meantime. If persistence fails, the session stays unauthenticated and is
     * closed: a pairing that cannot be remembered must not be treated as permanent.
     */
    private fun confirmPairing() {
        val s = session ?: run { record("confirm refused: no session"); return }
        if (s.isAuthenticated) {
            record("confirm ignored: session is already authenticated")
            return
        }

        executor.execute {
            try {
                if (pinStore.pinFor(PEER_ID) != null) {
                    record("confirm refused: a pin appeared while pairing; not overwriting it")
                    return@execute
                }
                val code = s.shortAuthenticationCode(identity)
                PeerPinning.confirm(PEER_ID, s.peerCertificate, pinStore)
                val persisted = pinStore.pinFor(PEER_ID) == s.peerPin
                if (!persisted) {
                    throw IllegalStateException("pin did not persist")
                }
                s.authenticate()
                runOnUiThread {
                    statusText = "authenticated (pairing confirmed)"
                    dataStateText = "available (authenticated)"
                    storedPinText = pinStore.pinFor(PEER_ID) ?: "(none)"
                }
                record("pairing confirmed: peerPin=${s.peerPin} code=$code pin persisted=$persisted")
            } catch (e: Exception) {
                record("confirm FAILED: ${e.javaClass.simpleName}: ${e.message}")
                s.close("pairing confirmation failed")
                session = null
                runOnUiThread {
                    statusText = "confirmation failed"
                    dataStateText = "no session"
                }
            }
        }
    }

    /** Rejects the pairing: no pin is stored and the session closes. */
    private fun rejectPairing() {
        val s = session ?: run { record("reject ignored: no session"); return }
        if (s.isAuthenticated) {
            // The pin is already confirmed and stored; closing the socket here would leave a stored
            // pin behind a "rejected" label. Use "Forget stored pin" or "Close session" instead.
            record("reject ignored: session is already authenticated")
            return
        }
        record("pairing rejected by user: no pin stored, closing session")
        s.close("pairing rejected by user")
        session = null
        runOnUiThread {
            codeText = ""
            peerPinText = "(none)"
            statusText = "pairing rejected"
            dataStateText = "no session"
        }
    }

    private fun sendPing() {
        val s = session ?: run { record("send refused: no session"); return }
        executor.execute {
            try {
                s.send(ControlMessage.Type.PING, "session.ping".toByteArray())
                record("sent PING payload=session.ping")
            } catch (e: SessionNotAuthenticatedException) {
                record("send refused: session is not confirmed (compare the code first)")
            } catch (e: Exception) {
                record("send failed: ${e.javaClass.simpleName}: ${e.message}")
                session = null
                runOnUiThread {
                    statusText = "session closed"
                    dataStateText = "no session"
                }
            }
        }
    }

    private fun receiveOnce() {
        val s = session ?: run { record("receive refused: no session"); return }
        executor.execute {
            try {
                val message = s.tryReceive(10_000)
                if (message == null) {
                    record("receive: no frame within 10s (session still open)")
                } else {
                    record("received ${ControlMessage.describe(message)}")
                }
            } catch (e: SessionNotAuthenticatedException) {
                record("receive refused: session is not confirmed")
            } catch (e: Exception) {
                record("receive failed: ${e.javaClass.simpleName}: ${e.message}")
                session = null
                runOnUiThread {
                    statusText = "session closed"
                    dataStateText = "no session"
                }
            }
        }
    }

    private fun closeSession() {
        val s = session
        s?.close("closed from UI")
        session = null
        runOnUiThread {
            statusText = "closed"
            codeText = ""
            peerPinText = "(none)"
            dataStateText = "no session"
        }
        record("session closed")
    }

    private fun forgetPin() {
        pinStore.forget(PEER_ID)
        runOnUiThread {
            storedPinText = "(none)"
            statusText = "stored pin forgotten (next connect is a first pairing)"
        }
        record("forgot pin for $PEER_ID")
    }

    private fun record(line: String) {
        Log.i(tag, line)
        runOnUiThread {
            // Bounded: a long session must not grow the text without limit.
            val next = if (logText.isEmpty()) line else "$logText\n$line"
            logText = if (next.length > 6000) next.takeLast(6000) else next
        }
    }

    companion object {
        /** Must match the Mac's advertised type (P02). */
        private const val SERVICE_TYPE = "_droidbridge-dev._tcp"

        /** Pin key. The service name is what the user selected and will recognise. */
        private const val PEER_ID = "DroidBridge-P03-Mac"
    }
}

@Composable
private fun SessionScreen(
    status: String,
    ownPin: String,
    storedPin: String,
    peerPin: String,
    code: String,
    dataState: String,
    log: String,
    onRequestPermission: () -> Unit,
    onDiscover: () -> Unit,
    onConnectFirstPairing: () -> Unit,
    onConnectKnownPeer: () -> Unit,
    onConfirmPairing: () -> Unit,
    onRejectPairing: () -> Unit,
    onSendPing: () -> Unit,
    onReceive: () -> Unit,
    onClose: () -> Unit,
    onForgetPin: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("P03 — secure session", style = MaterialTheme.typography.titleLarge)
        Text("TLS 1.3, mutual auth, pinned identity.", style = MaterialTheme.typography.bodySmall)
        Text("status: $status")
        Text("own pin: $ownPin", style = MaterialTheme.typography.bodySmall)
        Text("stored peer pin: $storedPin", style = MaterialTheme.typography.bodySmall)
        Text("peer pin: $peerPin", style = MaterialTheme.typography.bodySmall)
        Text("application data: $dataState", style = MaterialTheme.typography.bodyMedium)
        if (code.isNotEmpty()) {
            Text("code: $code", style = MaterialTheme.typography.headlineSmall)
        }

        Button(onClick = onRequestPermission, modifier = Modifier.fillMaxWidth()) {
            Text("Request local-network permission")
        }
        Button(onClick = onDiscover, modifier = Modifier.fillMaxWidth()) {
            Text("Discover with picker")
        }
        Button(onClick = onConnectFirstPairing, modifier = Modifier.fillMaxWidth()) {
            Text("Connect (first pairing, no pin)")
        }
        Button(onClick = onConnectKnownPeer, modifier = Modifier.fillMaxWidth()) {
            Text("Connect (verify stored pin)")
        }
        Button(onClick = onConfirmPairing, modifier = Modifier.fillMaxWidth()) {
            Text("Codes match — confirm pairing")
        }
        OutlinedButton(onClick = onRejectPairing, modifier = Modifier.fillMaxWidth()) {
            Text("Codes don't match — reject pairing")
        }
        OutlinedButton(onClick = onSendPing, modifier = Modifier.fillMaxWidth()) {
            Text("Send session.ping")
        }
        OutlinedButton(onClick = onReceive, modifier = Modifier.fillMaxWidth()) {
            Text("Receive one frame")
        }
        OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
            Text("Close session")
        }
        OutlinedButton(onClick = onForgetPin, modifier = Modifier.fillMaxWidth()) {
            Text("Forget stored pin")
        }

        Text("Log:", style = MaterialTheme.typography.titleSmall)
        Text(if (log.isEmpty()) "(none yet)" else log, style = MaterialTheme.typography.bodySmall)
    }
}
