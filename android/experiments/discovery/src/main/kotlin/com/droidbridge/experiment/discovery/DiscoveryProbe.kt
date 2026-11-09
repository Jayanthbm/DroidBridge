package com.droidbridge.experiment.discovery

import android.net.nsd.DiscoveryRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * P02: measure the API 37 local-network discovery path.
 *
 * The question this answers is narrow and decisive: **does the Android 17 system picker appear, and
 * what does the app actually receive after the user approves a peer?** Everything else about P02
 * depends on that answer, so the experiment records the grant state rather than assuming it.
 *
 * Three states are distinguished, because they are three different product problems:
 *
 * 1. `PERMISSION_DENIED` — `ACCESS_LOCAL_NETWORK` was refused. Nothing else can be measured.
 * 2. `PICKER_SHOWN` — the system picker appeared, so the OS is mediating peer selection.
 * 3. `DISCOVERED` — a peer came back through the listener, meaning the grant produced a usable
 *    service record.
 *
 * A run that reaches 2 but never 3 is a real and reportable outcome: it would mean the picker
 * grants access to a peer the app cannot then resolve. That distinction is the entire point of
 * keeping the states separate instead of collapsing them into "discovery works".
 *
 * The listener callbacks are the only source of truth here. No timeout is treated as a result: a
 * discovery that finds nothing is recorded as finding nothing, not as failure.
 */
