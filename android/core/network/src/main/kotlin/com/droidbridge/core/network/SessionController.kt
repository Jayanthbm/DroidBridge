package com.droidbridge.core.network

import android.util.Log
import com.droidbridge.core.pairing.Pairing
import com.droidbridge.core.pairing.Pairing.Event
import com.droidbridge.core.pairing.Pairing.Failure
import com.droidbridge.core.pairing.Pairing.Phase
import com.droidbridge.core.pairing.Pairing.State
import com.droidbridge.core.security.DeviceIdentity
import com.droidbridge.core.security.PeerPinning
import com.droidbridge.protocol.session.ControlMessage
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Phase 2's Android session owner: discover, pair, keep alive, and reconnect.
 *
 * The interesting decisions are all delegated to things that can be tested without a network — the
 * pairing rules live in [Pairing], the retry timing in [BackoffPolicy] — so this class is only the
 * part that has to touch a socket. What is left here is the policy the protocol actually constrains:
 *
 * * **A stale socket is not "connected".** Liveness is decided by a ping round trip, never by
 *   `isConnected` on a socket that may have been dead for minutes. Two unanswered intervals demote
 *   the session to reconnecting, which is what stops the UI from claiming a link that Doze already
 *   killed.
 * * **Reconnect re-resolves the peer** through [ServiceDiscovery] rather than dialling the last IP.
 *   The protocol forbids the cached address, and a phone that roamed between APs would otherwise
 *   retry a dead address forever.
 * * **The pin decides whether a code is shown.** A stored pin that matched inside the TLS handshake
 *   skips the prompt; anything else requires the user to compare the code.
 * * **Discovery never stops being live.** Service-lost events are forwarded into the state machine so
 *   a vanished Mac shows up as a drop rather than as silence.
 */
