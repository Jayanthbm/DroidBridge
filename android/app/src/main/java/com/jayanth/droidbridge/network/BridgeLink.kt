package com.jayanth.droidbridge.network

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedTrustManager

sealed interface LinkEvent {
    data object Searching : LinkEvent
    data class Pairing(val code: String, val mac: MacHello) : LinkEvent
    data object PairingPending : LinkEvent
    data class Connected(val mac: MacHello) : LinkEvent
    data class Disconnected(val reason: String, val willReconnect: Boolean) : LinkEvent
    data class Failed(val message: String) : LinkEvent
}

class BridgeLink(
    context: Context,
    private val onEvent: (LinkEvent) -> Unit,
) {
    companion object {
        @Volatile
        var active: BridgeLink? = null
            private set
    }

    private val appContext = context.applicationContext
    private val trust = TrustStore(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())

    private var identity: AndroidIdentity? = null
    private var sslContext: SSLContext? = null
    private var discovery: MacDiscovery? = null
    @Volatile private var socket: SSLSocket? = null
    @Volatile private var input: DataInputStream? = null
    @Volatile private var output: DataOutputStream? = null
    private var connectionJob: Job? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null

    private var started = false
    private var connecting = false
    private var manualDisconnect = false
    private var autoPair = false
    private var pairingSent = false
    private var currentMac: MacHello? = null
    private var localFingerprint: String? = null
    private var generation = 0

    init {
        active = this
    }

    fun start(allowNewPairing: Boolean = false, autoPair: Boolean = false) {
        if (started) {
            if (allowNewPairing) beginDiscovery()
            return
        }
        if (!allowNewPairing && trust.load() == null) return
        started = true
        manualDisconnect = false
        this.autoPair = autoPair
        emit(LinkEvent.Searching)
        scope.launch {
            try {
                ensureIdentity()
                beginDiscovery()
            } catch (error: Exception) {
                android.util.Log.e("DroidBridge", "Could not start secure session", error)
                emit(LinkEvent.Failed(error.message ?: "Could not start a secure session."))
            }
        }
    }

    fun pair() {
        if (pairingSent) return
        pairingSent = true
        scope.launch {
            send(WireProtocol.control("pair_confirm", identity?.fingerprint))
            emit(LinkEvent.PairingPending)
        }
    }

    fun reconnect() {
        if (started) return
        manualDisconnect = false
        start(allowNewPairing = trust.load() != null, autoPair = autoPair)
    }

    fun disconnect() {
        manualDisconnect = true
        started = false
        closeSocket()
        discovery?.stop()
        discovery = null
        emit(LinkEvent.Disconnected("Disconnected from Mac", willReconnect = false))
    }

    fun stop() {
        started = false
        manualDisconnect = true
        closeSocket()
        discovery?.stop()
        discovery = null
        reconnectJob?.cancel()
        scope.cancel()
        if (active === this) active = null
    }

    @Synchronized
    fun sendMirrorStopped() {
        if (!started) return
        val stream = output ?: return
        // A stop notice is best-effort; a failed write must not tear down the
        // TLS session while the phone is still paired.
        runCatching { WireProtocol.write(stream, WireProtocol.control("mirror_stopped")) }
    }

    fun sendVideo(
        width: Int,
        height: Int,
        rotation: Int,
        keyframe: Boolean,
        presentationTimeUs: Long,
        accessUnit: ByteArray,
    ) {
        if (!started || output == null) return
        send(WireProtocol.video(width, height, rotation, keyframe, presentationTimeUs, accessUnit))
    }

    private fun ensureIdentity() {
        if (identity != null && sslContext != null) return
        val loaded = AndroidIdentityStore.loadOrCreate(appContext)
        identity = loaded
        localFingerprint = loaded.fingerprint
        sslContext = createSslContext(loaded)
    }

    private fun beginDiscovery() {
        if (!started || manualDisconnect) return
        if (socket != null || connecting) return
        discovery?.stop()
        main.post { emit(LinkEvent.Searching) }
        val scanner = MacDiscovery(
            context = appContext,
            onFound = { mac -> onDiscovered(mac) },
            onError = { message -> emit(LinkEvent.Failed(message)) },
        )
        discovery = scanner
        scanner.start()
    }

    private fun onDiscovered(mac: MacDiscovery.DiscoveredMac) {
        android.util.Log.d("DroidBridge", "Discovered Mac ${mac.serviceName} at ${mac.host}:${mac.port}")
        if (!started || manualDisconnect || connecting || socket != null) return
        discovery?.stop()
        discovery = null
        connecting = true
        val myGeneration = ++generation
        connectionJob = scope.launch {
            try {
                ensureIdentity()
                val context = sslContext ?: error("TLS context unavailable")
                val ssl = openSocket(context, mac)
                socket = ssl
                input = DataInputStream(BufferedInputStream(ssl.inputStream))
                output = DataOutputStream(BufferedOutputStream(ssl.outputStream))

                val first = WireProtocol.read(input!!)
                if (first.type != WireProtocol.TYPE_HELLO) {
                    error("The Mac sent an unexpected first message.")
                }
                val hello = WireProtocol.parseHello(first.payload)
                if (hello.fingerprint.isBlank()) error("The Mac did not present a TLS identity.")
                val peer = ssl.session.peerCertificates.firstOrNull() as? X509Certificate
                    ?: error("The Mac did not present a TLS certificate.")
                val peerFingerprint = WireProtocol.fingerprint(peer.encoded)
                if (peerFingerprint != hello.fingerprint) {
                    error("The Mac's certificate did not match its hello.")
                }

                currentMac = hello
                val trusted = trust.load()?.fingerprint == hello.fingerprint
                val ownFingerprint = localFingerprint ?: error("Local identity unavailable")
                val paired = trusted
                send(
                    WireProtocol.hello(
                        deviceId = ownFingerprint.take(16),
                        name = android.os.Build.MODEL,
                        model = android.os.Build.MODEL,
                        fingerprint = ownFingerprint,
                        paired = paired,
                        battery = batteryLevel(),
                        osVersion = "Android ${android.os.Build.VERSION.RELEASE}",
                    ),
                )

                if (!trusted || !hello.paired) {
                    val code = WireProtocol.pairingCode(ownFingerprint, hello.fingerprint)
                    emit(LinkEvent.Pairing(code, hello))
                    if (autoPair) pair()
                }

                startHeartbeat()
                while (scope.isActive && socket != null && myGeneration == generation) {
                    val frame = WireProtocol.read(input!!)
                    when (frame.type) {
                        WireProtocol.TYPE_CONTROL -> {
                            val control = WireProtocol.parseControl(frame.payload)
                            when (control.action) {
                                "pair_ok" -> {
                                    val mac = currentMac ?: continue
                                    trust.save(
                                        TrustedMac(
                                            fingerprint = mac.fingerprint,
                                            deviceId = mac.deviceId,
                                            name = mac.name,
                                            model = mac.model,
                                            osVersion = mac.osVersion,
                                            pairedAt = System.currentTimeMillis(),
                                        ),
                                    )
                                    pairingSent = false
                                    emit(LinkEvent.Connected(mac))
                                }

                                "pair_cancel" -> {
                                    manualDisconnect = true
                                    started = false
                                    emit(LinkEvent.Disconnected("Pairing cancelled by Mac", willReconnect = false))
                                    return@launch
                                }

                                "disconnect" -> {
                                    manualDisconnect = true
                                    started = false
                                    emit(LinkEvent.Disconnected("Disconnected from Mac", willReconnect = false))
                                    return@launch
                                }

                                "keyframe" -> com.jayanth.droidbridge.mirror.MirrorService.requestKeyFrame()
                                "stop_mirror" -> {
                                    if (com.jayanth.droidbridge.mirror.MirrorService.isRunning) {
                                        com.jayanth.droidbridge.mirror.MirrorService.stop(appContext)
                                    }
                                }
                            }
                        }

                        WireProtocol.TYPE_PING -> send(WireProtocol.ping(frame.payload))
                        else -> Unit
                    }
                }
            } catch (error: Exception) {
                android.util.Log.w("DroidBridge", "Secure session failed", error)
                if (myGeneration == generation && !manualDisconnect) {
                    emit(
                        LinkEvent.Disconnected(
                            reason = error.message ?: "The secure connection closed.",
                            willReconnect = started && trust.load() != null,
                        ),
                    )
                }
            } finally {
                if (myGeneration == generation) {
                    closeSocket()
                    connecting = false
                    pairingSent = false
                    if (started && !manualDisconnect) scheduleReconnect()
                }
            }
        }
    }

    private fun scheduleReconnect() {
        if (!started || manualDisconnect || trust.load() == null) return
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            delay(1_500)
            if (started && !manualDisconnect && socket == null) {
                beginDiscovery()
            }
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(5_000)
                if (socket == null) break
                send(WireProtocol.ping())
            }
        }
    }

    private fun openSocket(context: SSLContext, mac: MacDiscovery.DiscoveredMac): SSLSocket {
        val raw = Socket()
        raw.tcpNoDelay = true
        raw.connect(InetSocketAddress(mac.host, mac.port), 6_000)
        val ssl = context.socketFactory.createSocket(raw, mac.host, mac.port, true) as SSLSocket
        ssl.useClientMode = true
        ssl.enabledProtocols = arrayOf("TLSv1.3")
        val parameters = ssl.sslParameters
        parameters.endpointIdentificationAlgorithm = null
        ssl.sslParameters = parameters
        ssl.soTimeout = 10_000
        ssl.startHandshake()
        ssl.soTimeout = 25_000
        return ssl
    }

    @Synchronized
    private fun send(data: ByteArray) {
        val stream = output ?: return
        try {
            WireProtocol.write(stream, data)
        } catch (_: Exception) {
            closeSocket()
        }
    }

    private fun closeSocket() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
    }

    private fun createSslContext(identity: AndroidIdentity): SSLContext {
        val trustAll = object : X509ExtendedTrustManager() {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = Unit
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: javax.net.ssl.SSLEngine?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: javax.net.ssl.SSLEngine?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLS")
        context.init(identity.keyManagers, arrayOf(trustAll), SecureRandom())
        return context
    }

    private fun batteryLevel(): Int {
        val manager = appContext.getSystemService(android.content.Context.BATTERY_SERVICE)
            as? android.os.BatteryManager
        return manager?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
    }

    private fun emit(event: LinkEvent) {
        main.post { onEvent(event) }
    }
}
