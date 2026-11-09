package com.droidbridge.experiment.discovery

import android.net.nsd.AdvertisingRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * P02 D/B — inbound connections to Android, and Android advertising.
 *
 * Two questions in one small probe because they share the socket:
 *
 * 1. **Can the Mac connect to Android?** ADR-010 chooses Android-dials-out, but the reverse
 *    direction decides whether Mac-initiated reconnect is possible. A listening socket is a
 *    documented Android API, so this is a legitimate test, not a bypass.
 * 2. **Does Android advertising actually require `ACCESS_LOCAL_NETWORK`?** `registerService` with
 *    an `AdvertisingRequest` is expected to fail with `FAILURE_PERMISSION_DENIED` when the
 *    permission is absent. The first S24 Ultra run could not demonstrate the permission being
 *    enforced for discovery; advertising is where the research says it is mandatory.
 *
 * The listener echoes lines, so the Mac's connection test can verify both directions rather than
 * just a successful connect.
 */
class InboundProbe(
    private val nsdManager: NsdManager,
    private val log: (String) -> Unit = { Log.i(TAG, it) },
) {

    companion object {
        private const val TAG = "P02Inbound"
        const val SERVICE_NAME = "DroidBridge-Android-P02"
    }

    private var server: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private val executor: Executor = Executors.newSingleThreadExecutor()

    val port = AtomicInteger(0)
    val connections = AtomicInteger(0)
    val bytesReceived = AtomicInteger(0)
    val isListening = AtomicBoolean(false)
    val isAdvertising = AtomicBoolean(false)

    fun start() {
        if (isListening.get()) return
        try {
            val socket = ServerSocket(0)
            server = socket
            port.set(socket.localPort)
            isListening.set(true)
            log("INBOUND listening port=${socket.localPort} ip=${localWlanAddress()?.hostAddress}")
            acceptThread = Thread({ acceptLoop(socket) }, "p02-inbound").also {
                it.isDaemon = true
                it.start()
            }
        } catch (e: Exception) {
            log("INBOUND failed to listen: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * Advertises the listening socket as `_droidbridge-dev._tcp`.
     *
     * This is Path B from the prototype plan: Android advertises, which the research says always
     * requires `ACCESS_LOCAL_NETWORK`. A refusal here is the evidence that the permission is
     * enforced somewhere, even if the picker path works without it.
     */
    fun advertise() {
        val socketPort = port.get()
        if (socketPort <= 0) {
            log("ADVERTISE skipped: inbound listener is not started")
            return
        }
        val info = NsdServiceInfo().apply {
            serviceName = SERVICE_NAME
            serviceType = DiscoveryProbe.SERVICE_TYPE
            port = socketPort
        }
        val request = AdvertisingRequest.Builder(info)
            .setProtocolType(NsdManager.PROTOCOL_DNS_SD)
            .build()
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                isAdvertising.set(true)
                // The callback's serviceInfo.port is 0 on this build, so log the port that was
                // actually requested. Reporting the callback's zero would look like a failed
                // advertisement when the registration succeeded.
                log("ADVERTISE registered name='${serviceInfo.serviceName}' requestedPort=$socketPort")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                isAdvertising.set(false)
                log("ADVERTISE failed name='${serviceInfo.serviceName}' errorCode=$errorCode (${describe(errorCode)})")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                isAdvertising.set(false)
                log("ADVERTISE unregistered name='${serviceInfo.serviceName}'")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                log("ADVERTISE unregister failed errorCode=$errorCode (${describe(errorCode)})")
            }
        }
        registrationListener = listener
        try {
            nsdManager.registerService(request, executor, listener)
            log("ADVERTISE registerService(AdvertisingRequest) returned without throwing")
        } catch (e: SecurityException) {
            log("ADVERTISE SecurityException: ${e.message}")
        } catch (e: Exception) {
            log("ADVERTISE threw: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    fun stop() {
        val listener = registrationListener
        registrationListener = null
        if (listener != null) {
            runCatching { nsdManager.unregisterService(listener) }
        }
        isAdvertising.set(false)
        isListening.set(false)
        runCatching { server?.close() }
        server = null
        acceptThread = null
        log("INBOUND stopped")
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (isListening.get() && !socket.isClosed) {
            try {
                val connection = socket.accept()
                connections.incrementAndGet()
                val peer = connection.inetAddress.hostAddress
                log("INBOUND accepted from $peer")
                Thread({ handle(connection, peer) }, "p02-inbound-conn").also {
                    it.isDaemon = true
                    it.start()
                }
            } catch (e: Exception) {
                if (isListening.get()) {
                    log("INBOUND accept ended: ${e.javaClass.simpleName}: ${e.message}")
                }
                return
            }
        }
    }

    private fun handle(connection: java.net.Socket, peer: String?) {
        try {
            val reader = BufferedReader(InputStreamReader(connection.getInputStream()))
            val writer = OutputStreamWriter(connection.getOutputStream())
            var line = reader.readLine()
            while (line != null) {
                bytesReceived.addAndGet(line.length + 1)
                log("INBOUND received '${line.take(80)}' from $peer; echoing")
                writer.write(line)
                writer.write("\n")
                writer.flush()
                line = reader.readLine()
            }
            log("INBOUND peer closed: $peer")
        } catch (e: Exception) {
            log("INBOUND connection error: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            runCatching { connection.close() }
        }
    }

    /** The Wi-Fi address to connect to from the Mac, without shelling out to `ip`. */
    private fun localWlanAddress(): InetAddress? {
        return runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .firstOrNull { it.name == "wlan0" }
                ?.inetAddresses?.toList()
                ?.firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }
        }.getOrNull()
    }

    private fun describe(code: Int): String = when (code) {
        NsdManager.FAILURE_ALREADY_ACTIVE -> "FAILURE_ALREADY_ACTIVE"
        NsdManager.FAILURE_BAD_PARAMETERS -> "FAILURE_BAD_PARAMETERS"
        NsdManager.FAILURE_INTERNAL_ERROR -> "FAILURE_INTERNAL_ERROR"
        NsdManager.FAILURE_MAX_LIMIT -> "FAILURE_MAX_LIMIT"
        NsdManager.FAILURE_OPERATION_NOT_RUNNING -> "FAILURE_OPERATION_NOT_RUNNING"
        NsdManager.FAILURE_PERMISSION_DENIED -> "FAILURE_PERMISSION_DENIED"
        else -> "unknown code"
    }
}
