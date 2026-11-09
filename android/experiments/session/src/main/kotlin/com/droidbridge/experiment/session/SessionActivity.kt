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
import com.droidbridge.protocol.session.ShortAuthenticationCode
import java.util.concurrent.Executors

/**
 * P03 experiment UI.
 *
 * Each button is one acceptance criterion. The log is the evidence, and every line is written to
 * logcat under `P03Session` so a scripted run can read it without scraping the screen.
 */
class SessionActivity : ComponentActivity() {

    private val tag = "P03Session"
    private val executor = Executors.newSingleThreadExecutor()

    private lateinit var nsdManager: NsdManager
    private lateinit var identity: DeviceIdentity
    private val pinStore = PeerPinning.Store()

    private var session: SecureSession? = null
    private var discovered: NsdServiceInfo? = null

    private var logText by mutableStateOf("")
    private var statusText by mutableStateOf("idle")
    private var codeText by mutableStateOf("")
    private var pinText by mutableStateOf("")

    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            record("ACCESS_LOCAL_NETWORK granted=$granted")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        nsdManager = getSystemService(NsdManager::class.java)
        identity = DeviceIdentity.loadOrCreate()
        pinText = identity.pin
        record("identity loaded: pin=${identity.pin}")

        setContent {
            MaterialTheme {
                SessionScreen(
                    status = statusText,
                    ownPin = pinText,
                    code = codeText,
                    log = logText,
                    onRequestPermission = {
                        requestPermission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
                    },
                    onDiscover = ::discover,
                    onConnect = { confirmFirst -> connect(confirmFirst) },
                    onSendPing = ::sendPing,
                    onReceive = ::receiveOnce,
                    onClose = ::closeSession,
                    onForgetPin = {
                        pinStore.forget(PEER_ID)
                        record("forgot pin for $PEER_ID")
                    },
                )
            }
        }
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
     * @param confirmFirst when true, connects with the stored pin if there is one, so the handshake
     *   only completes for a peer whose pin matches. When false, connects with no pin so the code
     *   can be derived for a first pairing.
     */
    private fun connect(confirmFirst: Boolean) {
        val target = discovered
        if (target?.host == null) {
            record("connect refused: no resolved peer, run Discover first")
            return
        }

        val host = target.host!!.hostAddress
        val port = target.port
        val expectedPin = if (confirmFirst) pinStore.pinFor(PEER_ID) else null
        record("connecting to $host:$port expectedPin=${expectedPin ?: "(none, first pairing)"}")

        executor.execute {
            try {
                val s = SecureSession.connect(host, port, identity, expectedPin)
                session = s
                val code = s.shortAuthenticationCode(identity)
                runOnUiThread {
                    codeText = ShortAuthenticationCode.formatForDisplay(code)
                    statusText = if (s.isConfirmed) "connected (pin verified)" else "connected (unconfirmed)"
                }
                record(
                    "handshake ok: protocol=TLSv1.3 peerPin=${s.peerPin} " +
                        "confirmed=${s.isConfirmed} code=$code",
                )
            } catch (e: Exception) {
                record("connect FAILED: ${e.javaClass.simpleName}: ${e.message}")
                runOnUiThread { statusText = "connect failed" }
            }
        }
    }

    private fun sendPing() {
        val s = session ?: run { record("send refused: no session"); return }
        if (!s.isConfirmed) {
            // The whole point of the confirmation step: an unconfirmed peer must not receive data.
            record("send refused: session is not confirmed (compare the code first)")
            return
        }
        executor.execute {
            try {
                val payload = "session.ping".toByteArray()
                s.send(payload)
                record("sent ${payload.size} bytes: session.ping")
            } catch (e: Exception) {
                record("send failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private fun receiveOnce() {
        val s = session ?: run { record("receive refused: no session"); return }
        executor.execute {
            val payload = s.receiveOrNull()
            if (payload == null) {
                record("receive: closed or framing error ${s.lastFramingError?.message ?: ""}")
            } else {
                record("received ${payload.size} bytes: ${String(payload).take(80)}")
            }
        }
    }

    private fun closeSession() {
        session?.close()
        session = null
        statusText = "closed"
        record("session closed")
    }

    private fun record(line: String) {
        Log.i(tag, line)
        runOnUiThread { logText = if (logText.isEmpty()) line else "$logText\n$line" }
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
    code: String,
    log: String,
    onRequestPermission: () -> Unit,
    onDiscover: () -> Unit,
    onConnect: (Boolean) -> Unit,
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
        if (code.isNotEmpty()) {
            Text("code: $code", style = MaterialTheme.typography.headlineSmall)
        }

        Button(onClick = onRequestPermission, modifier = Modifier.fillMaxWidth()) {
            Text("Request local-network permission")
        }
        Button(onClick = onDiscover, modifier = Modifier.fillMaxWidth()) {
            Text("Discover with picker")
        }
        Button(onClick = { onConnect(false) }, modifier = Modifier.fillMaxWidth()) {
            Text("Connect (first pairing, no pin)")
        }
        Button(onClick = { onConnect(true) }, modifier = Modifier.fillMaxWidth()) {
            Text("Connect (verify stored pin)")
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
