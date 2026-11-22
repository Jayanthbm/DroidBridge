package com.droidbridge.core.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log

/**
 * The discovery half of Phase 2: find the Mac on the local network.
 *
 * Direction is fixed by the permission model (ADR-010): the Mac advertises and listens, Android
 * discovers and connects outbound. That is why there is no `advertise` here — Android advertising
 * would need `ACCESS_LOCAL_NETWORK` unconditionally, while connecting to a discovered service does
 * not.
 *
 * Two properties matter more than the API calls:
 *
 * * **The address is never cached by this class.** `NsdServiceInfo` is resolved to a host and port
 *   for the caller's immediate use, and nothing is retained. The protocol requires a fresh resolve on
 *   every reconnect, network change and wake, so retaining an IP here would be the exact bug
 *   §2 of `NETWORK_PROTOCOL.md` forbids.
 * * **The TXT record is treated as untrusted.** It is only a hint for the protocol version and a
 *   service identifier; anything a peer could lie about (identity, pin, clipboard data) is not read
 *   from it, and a peer that advertises an incompatible version is reported as such rather than
 *   quietly connected to.
 */
class ServiceDiscovery(private val context: Context) {

    /** One discovered Mac, resolved and usable for a connect attempt right now. */
    data class Peer(
        val serviceName: String,
        val host: String,
        val port: Int,
        /** Protocol version from the TXT record, or null when the peer published none. */
        val protocolVersion: Int?,
    ) {
        /** True when this app can speak to the peer's advertised protocol version. */
        val isCompatible: Boolean get() = protocolVersion == null || protocolVersion == SUPPORTED_PROTOCOL_VERSION

        /** A stable key for UI lists: the service name, never the address. */
        val key: String get() = serviceName
    }

    companion object {
        private const val TAG = "P03Discovery"

        /** The private type used by prototypes and this first implementation (ADR-022). */
        const val SERVICE_TYPE = "_droidbridge-dev._tcp"

        /**
         * TXT key carrying the protocol major version.
         *
         * Must match what the Mac publishes (`ServiceTxtRecord.dictionary` in
         * `macos/Core/ServiceAdvertisement.swift`). It was `pv` here and `v` there, which discovery
         * silently reported as "unstated" instead of failing — the kind of mismatch that only shows
         * up when someone reads the record.
         */
        const val TXT_PROTOCOL_VERSION = "v"

        const val SUPPORTED_PROTOCOL_VERSION = 1
    }

    private val nsdManager: NsdManager? =
        context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager

    private var activeListener: NsdManager.DiscoveryListener? = null
    private var infoCallbacks = mutableListOf<Any>()
    private val resolved = LinkedHashMap<String, Peer>()

    @Volatile private var collecting: ((List<Peer>) -> Unit)? = null

    /**
     * Starts discovery. [onPeers] is called on every change with the full current set, so the caller
     * never has to reassemble a list from deltas.
     */
    fun start(onPeers: (List<Peer>) -> Unit) {
        val manager = nsdManager
        if (manager == null) {
            Log.e(TAG, "NSD is unavailable on this device")
            return
        }
        stop()
        collecting = onPeers
        resolved.clear()

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.i(TAG, "discovery started type=$serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "service found name=${serviceInfo.serviceName}")
                resolve(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "service lost name=${serviceInfo.serviceName}")
                synchronized(resolved) {
                    resolved.remove(serviceInfo.serviceName)
                    publish()
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(TAG, "discovery stopped type=$serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "start discovery failed error=$errorCode")
                stopInternal()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "stop discovery failed error=$errorCode")
                stopInternal()
            }
        }
        activeListener = listener
        runCatching {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure {
            Log.e(TAG, "discoverServices threw ${it.javaClass.simpleName}: ${it.message}")
            stopInternal()
        }
    }

    fun stop() {
        stopInternal()
        synchronized(resolved) { resolved.clear() }
    }

    private fun stopInternal() {
        val manager = nsdManager ?: return
        activeListener?.let { listener ->
            runCatching { manager.stopServiceDiscovery(listener) }
        }
        activeListener = null
        collecting = null
        // Service-info callbacks are unregistered on API 34+; leaving them registered would leak a
        // reference to the previous UI surface on every restart.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            infoCallbacks.forEach { callback ->
                runCatching { manager.unregisterServiceInfoCallback(callback as NsdManager.ServiceInfoCallback) }
            }
        }
        infoCallbacks.clear()
    }

    /**
     * Resolves a found service to a host and port.
     *
     * `resolveService` is deprecated in favour of `registerServiceInfoCallback` but remains
     * functional and is the only option below API 34; both paths are used so the same code works on
     * the OnePlus (Android 12) and the S24 Ultra (Android 17).
     */
    private fun resolve(serviceInfo: NsdServiceInfo) {
        val manager = nsdManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val callback = object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                    Log.e(TAG, "service info callback registration failed error=$errorCode")
                }

                override fun onServiceUpdated(info: NsdServiceInfo) {
                    record(info)
                }

                override fun onServiceLost() {
                    synchronized(resolved) {
                        resolved.remove(serviceInfo.serviceName)
                        publish()
                    }
                }

                override fun onServiceInfoCallbackUnregistered() = Unit
            }
            infoCallbacks += callback
            runCatching {
                manager.registerServiceInfoCallback(serviceInfo, { it.run() }, callback)
            }.onFailure {
                Log.e(TAG, "registerServiceInfoCallback threw ${it.javaClass.simpleName}: ${it.message}")
            }
        } else {
            @Suppress("DEPRECATION")
            runCatching {
                manager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(failed: NsdServiceInfo, errorCode: Int) {
                        Log.e(TAG, "resolve failed name=${failed.serviceName} error=$errorCode")
                    }

                    override fun onServiceResolved(info: NsdServiceInfo) {
                        record(info)
                    }
                })
            }.onFailure {
                Log.e(TAG, "resolveService threw ${it.javaClass.simpleName}: ${it.message}")
            }
        }
    }

    private fun record(info: NsdServiceInfo) {
        // An unresolved service has no host: recording it would publish a peer that cannot be dialled.
        val host = info.host?.hostAddress ?: run {
            Log.i(TAG, "not resolved yet name=${info.serviceName}")
            return
        }
        val version = textAttribute(info, TXT_PROTOCOL_VERSION)?.toIntOrNull()
        val peer = Peer(
            serviceName = info.serviceName,
            host = host,
            port = info.port,
            protocolVersion = version,
        )
        Log.i(
            TAG,
            "resolved name=${peer.serviceName} host=${peer.host} port=${peer.port} pv=${peer.protocolVersion ?: "none"}",
        )
        synchronized(resolved) {
            resolved[peer.serviceName] = peer
            publish()
        }
    }

    private fun textAttribute(info: NsdServiceInfo, key: String): String? {
        val attributes = info.attributes ?: return null
        val raw = attributes[key] ?: return null
        return runCatching { String(raw, Charsets.UTF_8) }.getOrNull()
    }

    private fun publish() {
        val snapshot = synchronized(resolved) { resolved.values.toList() }
        collecting?.invoke(snapshot)
    }
}
