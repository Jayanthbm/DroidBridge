package com.droidbridge.protocol.session

/**
 * The control-message envelope carried inside one [Framing] frame (NETWORK_PROTOCOL §4, §5).
 *
 * Wire shape: `[version:1][type:1][sequence:4 big-endian][payload…]`.
 *
 * ## Why a sequence number exists
 *
 * TLS 1.3 already protects the record layer against replay: an attacker cannot re-inject an earlier
 * record into the same connection without the session keys. So why repeat it above TLS?
 *
 * - The control channel is the part of the protocol that will later be carried over other
 *   transports (a reconnect that has to re-establish keys, a future non-TLS media side channel), and
 *   a receiver must not have to assume the transport did the work.
 * - It makes replay rejection *testable* without breaking TLS: a peer that sends a duplicate or
 *   rewound sequence number is a peer whose frames must be refused, and a test can send exactly that.
 *
 * The sequence is per sender and strictly increasing from 1. A receiver keeps one
 * [ReplayWindow] per peer direction. It is not a nonce and not a secret; it is an ordering check.
 *
 * ## Why the payload may be empty
 *
 * `session.ping`, `session.pong` and `session.close` carry no body. Framing still rejects a
 * zero-length *frame*, because a frame must carry a control header; the header is 6 bytes, so every
 * encoded control message is a valid frame payload.
 */
object ControlMessage {

    /** Envelope version. A peer with any other version is rejected, not upgraded silently. */
    const val VERSION: Int = 1

    /** Bytes before the payload: version, type, 4-byte sequence. */
    const val HEADER_BYTES: Int = 6

    /** Largest control payload after the envelope header. */
    const val MAX_PAYLOAD_BYTES: Int =
        Framing.MAX_CONTROL_PAYLOAD_BYTES - HEADER_BYTES

    /** Control message types. Codes are wire values and must never be reused. */
    enum class Type(val code: Int) {
        PING(0x01),
        PONG(0x02),
        CLOSE(0x03),
        ;

        companion object {
            fun fromCode(code: Int): Type =
                entries.firstOrNull { it.code == code }
                    ?: throw FramingException("unknown control message type 0x%02x".format(code))
        }
    }

    /** One decoded control message. */
    data class Message(
        val type: Type,
        val sequence: Long,
        val payload: ByteArray,
    ) {
        // Data classes with a ByteArray need explicit equality; identity equality on the array
        // would make two equal messages compare unequal in tests and in any future dedupe.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Message) return false
            return type == other.type &&
                sequence == other.sequence &&
                payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int =
            (type.hashCode() * 31 + sequence.hashCode()) * 31 + payload.contentHashCode()
    }

    /** Encodes one control message. Validates before producing bytes. */
    fun encode(type: Type, sequence: Long, payload: ByteArray = ByteArray(0)): ByteArray {
        require(sequence in 1..0xFFFF_FFFFL) {
            "control sequence must be 1..4294967295, got $sequence"
        }
        require(payload.size <= MAX_PAYLOAD_BYTES) {
            "control payload ${payload.size} exceeds $MAX_PAYLOAD_BYTES bytes"
        }

        val out = ByteArray(HEADER_BYTES + payload.size)
        out[0] = VERSION.toByte()
        out[1] = type.code.toByte()
        out[2] = ((sequence ushr 24) and 0xFF).toByte()
        out[3] = ((sequence ushr 16) and 0xFF).toByte()
        out[4] = ((sequence ushr 8) and 0xFF).toByte()
        out[5] = (sequence and 0xFF).toByte()
        payload.copyInto(out, HEADER_BYTES)
        return out
    }

    /**
     * Decodes one frame payload into a control message.
     *
     * Throws [FramingException] on anything malformed. The caller closes the connection: a control
     * header that does not parse means the stream cannot be trusted to stay in sync.
     */
    fun decode(bytes: ByteArray): Message {
        if (bytes.size < HEADER_BYTES) {
            throw FramingException(
                "control message is ${bytes.size} bytes, needs at least $HEADER_BYTES",
            )
        }

        val version = bytes[0].toInt() and 0xFF
        if (version != VERSION) {
            throw FramingException("unsupported control version $version")
        }

        val type = Type.fromCode(bytes[1].toInt() and 0xFF)
        val sequence = ((bytes[2].toLong() and 0xFF) shl 24) or
            ((bytes[3].toLong() and 0xFF) shl 16) or
            ((bytes[4].toLong() and 0xFF) shl 8) or
            (bytes[5].toLong() and 0xFF)

        if (sequence == 0L) {
            // Sequence zero is not a legal first message: it would make "nothing received yet" and
            // "message zero received" the same state.
            throw FramingException("control sequence must start at 1")
        }

        val payload = bytes.copyOfRange(HEADER_BYTES, bytes.size)
        if (payload.size > MAX_PAYLOAD_BYTES) {
            throw FrameTooLargeException(payload.size.toLong(), MAX_PAYLOAD_BYTES)
        }

        return Message(type = type, sequence = sequence, payload = payload)
    }

    /** Display form for a payload that is meant to be readable, bounded and redacted otherwise. */
    fun describe(message: Message): String {
        if (message.payload.isEmpty()) return "${message.type.name.lowercase()}:${message.sequence}"
        val text = message.payload.toString(Charsets.UTF_8)
        val printable = text.all { it.code in 0x20..0x7E }
        return if (printable) {
            "${message.type.name.lowercase()}:${message.sequence}:${text.take(64)}"
        } else {
            "${message.type.name.lowercase()}:${message.sequence}:<${message.payload.size} bytes>"
        }
    }
}

/** A control sequence repeated or rewound. The session must close. */
class ReplayException(message: String) : FramingException(message)

/**
 * Accepts only strictly increasing control sequence numbers.
 *
 * One instance per received direction. Fresh per session, because the peer's counter restarts at 1
 * on a new connection; sharing one across connections would reject every message after a reconnect.
 */
class ReplayWindow {

    private var lastAccepted: Long = 0

    /** Highest sequence accepted so far. Zero means nothing has been accepted. */
    val highest: Long get() = lastAccepted

    /**
     * Validates [sequence] and advances the window.
     *
     * @throws ReplayException when the sequence is repeated or rewound
     */
    fun accept(sequence: Long) {
        if (sequence <= lastAccepted) {
            throw ReplayException(
                "replayed control sequence $sequence (highest accepted $lastAccepted)",
            )
        }
        lastAccepted = sequence
    }
}
