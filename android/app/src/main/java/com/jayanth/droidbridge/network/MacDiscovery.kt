package com.jayanth.droidbridge.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
class MacDiscovery(
    context: Context,
    private val onFound: (DiscoveredMac) -> Unit,
    private val onError: (String) -> Unit,
) {
    data class DiscoveredMac(
        val host: String,
        val port: Int,
        val serviceName: String,
        val deviceId: String?,
        val name: String?,
    )

    private val nsd = context.applicationContext.getSystemService(NsdManager::class.java)
    private var listener: NsdManager.DiscoveryListener? = null
    private var started = false
    private val resolving = mutableSetOf<String>()

    fun start() {
        if (started) return
        started = true
        val discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!serviceInfo.serviceType.startsWith(SERVICE_TYPE)) return
                resolve(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit

            override fun onDiscoveryStopped(serviceType: String) {
                started = false
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                started = false
                onError("Local discovery failed (code $errorCode).")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        listener = discovery
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
        } catch (error: Exception) {
            started = false
            onError(error.message ?: "Could not start local discovery.")
        }
    }

    fun stop() {
        val discovery = listener ?: return
        listener = null
        started = false
        runCatching { nsd.stopServiceDiscovery(discovery) }
    }

    private fun resolve(serviceInfo: NsdServiceInfo) {
        val key = serviceInfo.serviceName + ":" + serviceInfo.serviceType
        if (!resolving.add(key)) return
        try {
            nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    resolving.remove(key)
                    if (errorCode != NsdManager.FAILURE_ALREADY_ACTIVE) {
                        onError("Could not resolve the Mac service (code $errorCode).")
                    }
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    resolving.remove(key)
                    val host = info.host
                    android.util.Log.d(
                        "DroidBridge",
                        "Resolved ${info.serviceName}: host=$host name=${host?.hostName} address=${host?.hostAddress} port=${info.port}",
                    )
                    if (host == null) {
                        onError("The Mac was found but its address was empty.")
                        return
                    }
                    // Android's NSD often returns the .local hostname rather than an
                    // InetAddress. Either form is accepted by InetSocketAddress.
                    val address = host.hostAddress?.takeIf { it.isNotBlank() } ?: host.hostName
                    val txt = info.attributes.orEmpty()
                    val name = txt["name"]?.toString(Charsets.UTF_8)
                    val deviceId = txt["id"]?.toString(Charsets.UTF_8)
                    onFound(
                        DiscoveredMac(
                            host = address,
                            port = info.port,
                            serviceName = info.serviceName,
                            deviceId = deviceId,
                            name = name,
                        ),
                    )
                }
            })
        } catch (error: Exception) {
            resolving.remove(key)
            onError(error.message ?: "Could not resolve the Mac service.")
        }
    }

    private companion object {
        const val SERVICE_TYPE = "_droidbridge._tcp."
    }
}
