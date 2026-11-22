package com.droidbridge.core.pairing

/**
 * Phone-side pairing state (Phase 2, `NETWORK_PROTOCOL.md` §3).
 *
 * This is deliberately a pure state machine with no Android or socket types: the whole point is that
 * the rules which decide when a peer becomes trusted can be tested without a network, a device or a
 * UI. The service drives it; the UI renders it.
 *
 * The rule that matters most is in [Phase.CONFIRMING]: a session exists and TLS has completed, but
 * the peer is **not** trusted yet and no application data may move until the user has compared the
 * code on both screens. Encoding that as a state (rather than a boolean passed around the UI) is what
 * stops a later refactor from sending clipboard content on an unconfirmed session.
 */
object Pairing {

    /** Where the pairing attempt currently is. */
    enum class Phase {
        /** Nothing happening. */
        IDLE,

        /** Looking for the Mac on the local network. */
        DISCOVERING,

        /** A peer was chosen; TLS is being established with the stored pin, or with none on first run. */
        CONNECTING,

        /** TLS is up, the code is on screen, and nothing may be sent until the user decides. */
        CONFIRMING,

        /** The peer is trusted and the session is usable. */
        CONNECTED,

        /** The connection dropped; a retry is scheduled. */
        RECONNECTING,

        /** This attempt ended and needs the user to act (or a code did not match). */
        FAILED,
    }

    /** Why a pairing attempt failed, in terms the UI can explain without leaking peer data. */
    enum class Failure {
        /** The peer's advertised protocol version is not one this app speaks. */
        INCOMPATIBLE_VERSION,

        /** TLS could not be established: refusal, timeout, or an unexpected protocol. */
        HANDSHAKE,

        /** The peer's pin did not match the stored pin — possible impersonation, never auto-accepted. */
        PIN_MISMATCH,

        /** The user said the codes did not match. */
        CODE_REJECTED,

        /** The session dropped and the retry budget was exhausted. */
        GAVE_UP,
    }

    /** Everything that can affect pairing, as data. */
    sealed interface Event {
        /** Discovery started. */
        data object DiscoveryStarted : Event

        /** The user chose a peer. [hasStoredPin] decides whether a code must be compared. */
        data class ConnectRequested(val hasStoredPin: Boolean) : Event

        /** TLS completed. [code] is shown to the user only when confirmation is required. */
        data class SessionEstablished(val requiresConfirmation: Boolean, val code: String?) : Event

        /** The user confirmed the codes match. */
        data object Confirmed : Event

        /** The user rejected the codes. */
        data object Rejected : Event

        /** The connection dropped. [attempt] is the retry number about to be scheduled. */
        data class Disconnected(val attempt: Int, val retryable: Boolean) : Event

        /** A retry began. */
        data object Retrying : Event

        /** The user or the app ended the session on purpose. */
        data object Stopped : Event

        /** Something failed for good. */
        data class Failed(val reason: Failure) : Event
    }

    /** The renderable state. [code] is only ever set while a comparison is pending. */
    data class State(
        val phase: Phase = Phase.IDLE,
        val code: String? = null,
        val failure: Failure? = null,
        val attempt: Int = 0,
    ) {
        /** True only when application data may move. */
        val canExchangeData: Boolean get() = phase == Phase.CONNECTED

        /** True when the user has a code to compare. */
        val awaitsUserDecision: Boolean get() = phase == Phase.CONFIRMING
    }

    /**
     * The transition function. Every accepted event is total; every rejected event returns the same
     * state unchanged, so a stray callback can never move the machine into a state the UI cannot
     * explain.
     */
    fun reduce(state: State, event: Event): State = when (event) {
        Event.DiscoveryStarted ->
            if (state.phase == Phase.CONNECTED || state.phase == Phase.CONFIRMING) state
            else State(phase = Phase.DISCOVERING)

        is Event.ConnectRequested -> when (state.phase) {
            Phase.DISCOVERING, Phase.FAILED, Phase.IDLE, Phase.RECONNECTING ->
                State(phase = Phase.CONNECTING, attempt = state.attempt)
            // A connect request must never interrupt a live or unconfirmed session.
            else -> state
        }

        is Event.SessionEstablished -> when (state.phase) {
            Phase.CONNECTING, Phase.RECONNECTING -> {
                if (event.requiresConfirmation) {
                    // A confirmation state without a code would be a UI dead end, and a code without
                    // confirmation would silently trust the peer. Neither is representable.
                    if (event.code.isNullOrBlank()) State(phase = Phase.FAILED, failure = Failure.HANDSHAKE)
                    else State(phase = Phase.CONFIRMING, code = event.code, attempt = state.attempt)
                } else {
                    State(phase = Phase.CONNECTED, attempt = 0)
                }
            }
            else -> state
        }

        Event.Confirmed -> when (state.phase) {
            // Only a pending comparison can be confirmed; "confirming" an already-connected session
            // would let a stale UI callback re-trust a peer.
            Phase.CONFIRMING -> State(phase = Phase.CONNECTED, attempt = 0)
            else -> state
        }

        Event.Rejected -> when (state.phase) {
            Phase.CONFIRMING -> State(phase = Phase.FAILED, failure = Failure.CODE_REJECTED)
            else -> state
        }

        is Event.Disconnected -> when (state.phase) {
            Phase.CONNECTED, Phase.CONFIRMING, Phase.CONNECTING, Phase.RECONNECTING -> {
                if (event.retryable) {
                    State(phase = Phase.RECONNECTING, attempt = event.attempt)
                } else {
                    State(phase = Phase.FAILED, failure = Failure.GAVE_UP)
                }
            }
            else -> state
        }

        Event.Retrying -> when (state.phase) {
            Phase.RECONNECTING -> State(phase = Phase.CONNECTING, attempt = state.attempt)
            else -> state
        }

        Event.Stopped -> State(phase = Phase.IDLE)

        is Event.Failed -> State(phase = Phase.FAILED, failure = event.reason)
    }
}
