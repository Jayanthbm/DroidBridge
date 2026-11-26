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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.channels.Channel
import kotlin.coroutines.coroutineContext
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
 * * **The retry budget outlasts the Mac's reap window.** The Mac closes a silent session after
 *   120 s (ADR-051); a phone that gave up at ~60 s sat in a dead zone until the user tapped
 *   again. Ten attempts with the 30 s backoff cap span roughly 150 s, so a reconnect after a
 *   reboot gap lands inside the phone's own patience, and supersede (ADR-049) makes it a
 *   first-try success.
 * * **A suspended phone does not spend that budget.** Attempts made while the device cannot reach
 *   the network are doomed before they start, so [retryGate] holds the loop until it can and only
 *   then charges an attempt (ADR-061). Without it an ordinary screen-off exhausted the budget in
 *   ~150 s and left a terminal give-up for a Mac that had never left.
 */

/** See [SessionController.messageTarget]: the routing contract is pinned by a unit test. */
internal enum class MessageTarget { CLIPBOARD, MIRROR }

class SessionController(
    private val identity: DeviceIdentity,
    private val pinStore: PeerPinning.PinStore,
    private val discovery: ServiceDiscovery,
    private val scope: CoroutineScope,
    private val pingIntervalMs: Long = 15_000,
    private val maxAttempts: Int = 10,
    private val backoff: BackoffPolicy = BackoffPolicy(),
    /**
     * Held before an attempt is charged to the budget, so time the phone cannot reach the network
     * costs nothing. Null means "always attempt", which is what the tests and the reuse cases want.
     */
    private val retryGate: RetryGate? = null,
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

        /**
         * Which feature hook a session message belongs to. Pure so a unit test can pin the
         * contract: the 2026-11-24 regression had clipboard types flowing into the mirroring
         * hook (which ignores them) and nothing failed at build or test time — only a human
         * watching a dead clipboard card caught it. Keep every message type routed explicitly.
         */
        internal fun messageTarget(type: ControlMessage.Type): MessageTarget? = when (type) {
            ControlMessage.Type.CLIPBOARD_OFFER,
            ControlMessage.Type.CLIPBOARD_TEXT,
            ControlMessage.Type.CLIPBOARD_ACCEPT,
            ControlMessage.Type.CLIPBOARD_ACK,
            ControlMessage.Type.CLIPBOARD_REJECT,
            ControlMessage.Type.CLIPBOARD_PAUSE -> MessageTarget.CLIPBOARD
            ControlMessage.Type.MIRROR_KEYFRAME_REQUEST,
            ControlMessage.Type.MIRROR_STOP,
            ControlMessage.Type.MIRROR_STARTED,
            ControlMessage.Type.MIRROR_FORMAT -> MessageTarget.MIRROR
            // Keepalive and lifecycle types are interpreted by the keepalive loop itself.
            else -> null
        }

        /**
         * The peer a system-picker response should connect to, or null when nothing should.
         *
         * Only a compatible peer, and only while discovery is still what is happening: after a drop
         * or a stop the UI owns the decision, and an in-flight session must never be replaced by a
         * later entry in the same list. `DISCOVERING` is the normal case - `startDiscovery` moves the
         * state there before the picker opens. Pure so the guard is testable without a network.
         */
        internal fun pickAutoConnectPeer(
            found: List<ServiceDiscovery.Peer>,
            phase: Phase,
        ): ServiceDiscovery.Peer? =
            if (phase == Phase.IDLE || phase == Phase.DISCOVERING) {
                found.firstOrNull { it.isCompatible }
            } else {
                null
            }
    }

    private val stateFlow = MutableStateFlow(Pairing.State())
    val state: StateFlow<Pairing.State> = stateFlow.asStateFlow()

    /**
     * True between a system-picker request and the first peer list it delivers.
     *
     * Only the picker arms it: direct discovery lists every Mac on the network, so connecting to the
     * first one there would pick a host the user never chose. Cleared by the first peer list and by
     * [stop], so an explicit disconnect is not undone by a stale request.
     */
    @Volatile private var awaitingPickerPeer = false

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

    /**
     * The live session's short authentication code, or null with no session. Shown while CONNECTED
     * so a one-sided re-pair (the Mac forgot the pin, the phone did not) still has a code to
     * compare; the code is derived from public keys and is not a secret (ADR-011).
     */
    @Volatile var liveSessionCode: String? = null
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

    /**
     * Which feature hook a session message belongs to. Pure so a unit test can pin the contract:
     * the 2026-11-24 regression had clipboard types flowing into the mirroring hook (which ignores
     * them) and nothing failed at build or test time — only a human watching a dead clipboard card
     * caught it. Keep every new message type routed here explicitly.
     */
    /** Called whenever the pairing state changes, so feature UIs can refresh without polling. */
    @Volatile var onStateChanged: (() -> Unit)? = null

    /**
     * One frame waiting to be written. A queue rather than a direct write because callers are UI
     * handlers and other feature threads, and a socket write from the main thread throws
     * `NetworkOnMainThreadException` — measured here, where it silently swallowed the clipboard ack.
     */
    private class Outbound(val type: ControlMessage.Type, val payload: ByteArray)

    /**
     * Bounded so a stalled writer cannot grow without limit, but large enough that a burst of media
     * frames never blocks a caller. Media that cannot be written is worthless anyway.
     */
    private val outbox = Channel<Outbound>(capacity = 256)

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
        while (coroutineContext.isActive) {
            val frame = outbox.receiveCatching().getOrNull() ?: return
            val live = session
            if (live == null || !live.isAuthenticated) {
                // No session: a media frame is worthless, so dropping it is honest. Control frames are
                // one-off state changes (format, stop) and are re-sent on the next session anyway.
                continue
            }
            try {
                live.send(frame.type, frame.payload)
            } catch (e: Exception) {
                // Keep draining. Returning here left the queue with no consumer after a reconnect, so
                // every later send looked successful while nothing was written — measured as a phone
                // that kept encoding and a Mac that received only pings.
                Log.i(TAG, "send ${frame.type} failed: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun update(event: Event) {
        val next = Pairing.reduce(stateFlow.value, event)
        if (next != stateFlow.value) {
            // Every transition into CONNECTED is a session features must re-establish, whether it was
            // reached by confirming a code or by reconnecting on a stored pin. Bumping only on the
            // pairing path left automatic reconnects sharing a generation with the dead session, so
            // mirroring never re-announced its format and the Mac silently dropped every frame.
            if (next.phase == Phase.CONNECTED && stateFlow.value.phase != Phase.CONNECTED) {
                generation += 1
            }
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
        // Arm the picker's auto-connect before the request: the listener can fire before
        // startWithPicker() returns.
        awaitingPickerPeer = true
        val usedPicker = discovery.startWithPicker { found -> onPeersChanged(found) }
        if (!usedPicker) {
            awaitingPickerPeer = false
            discovery.start { found -> onPeersChanged(found) }
        }
    }

    private fun onPeersChanged(found: List<ServiceDiscovery.Peer>) {
        // The list is published before anything else happens to it: the dashboard renders from it,
        // and the first version returned early after connecting, so a connected app still said
        // "no Mac found yet".
        peersFlow.value = found

        // The system picker already asked the user which Mac to use; the dashboard then made them
        // press Connect too. The grant is the decision, so the first compatible peer it returns is
        // connected to without a second tap - and the arming flag is cleared so a later peer from the
        // same request can never start a competing session.
        if (awaitingPickerPeer) {
            awaitingPickerPeer = false
            val picked = pickAutoConnectPeer(found, stateFlow.value.phase)
            if (picked != null) {
                Log.i(TAG, "auto-connecting to the peer the system picker returned: ${picked.key}")
                connect(picked)
                return
            }
            Log.i(TAG, "picker returned ${found.size} peer(s), none auto-connectable now")
        }

        // A peer disappearing while we believe we are connected is a drop, not a detail.
        val current = selected
        if (current != null && found.none { it.key == current.key } && stateFlow.value.phase == Phase.CONNECTED) {
            Log.i(TAG, "selected peer vanished from discovery; treating as a drop")
            session?.close("peer lost")
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
        liveSessionCode = null
        update(Event.Rejected)
        Log.i(TAG, "PAIRING_REJECTED")
    }

    /** Stops everything: session, discovery, retries. */
    fun stop() {
        // A request still in flight must not reconnect after the user said stop.
        awaitingPickerPeer = false
        worker?.cancel()
        worker = null
        session?.close("stopped by app")
        session = null
        liveSessionCode = null
        discovery.stop()
        update(Event.Stopped)
    }

    /**
     * Revokes pairing: drops the session (if any) and clears every stored Mac pin, so the next
     * connection is a first pairing again — the code must be compared and confirmed by a human.
     * Pins are keyed by peer id; the phone pairs with one Mac in practice, but revocation must not
     * depend on a session being live to know which id to forget, so every stored pin goes.
     */
    fun forgetPinnedPeers() {
        stop()
        val forgotten = pinStore.snapshot().keys
        forgotten.forEach { pinStore.forget(it) }
        Log.i(TAG, "pairing revoked for ${forgotten.size} peer(s)")
        onStateChanged?.invoke()
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
        // A streak of failures, not a lifetime count: a successful handshake on a stored pin resets
        // it, so a peer that keeps coming back can never exhaust the budget (see RetryCounter).
        val attempts = RetryCounter(maxAttempts)
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
                Log.e(TAG, "connect attempt=${attempts.nextAttempt()} failed: ${e.javaClass.simpleName}: ${e.message}")
                if (failure == Failure.PIN_MISMATCH) {
                    // A pin mismatch is never retried: retrying an impersonation attempt only gives
                    // it more chances, and the user must decide.
                    update(Event.Failed(failure))
                    return
                }
                awaitRetryWindow()
                val withinBudget = attempts.charge()
                if (!withinBudget) {
                    update(Event.Disconnected(attempts.failures, retryable = false))
                    return
                }
                update(Event.Disconnected(attempts.failures, retryable = true))
                delay(backoff.delayFor(attempts.failures))
                update(Event.Retrying)
                continue
            }

            session = opened
            // Any successful handshake proves the peer is reachable, including a silent stored-pin
            // reconnect. Resetting only after a fresh confirmation made the budget a lifetime count
            // and killed long sessions after 10 successful drops (see RetryCounter).
            attempts.reset()
            roundTrips = 0
            lastRttMs = -1
            // The code travels with the session either way: when the phone authenticated the Mac
            // but the Mac has not yet confirmed the phone (a one-sided forget — measured
            // 2026-11-24), the phone shows CONNECTED with no code while the Mac displays one and
            // waits. The code is derived from public keys (not a secret), so displaying the
            // phone's own while connected gives the human something to compare against the Mac's
            // confirmation dialog.
            liveSessionCode = opened.shortAuthenticationCode(identity)
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
                // Nothing may move until the user decides, but the socket is still watched: a Mac
                // that goes away must not leave the phone on a code that can never be compared.
                val confirmed = awaitConfirmation(opened)
                if (!confirmed) {
                    runCatching { opened.close("pairing did not complete") }
                    if (session === opened) session = null
                    liveSessionCode = null
                    // A rejection or a stop has already said what happened, in its own words.
                    if (stateFlow.value.phase == Phase.CONFIRMING) {
                        update(Event.Failed(Failure.HANDSHAKE))
                    }
                    Log.i(TAG, "pairing did not complete; nothing pinned")
                    return
                }
            }

            val ended = keepAlive(opened)
            runCatching { opened.close(ended) }
            if (session === opened) session = null
            liveSessionCode = null

            // A deliberate stop must not schedule a retry.
            if (stateFlow.value.phase == Phase.IDLE) return

            awaitRetryWindow()
            val withinBudget = attempts.charge()
            if (!withinBudget) {
                Log.i(TAG, "giving up after ${attempts.failures} consecutive attempts (last: $ended)")
                update(Event.Disconnected(attempts.failures, retryable = false))
                return
            }
            update(Event.Disconnected(attempts.failures, retryable = true))
            Log.i(TAG, "reconnect scheduled attempt=${attempts.failures} reason=$ended")
            delay(backoff.delayFor(attempts.failures))
            update(Event.Retrying)
        }
    }

    /**
     * Waits for the user to compare the code, watching the socket while it waits.
     *
     * @return true when the session is authenticated; false when the peer went away or the session
     *   closed, in which case nothing is pinned
     */
    private suspend fun awaitConfirmation(opened: SecureSession): Boolean {
        // The user decides out-of-band (UI thread); this waits without holding a lock, but it does
        // read the socket: a peer that goes away must not leave the phone on a code that can never
        // be compared (measured 2026-11-26 - the peer reset 39 s after the code appeared, the phone
        // stayed on CONFIRMING, and confirming then reported an authenticated session with no
        // socket behind it).
        while (scope.isActive && opened.state == SecureSession.State.AWAITING_CONFIRMATION) {
            val alive = runCatching { opened.peerStillConnected(200) }.getOrElse { false }
            if (!alive) return false
        }
        return opened.isAuthenticated
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
                    else -> {
                        // Logged because this path is otherwise invisible: when a keyframe request was
                        // dropped here the Mac kept flushing while the phone kept sending P-frames, and
                        // nothing in either log said why.
                        Log.i(TAG, "session message ${message.type} target=${messageTarget(message.type)}")
                        when (messageTarget(message.type)) {
                            MessageTarget.CLIPBOARD ->
                                // Clipboard types belong to the clipboard feature. The Phase 4 hook
                                // addition routed them into the mirroring callback, which ignores
                                // them — Mac→phone clipboard receive then silently died (the card
                                // stayed idle and no ack was ever sent) while the log claimed the
                                // message had a handler.
                                onClipboardMessage?.invoke(message.type, message.payload)
                            MessageTarget.MIRROR ->
                                onMirrorMessage?.invoke(message.type, message.payload)
                            null -> Unit
                        }
                    }
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

    /**
     * Waits until a retry has a chance, before the attempt is charged to the budget.
     *
     * The order matters: charging first is what turned a screen-off into a terminal failure, because
     * ten attempts that never left the device exhausted the same budget that exists to ride out a
     * reboot gap. After the wait the whole budget is still available, so the first attempt after the
     * screen comes back is attempt 1 and succeeds.
     */
    private suspend fun awaitRetryWindow() {
        val gate = retryGate ?: return
        Log.i(TAG, "holding the retry budget until the device can reach the network")
        gate.awaitUsable()
        Log.i(TAG, "device usable again; retrying with the budget intact")
    }

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