class SessionController(
    private val identity: DeviceIdentity,
    private val pinStore: PeerPinning.PinStore,
    private val discovery: ServiceDiscovery,
    private val scope: CoroutineScope,
    private val pingIntervalMs: Long = 15_000,
    private val maxAttempts: Int = 6,
    private val backoff: BackoffPolicy = BackoffPolicy(),
    /**
     * Where socket work runs. [Dispatchers.IO] by default because `SecureSession.connect` blocks:
     * a caller that passes a main-thread scope gets `NetworkOnMainThreadException` and every attempt
     * looks like a transport failure. That was a real bug here — the connect attempts failed with
     * exactly that exception while `curl` from the same device reached the same port.
     */
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    companion object {
        private const val TAG = "P03Session"
    }

    private val stateFlow = MutableStateFlow(Pairing.State())
    val state: StateFlow<Pairing.State> = stateFlow.asStateFlow()

    private val peersFlow = MutableStateFlow<List<ServiceDiscovery.Peer>>(emptyList())
    val peers: StateFlow<List<ServiceDiscovery.Peer>> = peersFlow.asStateFlow()

    /** How discovery is going, so the UI can distinguish "refused" from "nothing found". */
    val discoveryStatus: StateFlow<ServiceDiscovery.Status> get() = discovery.status

    /** The live session, if any. Only authenticated sessions are ever exposed here. */
    @Volatile private var session: SecureSession? = null

    @Volatile private var selected: ServiceDiscovery.Peer? = null
    private var worker: Job? = null

    /** Round trips completed on the current session, for the UI and for evidence. */
    @Volatile var roundTrips: Int = 0
        private set

    /** Last measured ping round-trip time, in milliseconds. */
    @Volatile var lastRttMs: Long = -1
        private set

    /** Increases each time a session authenticates, so features can re-establish their state. */
    @Volatile private var generation = 0L

    /** Current authenticated-session generation. */
    val sessionGeneration: Long get() = generation

    @Volatile var confirmationCompleted: Boolean = false
        private set

    /**
     * Receives clipboard control messages. Set by the clipboard feature; the controller does not know
     * what they mean, which keeps the transport free of clipboard policy.
     */
    @Volatile var onClipboardMessage: ((ControlMessage.Type, ByteArray) -> Unit)? = null

    /**
     * Receives mirroring control frames. The session owner is the only reader of the socket, so a
     * feature that needs `mirror.keyframe_request` subscribes here instead of reading it itself.
     */
    @Volatile var onMirrorMessage: ((ControlMessage.Type, ByteArray) -> Unit)? = null

    /** Called whenever the pairing state changes, so feature UIs can refresh without polling. */
    @Volatile var onStateChanged: (() -> Unit)? = null

    /**
     * One frame waiting to be written. A queue rather than a direct write because callers are UI
     * handlers and other feature threads, and a socket write from the main thread throws
     * `NetworkOnMainThreadException` — measured here, where it silently swallowed the clipboard ack.
     */
    private class Outbound(val type: ControlMessage.Type, val payload: ByteArray)

    /** Unbounded so a send from a UI thread can never block; drained in order by one writer. */
    private val outbox = Channel<Outbound>(Channel.UNLIMITED)

    /**
     * Sends one control frame on the live session.
     *
     * The frame is queued and written by [outboxWriter], so this is safe to call from the main
     * thread and preserves the order the caller produced. Returns false when there is no
     * authenticated session, so a caller can report "not connected" instead of believing a copy was
     * delivered; a write failure after the queue accepts it is logged and ends the session.
     */
    fun sendControl(type: ControlMessage.Type, payload: ByteArray = ByteArray(0)): Boolean {
        val live = session ?: return false
        if (!live.isAuthenticated) return false
        return outbox.trySend(Outbound(type, payload)).isSuccess
    }

    /**
     * The single writer. One coroutine owns the socket's write side, which is also what keeps
     * clipboard offer/body pairs in the order they were produced.
     */
    private suspend fun outboxWriter() {
        for (frame in outbox) {
            val live = session ?: continue
            try {
                live.send(frame.type, frame.payload)
            } catch (e: Exception) {
                Log.i(TAG, "send ${frame.type} failed: ${e.javaClass.simpleName}")
                // A dead socket is a reconnect, not a lost message: let the keepalive observe it
                // rather than pretending the frame was delivered.
                return
            }
        }
    }

    private fun update(event: Event) {
        val next = Pairing.reduce(stateFlow.value, event)
        if (next != stateFlow.value) {
            stateFlow.value = next
            onStateChanged?.invoke()
            Log.i(TAG, "state=${next.phase} attempt=${next.attempt} code=${next.code ?: "none"} failure=${next.failure ?: "none"}")
        }
    }

    /**
     * Starts discovery; the UI calls this when the screen appears.
     *
     * On API 37 the system picker is used (ADR-010) because it is the path that does not need the
     * broad LAN permission; below 37 the direct browse that P02 measured is used. Both feed the same
     * peer list, so nothing downstream cares which one ran.
     */
    fun startDiscovery() {
        update(Event.DiscoveryStarted)
        val usedPicker = discovery.startWithPicker { found -> onPeersChanged(found) }
        if (!usedPicker) {
            discovery.start { found -> onPeersChanged(found) }
        }
    }

    private fun onPeersChanged(found: List<ServiceDiscovery.Peer>) {
        run {
            peersFlow.value = found
            // A peer disappearing while we believe we are connected is a drop, not a detail.
            val current = selected
            if (current != null && found.none { it.key == current.key } && stateFlow.value.phase == Phase.CONNECTED) {
                Log.i(TAG, "selected peer vanished from discovery; treating as a drop")
                session?.close("peer lost")
            }
        }
    }

    /** Connects to [peer]. On a first pairing this stops at the code comparison. */
    fun connect(peer: ServiceDiscovery.Peer) {
        if (!peer.isCompatible) {
            Log.e(TAG, "refusing incompatible peer pv=${peer.protocolVersion}")
            update(Event.Failed(Failure.INCOMPATIBLE_VERSION))
            return
        }
        selected = peer
        update(Event.ConnectRequested(hasStoredPin = storedPin(peer) != null))
        worker?.cancel()
        worker = scope.launch(dispatcher) {
            // The writer runs for as long as this session attempt does: it owns the socket's write
            // side so no caller thread ever touches it.
            val writer = launch { outboxWriter() }
            try {
                runSession(peer)
            } finally {
                writer.cancel()
            }
        }
    }

    /** The user compared the codes and they matched. */
    fun confirmPairing() {
        val live = session ?: return
        val code = stateFlow.value.code ?: return
        if (stateFlow.value.phase != Phase.CONFIRMING) return
        try {
            live.authenticate()
            // Only now is the peer trusted enough to pin: a rejected code must leave no pin behind.
            pinStore.remember(peerIdFor(selected), live.peerPin)
            confirmationCompleted = true
            generation += 1
            update(Event.Confirmed)
            Log.i(TAG, "PAIRING_CONFIRMED code=$code")
        } catch (e: Exception) {
            Log.e(TAG, "confirm failed: ${e.javaClass.simpleName}: ${e.message}")
            update(Event.Failed(Failure.HANDSHAKE))
        }
    }

    /** The user said the codes did not match. The session is closed and nothing is pinned. */
    fun rejectPairing() {
        session?.close("pairing rejected")
        session = null
        update(Event.Rejected)
        Log.i(TAG, "PAIRING_REJECTED")
    }

    /** Stops everything: session, discovery, retries. */
    fun stop() {
        worker?.cancel()
        worker = null
        session?.close("stopped by app")
        session = null
        discovery.stop()
        update(Event.Stopped)
    }

    /**
     * Tears down the current session but keeps the controller usable (used to prove reconnect).
     *
     * This deliberately does **not** schedule a reconnect itself. Closing the socket makes the
     * keepalive loop observe the failure and take the single reconnect path; scheduling a retry here
     * as well produced two workers racing, and the second one cancelled the first — the reconnect
     * appeared to succeed and then die immediately, twice per drop.
     */
    fun dropForTest() {
        Log.i(TAG, "drop requested by app")
        session?.close("dropped for reconnect test")
    }

    private fun storedPin(peer: ServiceDiscovery.Peer): String? = pinStore.pinFor(peerIdFor(peer))

    /**
     * The pin store is keyed by a peer identifier. The service name is used rather than the address,
     * because the address changes across networks while the Mac keeps its identity.
     */
    private fun peerIdFor(peer: ServiceDiscovery.Peer?): String = peer?.key ?: "unknown"

    /**
     * One coroutine owns the whole connection lifecycle: connect, keep alive, and retry.
     *
     * There is exactly one loop and therefore exactly one retry path. An earlier version launched a
     * new worker from inside the failure handler while the old one was still unwinding, so a single
     * drop produced two overlapping reconnects and the newer coroutine was cancelled by the older
     * one's cleanup.
     */
    private suspend fun runSession(peer: ServiceDiscovery.Peer) {
        var attempt = 0
        while (scope.isActive) {
            val expectedPin = storedPin(peer)
            val opened = try {
                // A fresh resolve every attempt: the protocol forbids dialling a remembered address.
                val fresh = resolveFresh(peer) ?: peer
                SecureSession.connect(
                    host = fresh.host,
                    port = fresh.port,
                    identity = identity,
                    expectedPin = expectedPin,
                )
            } catch (e: Exception) {
                val failure = classify(e)
                Log.e(TAG, "connect attempt=${attempt + 1} failed: ${e.javaClass.simpleName}: ${e.message}")
                if (failure == Failure.PIN_MISMATCH) {
                    // A pin mismatch is never retried: retrying an impersonation attempt only gives
                    // it more chances, and the user must decide.
                    update(Event.Failed(failure))
                    return
                }
                attempt += 1
                if (!retryable(attempt)) {
                    update(Event.Disconnected(attempt, retryable = false))
                    return
                }
                update(Event.Disconnected(attempt, retryable = true))
                delay(backoff.delayFor(attempt))
                update(Event.Retrying)
                continue
            }

            session = opened
            roundTrips = 0
            lastRttMs = -1
            // The flag is "does the user still have to compare a code?", so it is true exactly when
            // the session is NOT already authenticated. Getting this backwards reports an unconfirmed
            // peer as connected, which is the one mistake this design exists to prevent.
            val requiresConfirmation = opened.state != SecureSession.State.AUTHENTICATED
            update(
                Event.SessionEstablished(
                    requiresConfirmation = requiresConfirmation,
                    code = if (requiresConfirmation) opened.shortAuthenticationCode(identity) else null,
                )
            )
            Log.i(TAG, "established authenticated=${!requiresConfirmation} code=${if (requiresConfirmation) "pending" else "none"}")

            if (requiresConfirmation) {
                // Nothing may move until the user decides; the keepalive would only time out.
                awaitConfirmation(opened)
                if (!opened.isAuthenticated) {
                    runCatching { opened.close("pairing did not complete") }
                    if (session === opened) session = null
                    return
                }
                attempt = 0
            }

            val ended = keepAlive(opened)
            runCatching { opened.close(ended) }
            if (session === opened) session = null

            // A deliberate stop must not schedule a retry.
            if (stateFlow.value.phase == Phase.IDLE) return

            attempt += 1
            if (!retryable(attempt)) {
                Log.i(TAG, "giving up after $attempt attempts (last: $ended)")
                update(Event.Disconnected(attempt, retryable = false))
                return
            }
            update(Event.Disconnected(attempt, retryable = true))
            Log.i(TAG, "reconnect scheduled attempt=$attempt reason=$ended")
            delay(backoff.delayFor(attempt))
            update(Event.Retrying)
        }
    }

    private suspend fun awaitConfirmation(opened: SecureSession) {
        // The user decides out-of-band (UI thread); this just waits without holding a lock.
        while (scope.isActive && opened.state == SecureSession.State.AWAITING_CONFIRMATION) {
            delay(200)
        }
    }

    /**
     * Ping/pong keepalive. Returns the reason it stopped, so the caller has one place to decide what
     * a stop means. A pong must arrive within two intervals: a Doze-suspended radio produces exactly
     * a socket that still looks open while nothing comes back.
     */
    private suspend fun keepAlive(opened: SecureSession): String {
        var missed = 0
        while (scope.isActive && opened === session) {
            val sentAt = System.nanoTime()
            try {
                opened.send(ControlMessage.Type.PING, byteArrayOf(0x01))
            } catch (e: Exception) {
                return "ping failed: ${e.javaClass.simpleName}"
            }
            var answered = false
            val deadline = System.currentTimeMillis() + pingIntervalMs * 2
            while (System.currentTimeMillis() < deadline) {
                if (opened !== session) return "session replaced"
                val message = try {
                    opened.tryReceive(timeoutMs = 500)
                } catch (e: Exception) {
                    return "receive failed: ${e.javaClass.simpleName}"
                }
                if (message == null) continue
                when (message.type) {
                    ControlMessage.Type.PONG -> {
                        answered = true
                        roundTrips += 1
                        lastRttMs = (System.nanoTime() - sentAt) / 1_000_000
                        Log.i(TAG, "HEARTBEAT n=$roundTrips rttMs=$lastRttMs")
                        break
                    }
                    ControlMessage.Type.PING -> {
                        runCatching { opened.send(ControlMessage.Type.PONG, message.payload) }
                    }
                    ControlMessage.Type.CLOSE -> {
                        return "peer closed: ${String(message.payload)}"
                    }
                    ControlMessage.Type.CLIPBOARD_OFFER,
                    ControlMessage.Type.CLIPBOARD_TEXT,
                    ControlMessage.Type.CLIPBOARD_ACCEPT,
                    ControlMessage.Type.CLIPBOARD_ACK,
                    ControlMessage.Type.CLIPBOARD_REJECT,
                    ControlMessage.Type.MIRROR_KEYFRAME_REQUEST,
                    ControlMessage.Type.MIRROR_STOP,
                    ControlMessage.Type.MIRROR_STARTED,
                    ControlMessage.Type.MIRROR_FORMAT -> {
                        // Logged because this path is otherwise invisible: when a keyframe request was
                        // dropped here the Mac kept flushing while the phone kept sending P-frames, and
                        // nothing in either log said why.
                        Log.i(TAG, "mirror message ${message.type} handler=${onMirrorMessage != null}")
                        onMirrorMessage?.invoke(message.type, message.payload)
                    }
                    ControlMessage.Type.CLIPBOARD_PAUSE -> {
                        // Handed to the feature, which owns the clipboard rules. The keepalive loop
                        // must not interpret them: a clipboard pause is not a session event.
                        onClipboardMessage?.invoke(message.type, message.payload)
                    }
                    else -> Unit
                }
            }
            if (!answered) {
                missed += 1
                Log.i(TAG, "HEARTBEAT_MISSED n=$missed")
                if (missed >= 2) return "stale after $missed unanswered intervals"
            } else {
                missed = 0
            }
            delay(pingIntervalMs)
        }
        return if (opened === session) "controller stopped" else "session replaced"
    }

    private fun retryable(attempt: Int): Boolean = attempt <= maxAttempts

    /**
     * Re-resolves a peer so a reconnect dials the current address rather than the remembered one.
     * Returns null when discovery has not seen the peer again yet, in which case the last known
     * address is used as a last resort — and the attempt is allowed to fail normally.
     */
    private fun resolveFresh(peer: ServiceDiscovery.Peer): ServiceDiscovery.Peer? =
        peersFlow.value.firstOrNull { it.key == peer.key }

    /** Maps a transport failure onto the reason the UI can explain without guessing. */
    private fun classify(e: Exception): Failure = when {
        e.message?.contains("pin", ignoreCase = true) == true -> Failure.PIN_MISMATCH
        e is IOException -> Failure.HANDSHAKE
        else -> Failure.HANDSHAKE
    }
}
