package com.droidbridge.experiment.discovery

import android.net.nsd.DiscoveryRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
 * ## Reconnect support (added after the first S24 Ultra run)
 *
 * P02's remaining questions are about recovery, not first contact: after Wi-Fi drops, after the app
 * process is recreated, after a reboot. Those need a discovery that can be re-run and resolved
 * synchronously on a background thread, which is what `rediscoverApprovedBlocking()` provides.
 * It uses `FLAG_USER_APPROVED_ONLY`, the flag that avoids re-showing the picker for a peer the user
 * already approved.
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

    /** The last resolved endpoint, if any. */
    private val resolvedAddress = AtomicReference<InetAddress?>(null)
    private val resolvedPort = AtomicInteger(0)

    private val executor: Executor = Executors.newSingleThreadExecutor()

    /** Set once a discovery listener is registered, so it can be stopped exactly once. */
    private var activeListener: NsdManager.DiscoveryListener? = null

    fun events(): List<String> = synchronized(events) { events.toList() }

    fun lastService(): NsdServiceInfo? = lastService.get()

    fun resolvedEndpoint(): Pair<InetAddress, Int>? {
        val address = resolvedAddress.get() ?: return null
        val port = resolvedPort.get()
        if (port <= 0) return null
        return address to port
    }

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
        stopPreviousDiscovery()
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
                        "hostAddresses=${serviceInfo.hostAddresses} port=${serviceInfo.port}",
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
     *
     * @param onFound invoked on the NSD executor when a peer is reported. Reconnect uses it to
     *   wait for a peer instead of polling a mutable field.
     */
    fun startWithUserApprovedOnly(onFound: (() -> Unit)? = null) {
        stopPreviousDiscovery()
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
                onFound?.invoke()
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
                record("approved-only onStopDiscoveryFailed: errorCode=$errorCode (${describe(errorCode)})")
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
                        val address = resolved.hostAddresses.firstOrNull() ?: resolved.host
                        if (address != null && resolved.port > 0) {
                            resolvedAddress.set(address)
                            resolvedPort.set(resolved.port)
                        }
                        val line =
                            "onServiceResolved: host=${address?.hostAddress} port=${resolved.port}"
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

    /**
     * Resolves the last found service and waits for the answer.
     *
     * Runs on the caller's background thread; the NSD callbacks arrive on this probe's executor, so
     * the caller must not be that executor. Returns `null` on timeout or failure.
     */
    fun resolveLastBlocking(timeoutMs: Long): Pair<InetAddress, Int>? {
        val service = lastService.get() ?: return null
        resolvedAddress.set(null)
        resolvedPort.set(0)
        val latch = CountDownLatch(1)
        record("resolve-blocking: '${service.serviceName}'")
        try {
            nsdManager.resolveService(
                service,
                executor,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        record("resolve-blocking failed: errorCode=$errorCode (${describe(errorCode)})")
                        latch.countDown()
                    }

                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        val address = resolved.hostAddresses.firstOrNull() ?: resolved.host
                        if (address != null && resolved.port > 0) {
                            resolvedAddress.set(address)
                            resolvedPort.set(resolved.port)
                        }
                        record("resolve-blocking resolved: host=${address?.hostAddress} port=${resolved.port}")
                        latch.countDown()
                    }
                },
            )
        } catch (e: Exception) {
            record("resolve-blocking threw: ${e.javaClass.simpleName}: ${e.message}")
            return null
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            record("resolve-blocking timed out after ${timeoutMs}ms")
            return null
        }
        return resolvedEndpoint()
    }

    /**
     * Re-runs `FLAG_USER_APPROVED_ONLY` discovery, waits for a peer and resolves it.
     *
     * This is the recovery path P02 needs to test: it never shows the picker, so a reconnect does
     * not require the user to approve the Mac again. Returns `null` if no peer is found or
     * resolution fails within the deadline.
     */
    fun rediscoverApprovedBlocking(timeoutMs: Long): Pair<InetAddress, Int>? {
        record("reconnect: approved-only rediscovery started")
        lastService.set(null)
        resolvedAddress.set(null)
        resolvedPort.set(0)
        val found = CountDownLatch(1)
        startWithUserApprovedOnly { found.countDown() }
        if (!found.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            record("reconnect: no approved peer within ${timeoutMs}ms")
            stopPreviousDiscovery()
            return null
        }
        val endpoint = resolveLastBlocking(timeoutMs)
        stopPreviousDiscovery()
        record("reconnect: endpoint=${endpoint?.first?.hostAddress}:${endpoint?.second}")
        return endpoint
    }

    /**
     * Asks the platform whether this app may talk to a service.
     *
     * `checkPermissionForService` is the API's own answer to "is the grant still there?"; it is
     * deliberately separate from "does discovery still return results", because the two disagreed
     * on the first S24 Ultra run after `pm revoke`.
     */
    fun checkPermissionForService(serviceName: String, onResult: (String) -> Unit) {
        try {
            nsdManager.checkPermissionForService(
                SERVICE_TYPE,
                serviceName,
                executor,
            ) { state ->
                val name = when (state) {
                    NsdManager.SERVICE_PERMISSION_GRANTED -> "SERVICE_PERMISSION_GRANTED"
                    NsdManager.SERVICE_PERMISSION_DENIED -> "SERVICE_PERMISSION_DENIED"
                    else -> "unknown($state)"
                }
                val line = "checkPermissionForService('$serviceName')=$name ($state)"
                record(line)
                onResult(line)
            }
        } catch (e: Exception) {
            val line = "checkPermissionForService threw: ${e.javaClass.simpleName}: ${e.message}"
            record(line)
            onResult(line)
        }
    }

    /** Stops discovery if one is running. Safe to call when nothing is running. */
    fun stop() {
        stopPreviousDiscovery(alwaysLog = true)
    }

    private fun stopPreviousDiscovery(alwaysLog: Boolean = false) {
        val listener = activeListener ?: return
        activeListener = null
        try {
            nsdManager.stopServiceDiscovery(listener)
            if (alwaysLog) record("stopServiceDiscovery called")
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
        NsdManager.FAILURE_BAD_PARAMETERS -> "FAILURE_BAD_PARAMETERS"
        NsdManager.FAILURE_INTERNAL_ERROR -> "FAILURE_INTERNAL_ERROR"
        NsdManager.FAILURE_MAX_LIMIT -> "FAILURE_MAX_LIMIT"
        NsdManager.FAILURE_OPERATION_NOT_RUNNING -> "FAILURE_OPERATION_NOT_RUNNING"
        NsdManager.FAILURE_PERMISSION_DENIED -> "FAILURE_PERMISSION_DENIED"
        else -> "unknown code"
    }
}
