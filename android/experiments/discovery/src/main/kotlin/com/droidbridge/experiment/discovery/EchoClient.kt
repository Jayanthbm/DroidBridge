package com.droidbridge.experiment.discovery

import android.util.Log
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * P02 A — sustained duplex echo over the picker-granted connection.
 *
 * The Mac's `ServiceAdvertiser` echoes every byte it receives, so a line sent from Android comes
 * back verbatim. That is what makes this a duplex test rather than a write test: the reply proves
 * the return path over the same socket.
 *
 * The payload is sequence number plus send timestamp. Matching the echoed line exactly detects
 * corruption and reordering; the timestamp yields a round-trip latency without needing a clock
 * shared with the Mac (the phone's own monotonic clock is enough).
 *
 * ## Recovery
 *
 * When the socket fails, the client throws the endpoint away and re-runs
 * `FLAG_USER_APPROVED_ONLY` discovery, then resolves again. That is the recovery path the product
 * needs: no picker, no re-pairing, because the user already approved this Mac. If re-discovery
 * finds nothing (Wi-Fi still down), it retries after a short delay until the run's deadline.
 *
 * Runs on its own thread. It never runs on the main thread and never blocks the UI.
 */
class EchoClient(
    private val probe: DiscoveryProbe,
    private val log: (String) -> Unit = { Log.i(TAG, it) },
) {

    companion object {
        private const val TAG = "P02Echo"
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 3_000
        private const val SEND_INTERVAL_MS = 250L
        private const val REDISCOVERY_TIMEOUT_MS = 20_000L
        private const val RETRY_DELAY_MS = 1_500L
    }

    val sent = AtomicInteger()
    val received = AtomicInteger()
    val mismatches = AtomicInteger()
    val errors = AtomicInteger()
    val connectFailures = AtomicInteger()
    val connectCount = AtomicInteger()
    val rttLastUs = AtomicLong(0)
    val rttMinUs = AtomicLong(Long.MAX_VALUE)
    val rttMaxUs = AtomicLong(0)
    val rttSumUs = AtomicLong(0)

    @Volatile
    private var running = false

    @Volatile
    private var socket: Socket? = null

    private var worker: Thread? = null

    val isRunning: Boolean get() = running

    fun start(seconds: Int, initialEndpoint: Pair<InetAddress, Int>? = null) {
        if (running) {
            log("ECHO already running")
            return
        }
        reset()
        running = true
        probe.stop()
        worker = Thread({ runSession(seconds, initialEndpoint) }, "p02-echo").also {
            it.isDaemon = true
            it.start()
        }
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        socket = null
    }

    private fun reset() {
        sent.set(0); received.set(0); mismatches.set(0); errors.set(0)
        connectFailures.set(0); connectCount.set(0)
        rttLastUs.set(0); rttMinUs.set(Long.MAX_VALUE); rttMaxUs.set(0); rttSumUs.set(0)
    }

    private fun runSession(seconds: Int, initialEndpoint: Pair<InetAddress, Int>?) {
        val startedAt = System.currentTimeMillis()
        val deadline = startedAt + seconds * 1000L
        var endpoint = initialEndpoint
        var connected = false
        var sequence = 0
        var lastProgress = 0L
        var writer: BufferedWriter? = null
        var reader: BufferedReader? = null

        log("ECHO start seconds=$seconds initial=${endpoint?.first?.hostAddress}:${endpoint?.second}")
        while (running && System.currentTimeMillis() < deadline) {
            if (!connected) {
                if (endpoint == null) {
                    endpoint = probe.rediscoverApprovedBlocking(REDISCOVERY_TIMEOUT_MS)
                    if (endpoint == null) {
                        connectFailures.incrementAndGet()
                        log("ECHO rediscovery found no endpoint; retrying")
                        sleep(RETRY_DELAY_MS)
                        continue
                    }
                    if (!running) break
                }
                try {
                    val s = Socket()
                    s.connect(InetSocketAddress(endpoint.first, endpoint.second), CONNECT_TIMEOUT_MS)
                    s.soTimeout = READ_TIMEOUT_MS
                    s.tcpNoDelay = true
                    socket = s
                    writer = BufferedWriter(OutputStreamWriter(s.getOutputStream()))
                    reader = BufferedReader(InputStreamReader(s.getInputStream()))
                    connected = true
                    connectCount.incrementAndGet()
                    log("ECHO connected to ${endpoint.first.hostAddress}:${endpoint.second}")
                } catch (e: Exception) {
                    connectFailures.incrementAndGet()
                    log("ECHO connect failed: ${e.javaClass.simpleName}: ${e.message}")
                    runCatching { socket?.close() }
                    socket = null
                    endpoint = null
                    sleep(RETRY_DELAY_MS)
                    continue
                }
            }

            val s = socket
            if (s == null) {
                connected = false
                continue
            }

            try {
                val out = writer ?: throw IOException("no writer")
                val input = reader ?: throw IOException("no reader")
                sequence++
                val nano = System.nanoTime()
                val payload = "P02 $sequence $nano"
                out.write(payload)
                out.write("\n")
                out.flush()
                sent.incrementAndGet()

                val reply = input.readLine()
                if (reply == null) throw IOException("peer closed connection")
                val rttUs = (System.nanoTime() - nano) / 1000
                if (reply == payload) {
                    received.incrementAndGet()
                    rttLastUs.set(rttUs)
                    rttSumUs.addAndGet(rttUs)
                    if (rttUs < rttMinUs.get()) rttMinUs.set(rttUs)
                    if (rttUs > rttMaxUs.get()) rttMaxUs.set(rttUs)
                } else {
                    mismatches.incrementAndGet()
                    log("ECHO mismatch: sent='$payload' got='${reply.take(120)}'")
                }
            } catch (e: Exception) {
                errors.incrementAndGet()
                log("ECHO disconnected: ${e.javaClass.simpleName}: ${e.message}")
                connected = false
                runCatching { socket?.close() }
                socket = null
                writer = null
                reader = null
                // Force a fresh approved-only discovery: the Mac may have moved to a new address
                // or the old socket may belong to a network that no longer exists.
                endpoint = null
            }

            val now = System.currentTimeMillis()
            if (now - lastProgress >= 5_000) {
                lastProgress = now
                log(
                    "ECHO progress sent=${sent.get()} received=${received.get()} " +
                        "mismatches=${mismatches.get()} errors=${errors.get()} " +
                        "rttLastUs=${rttLastUs.get()} connects=${connectCount.get()} " +
                        "elapsedMs=${now - startedAt}",
                )
            }
            sleep(SEND_INTERVAL_MS)
        }

        running = false
        runCatching { socket?.close() }
        socket = null
        val duration = System.currentTimeMillis() - startedAt
        val average = if (received.get() > 0) rttSumUs.get() / received.get() else 0
        log(
            "ECHO_SUMMARY seconds=$seconds durationMs=$duration sent=${sent.get()} " +
                "received=${received.get()} mismatches=${mismatches.get()} errors=${errors.get()} " +
                "connectFailures=${connectFailures.get()} connects=${connectCount.get()} " +
                "rttLastUs=${rttLastUs.get()} rttMinUs=${if (received.get() > 0) rttMinUs.get() else 0} " +
                "rttMaxUs=${rttMaxUs.get()} rttAvgUs=$average",
        )
    }

    private fun sleep(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (_: InterruptedException) {
            running = false
        }
    }
}
