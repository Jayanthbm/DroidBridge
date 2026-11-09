package com.droidbridge.experiment.session

import java.security.cert.X509Certificate

/**
 * The peer trust decision (ADR-011, ADR-024).
 *
 * Kept separate from [DeviceIdentity] on purpose: generating a key and deciding to trust a peer are
 * different acts, and collapsing them is how "the handshake succeeded" gets mistaken for "the peer
 * is who we think it is". TLS will happily complete a handshake with any certificate that chains to
 * a trust anchor; for a self-signed local peer there is no chain, so the pin is the entire
 * authentication.
 *
 * ## The three outcomes, and why they are three
 *
 * - [Decision.Unknown] — we have never seen this peer. A first pairing.
 * - [Decision.Known] — the pin matches what we stored. Proceed.
 * - [Decision.Changed] — we have a pin for this peer and it does not match. This is the dangerous
 *   one, and it must never be silently accepted: it is either the peer reinstalling (benign) or a
 *   substitution (not benign), and only the user can tell the difference.
 *
 * A single boolean "trusted" would erase the distinction between the first two and the third, which
 * is the distinction that matters.
 */
object PeerPinning {

    /** What the pin check concluded. */
    sealed interface Decision {
        /** No pin stored for this peer. First contact; the user must confirm a code. */
        data class Unknown(val pin: String) : Decision

        /** The pin matches the stored one. */
        data class Known(val pin: String) : Decision

        /**
         * A pin is stored and does not match.
         *
         * Carries both values so the UI can show what changed without the caller having to re-read
         * storage, and so a log line can say which pin was expected.
         */
        data class Changed(val expected: String, val actual: String) : Decision
    }

    /**
     * Where confirmed peer pins live.
     *
     * Storage is an interface because the decision logic — and its tests — must not depend on a
     * keystore or a Context. The P03 implementation is [KeystorePinStore]; [Store] is the in-memory
     * one used by tests.
     */
    interface PinStore {
        /** The confirmed pin for [peerId], or null when this peer has never been confirmed. */
        fun pinFor(peerId: String): String?

        /** Persists a pin. Only ever called after the user confirmed the code. */
        fun remember(peerId: String, pin: String)

        /** Removes a pin, so the next connection is a first pairing again. */
        fun forget(peerId: String)

        /** Every stored pin, for diagnostics. */
        fun snapshot(): Map<String, String>
    }

    /**
     * A minimal in-memory pin store.
     *
     * Exists for tests and for a process that does not want persistence. It is deliberately *not*
     * what the activity uses: a pairing that disappears on restart is not a pairing, and the P03
     * "known peer" gate needs the pin to survive.
     */
    class Store(initial: Map<String, String> = emptyMap()) : PinStore {
        private val pins = initial.toMutableMap()

        override fun pinFor(peerId: String): String? = pins[peerId]

        override fun remember(peerId: String, pin: String) {
            pins[peerId] = pin
        }

        override fun forget(peerId: String) {
            pins.remove(peerId)
        }

        override fun snapshot(): Map<String, String> = pins.toMap()
    }

    /**
     * Decides what to do about a peer certificate.
     *
     * @param peerId a stable identifier for the peer. For P03 this is the service name the peer was
     *   discovered under, because that is what the user selected and what they will recognise. It is
     *   *not* the pin itself: keying pins by pin would make every peer look new.
     */
    fun evaluate(peerId: String, certificate: X509Certificate, store: PinStore): Decision {
        val actual = DeviceIdentity.pinOf(certificate)
        val expected = store.pinFor(peerId)
        return when {
            expected == null -> Decision.Unknown(actual)
            expected == actual -> Decision.Known(actual)
            else -> Decision.Changed(expected = expected, actual = actual)
        }
    }

    /**
     * Records a pin after the user has confirmed the short authentication code.
     *
     * Deliberately a separate call from [evaluate]: a pin must only be stored once a human has
     * compared the codes. Storing on first sight would make the code confirmation decorative, since
     * the attacker's key would be pinned before anyone looked at it.
     */
    fun confirm(peerId: String, certificate: X509Certificate, store: PinStore) {
        store.remember(peerId, DeviceIdentity.pinOf(certificate))
    }
}