class DiscoveryProbe(
    private val nsdManager: NsdManager,
) {

    companion object {
        private const val TAG = "P02Discovery"

        /** The service type the Mac advertises (P02 Path A). Must match ServiceAdvertisement.swift. */
        const val SERVICE_TYPE = "_droidbridge-dev._tcp"

        /**
         * How long to wait for the picker and the first result.
         *
         * Long enough that a human can actually tap through the picker, short enough that a run
         * cannot hang the UI forever. The value is a UI convenience, not a protocol constant.
         */
        const val TIMEOUT_MS = 60_000L
    }

    /** One observation, appended in order so the report reads as a timeline. */
    private val events = mutableListOf<String>()

    /** The last service the listener reported, if any. */
    private val lastService = AtomicReference<NsdServiceInfo?>(null)

    private val executor: Executor = Executors.newSingleThreadExecutor()

    /** Set once a discovery listener is registered, so it can be stopped exactly once. */
    private var activeListener: NsdManager.DiscoveryListener? = null

    fun events(): List<String> = synchronized(events) { events.toList() }

    fun lastService(): NsdServiceInfo? = lastService.get()

    private fun record(line: String) {
        synchronized(events) { events.add(line) }
        Log.i(TAG, line)
    }

    /**
     * Starts discovery with the system picker.
     *
     * `FLAG_SHOW_PICKER` is the whole experiment: it asks the OS to present the peer list and to
     * hand back only what the user approved. `FLAG_USER_APPROVED_ONLY` is deliberately not combined
     * with it on the first run, because the two flags answer different questions and setting both
     * at once would make it impossible to tell which one produced the behaviour.
     */
    fun startWithPicker() {
        record("start: requesting discovery with FLAG_SHOW_PICKER")

        val request = try {
            DiscoveryRequest.Builder(SERVICE_TYPE)
                .setFlags(DiscoveryRequest.FLAG_SHOW_PICKER)
                .build()
        } catch (e: Exception) {
            // A failure here is a finding, not a crash: it means the API exists but refuses this
            // request shape, which is different from the API being absent.
            record("FAILED to build DiscoveryRequest: ${e.javaClass.simpleName}: ${e.message}")
            return
        }

        record("built request: type=$SERVICE_TYPE flags=${request.flags}")

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                record("onDiscoveryStarted: $serviceType (picker should be visible now)")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                lastService.set(serviceInfo)
                record(
                    "onServiceFound: name='${serviceInfo.serviceName}' " +
                        "type='${serviceInfo.serviceType}' host=${serviceInfo.host} " +
                        "port=${serviceInfo.port}",
                )
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                record("onServiceLost: name='${serviceInfo.serviceName}'")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                record("onDiscoveryStopped: $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                record("onStartDiscoveryFailed: $serviceType errorCode=$errorCode (${describe(errorCode)})")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                record("onStopDiscoveryFailed: $serviceType errorCode=$errorCode (${describe(errorCode)})")
            }
        }

        activeListener = listener
        try {
            nsdManager.discoverServices(request, executor, listener)
            record("discoverServices(DiscoveryRequest) returned without throwing")
        } catch (e: SecurityException) {
            // The expected failure when ACCESS_LOCAL_NETWORK is not granted. Recorded explicitly
            // so "permission missing" is never confused with "no peers on the network".
            record("SecurityException from discoverServices: ${e.message}")
            activeListener = null
        } catch (e: Exception) {
            record("Exception from discoverServices: ${e.javaClass.simpleName}: ${e.message}")
            activeListener = null
        }
    }

    /**
     * Second run: the same discovery, but restricted to peers the user has already approved.
     *
     * This is the flag that matters for the product. If it works, DroidBridge can rediscover a
     * previously approved Mac without showing the picker again, which is the difference between a
     * usable reconnect and asking the user to re-approve on every launch.
     */
    fun startWithUserApprovedOnly() {
        record("start: requesting discovery with FLAG_USER_APPROVED_ONLY")

        val request = try {
            DiscoveryRequest.Builder(SERVICE_TYPE)
                .setFlags(DiscoveryRequest.FLAG_USER_APPROVED_ONLY)
                .build()
        } catch (e: Exception) {
            record("FAILED to build request: ${e.javaClass.simpleName}: ${e.message}")
            return
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                record("approved-only onDiscoveryStarted: $serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                lastService.set(serviceInfo)
                record("approved-only onServiceFound: '${serviceInfo.serviceName}' port=${serviceInfo.port}")
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                record("approved-only onServiceLost: '${serviceInfo.serviceName}'")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                record("approved-only onDiscoveryStopped: $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                record("approved-only onStartDiscoveryFailed: errorCode=$errorCode (${describe(errorCode)})")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                record("approved-only onStopDiscoveryFailed: errorCode=$errorCode")
            }
        }

        activeListener = listener
        try {
            nsdManager.discoverServices(request, executor, listener)
            record("approved-only discoverServices returned without throwing")
        } catch (e: SecurityException) {
            record("approved-only SecurityException: ${e.message}")
            activeListener = null
        } catch (e: Exception) {
            record("approved-only Exception: ${e.javaClass.simpleName}: ${e.message}")
            activeListener = null
        }
    }

    /**
     * Resolves the last found service to a concrete host and port.
     *
     * Discovery gives a service name; connecting needs an address. Those are separate steps and
     * either can fail, so the resolve result is recorded separately from the discovery result.
     */
    fun resolveLast(onResult: (String) -> Unit) {
        val service = lastService.get()
        if (service == null) {
            record("resolve: nothing discovered yet, nothing to resolve")
            onResult("nothing to resolve")
            return
        }

        record("resolve: resolving '${service.serviceName}'")
        try {
            nsdManager.resolveService(
                service,
                executor,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        val line = "onResolveFailed: errorCode=$errorCode (${describe(errorCode)})"
                        record(line)
                        onResult(line)
                    }

                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        val line =
                            "onServiceResolved: host=${resolved.host?.hostAddress} port=${resolved.port}"
                        record(line)
                        onResult(line)
                    }
                },
            )
        } catch (e: Exception) {
            val line = "resolve threw: ${e.javaClass.simpleName}: ${e.message}"
            record(line)
            onResult(line)
        }
    }

    /** Stops discovery if one is running. Safe to call when nothing is running. */
    fun stop() {
        val listener = activeListener ?: return
        activeListener = null
        try {
            nsdManager.stopServiceDiscovery(listener)
            record("stopServiceDiscovery called")
        } catch (e: Exception) {
            record("stopServiceDiscovery threw: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * Turns an NsdManager error code into its name.
     *
     * The numbers are meaningless in a log on their own, and a report that says "errorCode=3" makes
     * the next person look it up. The constant names are the API's own.
     */
    private fun describe(code: Int): String = when (code) {
        NsdManager.FAILURE_ALREADY_ACTIVE -> "FAILURE_ALREADY_ACTIVE"
        NsdManager.FAILURE_INTERNAL_ERROR -> "FAILURE_INTERNAL_ERROR"
        NsdManager.FAILURE_MAX_LIMIT -> "FAILURE_MAX_LIMIT"
        else -> "unknown code"
    }
}
