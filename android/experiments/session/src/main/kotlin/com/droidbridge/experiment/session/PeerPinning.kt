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
     * A minimal in-memory pin store.
     *
     * The prototype keeps pins in memory because P03 is measuring the handshake, not building
     * storage. The production shape is the same interface backed by EncryptedSharedPreferences or
     * the Keystore; naming the interface now means the session code does not have to change when
     * the storage does.
     */
    class Store(initial: Map<String, String> = emptyMap()) {
        private val pins = initial.toMutableMap()

        fun pinFor(peerId: String): String? = pins[peerId]

        fun remember(peerId: String, pin: String) {
            pins[peerId] = pin
        }

        fun forget(peerId: String) {
            pins.remove(peerId)
        }

        fun snapshot(): Map<String, String> = pins.toMap()
    }

    /**
     * Decides what to do about a peer certificate.
     *
     * @param peerId a stable identifier for the peer. For P03 this is the service name the peer was
     *   discovered under, because that is what the user selected and what they will recognise.
     *   It is *not* the pin itself: keying pins by pin would make every peer look new.
     */
    fun evaluate(peerId: String, certificate: X509Certificate, store: Store): Decision {
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
    fun confirm(peerId: String, certificate: X509Certificate, store: Store) {
        store.remember(peerId, DeviceIdentity.pinOf(certificate))
    }
}
