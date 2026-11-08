package com.droidbridge.protocol.session

/**
 * Length-prefixed framing for the control channel (ADR-012).
 *
 * Wire shape: `[4-byte big-endian unsigned payload length][payload]`. The length covers the
 * payload only and excludes its own 4 bytes.
 *
 * The length prefix is attacker-controlled, so it is validated **before** anything is allocated.
 * A peer that announces 2 GiB must be rejected on the header alone, without the reader waiting for
 * a payload that will never arrive and without reserving memory for it. That ordering is the whole
 * security property of this file, and [FrameReader] is written to make it impossible to get wrong:
 * there is no code path that allocates from a length that has not passed [Framing.validateLength].
 */
object Framing {

    /** Bytes in the length prefix. */
    const val HEADER_BYTES = 4

    /**
     * Largest control payload accepted, in bytes (1 MiB).
     *
     * Sized from the largest legitimate control message rather than picked for tidiness: a
     * `mirror.format` carries Base64 SPS and PPS (a few KiB), and a `clipboard.text` carries up to
     * the 100 KiB clipboard cap from P04. 1 MiB leaves an order of magnitude of headroom over both
     * while keeping a single frame far below anything that would trouble a phone.
     *
     * Media does not travel on this channel — it has its own binary transport (ADR-027) — so this
     * limit never has to accommodate a video frame.
     */
    const val MAX_CONTROL_PAYLOAD_BYTES = 1 shl 20

    /**
     * Reads the length prefix as an unsigned 32-bit value.
     *
     * Returned as [Long] deliberately: a 4-byte signed read would turn `0xFFFFFFFF` into `-1`, and
     * every `> max` check would then pass. The unsigned value is what makes the bound meaningful.
     */
    fun decodeLength(header: ByteArray, offset: Int = 0): Long {
        require(header.size - offset >= HEADER_BYTES) {
            "need $HEADER_BYTES bytes for a length prefix, got ${header.size - offset}"
        }
        return ((header[offset].toLong() and 0xFF) shl 24) or
            ((header[offset + 1].toLong() and 0xFF) shl 16) or
            ((header[offset + 2].toLong() and 0xFF) shl 8) or
            (header[offset + 3].toLong() and 0xFF)
    }

    /** Writes [length] as a 4-byte big-endian prefix. */
    fun encodeLength(length: Int): ByteArray {
        require(length > 0) { "a frame payload must not be empty" }
        return byteArrayOf(
            ((length ushr 24) and 0xFF).toByte(),
            ((length ushr 16) and 0xFF).toByte(),
            ((length ushr 8) and 0xFF).toByte(),
            (length and 0xFF).toByte(),
        )
    }

    /**
     * Rejects a declared length that cannot be honoured.
     *
     * Called with the value straight off the wire, before any allocation.
     */
    fun validateLength(declared: Long, maxPayloadBytes: Int = MAX_CONTROL_PAYLOAD_BYTES) {
        if (declared == 0L) {
            throw FramingException("zero-length frame: a frame must carry a payload")
        }
        if (declared > maxPayloadBytes) {
            throw FrameTooLargeException(declared, maxPayloadBytes)
        }
    }

    /** Encodes one complete frame: prefix plus payload. */
    fun encodeFrame(payload: ByteArray, maxPayloadBytes: Int = MAX_CONTROL_PAYLOAD_BYTES): ByteArray {
        validateLength(payload.size.toLong(), maxPayloadBytes)
        val header = encodeLength(payload.size)
        val frame = ByteArray(HEADER_BYTES + payload.size)
        header.copyInto(frame, 0)
        payload.copyInto(frame, HEADER_BYTES)
        return frame
    }
}

/** A framing rule was violated. The stream cannot be resynchronised, so the session must close. */
open class FramingException(message: String) : Exception(message)

/**
 * A peer announced a payload larger than the limit.
 *
 * Carries [declared] so the log can say what the peer actually asked for, which is the difference
 * between diagnosing a buggy peer and diagnosing an attack.
 */
class FrameTooLargeException(
    val declared: Long,
    val maxPayloadBytes: Int,
) : FramingException(
    "declared payload $declared bytes exceeds the $maxPayloadBytes byte limit",
)

/**
 * Incremental frame decoder.
 *
 * TCP delivers a stream, not messages: one read can carry half a header, three whole frames, or a
 * header and part of a payload. This accumulates across reads and emits only complete frames.
 *
 * Once a framing rule is broken the reader latches into a failed state and every later [feed]
 * throws. Resynchronising after a framing error is how a decoder silently starts interpreting
 * payload bytes as headers, so it is refused outright: the caller closes the connection.
 */
class FrameReader(private val maxPayloadBytes: Int = Framing.MAX_CONTROL_PAYLOAD_BYTES) {

    init {
        require(maxPayloadBytes > 0) { "maxPayloadBytes must be positive" }
    }

    private val header = ByteArray(Framing.HEADER_BYTES)
    private var headerFill = 0
    private var payload: ByteArray? = null
    private var payloadFill = 0
    private var failure: FramingException? = null

    /** True once the stream is unrecoverable. */
    val isFailed: Boolean get() = failure != null

    /**
     * Feeds bytes and returns every frame completed by them, in order.
     *
     * Returns an empty list when the bytes only complete part of a frame, which is normal.
     */
    fun feed(chunk: ByteArray, offset: Int = 0, length: Int = chunk.size - offset): List<ByteArray> {
        failure?.let { throw it }
        require(offset >= 0 && length >= 0 && offset + length <= chunk.size) {
            "offset/length out of bounds for a ${chunk.size} byte chunk"
        }

        val frames = ArrayList<ByteArray>()
        var i = offset
        val end = offset + length

        try {
            while (i < end) {
                val pending = payload
                if (pending == null) {
                    val take = minOf(Framing.HEADER_BYTES - headerFill, end - i)
                    chunk.copyInto(header, headerFill, i, i + take)
                    headerFill += take
                    i += take

                    if (headerFill == Framing.HEADER_BYTES) {
                        val declared = Framing.decodeLength(header)
                        // Validate first. Nothing is allocated until this passes.
                        Framing.validateLength(declared, maxPayloadBytes)
                        payload = ByteArray(declared.toInt())
                        payloadFill = 0
                        headerFill = 0
                    }
                } else {
                    val take = minOf(pending.size - payloadFill, end - i)
                    chunk.copyInto(pending, payloadFill, i, i + take)
                    payloadFill += take
                    i += take

                    if (payloadFill == pending.size) {
                        frames.add(pending)
                        payload = null
                        payloadFill = 0
                    }
                }
            }
        } catch (e: FramingException) {
            failure = e
            throw e
        }

        return frames
    }

    /** Bytes buffered toward the frame currently being assembled. */
    fun pendingBytes(): Int = headerFill + (payload?.let { payloadFill } ?: 0)
}
