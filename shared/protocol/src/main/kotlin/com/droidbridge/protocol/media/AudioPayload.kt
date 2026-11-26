package com.droidbridge.protocol.media

/**
 * Payload of one `AUDIO` message: `[8-byte big-endian ptsUs][AAC access unit]`.
 *
 * The timestamp is the encoder's presentation time in microseconds, taken from the same clock the
 * video frames use, so the receiver can line the two up. It travels inside the payload for the same
 * reason [VideoPayload] puts it there: a frame and its timestamp must not be separable.
 *
 * An empty access unit is refused. AAC frames always carry at least one byte, and a zero-length
 * frame would make the decoder consume the next frame's bytes when it searched for a boundary.
 */
object AudioPayload {

    /** Bytes before the access unit: one 64-bit presentation timestamp. */
    const val HEADER_BYTES: Int = 8

    /** One access unit in the wire form. */
    class Frame(
        /** Encoder presentation timestamp, microseconds, non-negative. */
        val ptsUs: Long,
        /** Raw AAC access unit, without ADTS headers. */
        val accessUnit: ByteArray,
    ) {
        override fun toString(): String = "AudioFrame(ptsUs=$ptsUs, bytes=${accessUnit.size})"
    }

    /** Encodes one frame. */
    fun encode(ptsUs: Long, accessUnit: ByteArray): ByteArray {
        require(ptsUs >= 0) { "ptsUs must be non-negative, was $ptsUs" }
        require(accessUnit.isNotEmpty()) { "access unit is empty" }

        val out = ByteArray(HEADER_BYTES + accessUnit.size)
        for (i in 0 until 8) {
            out[i] = ((ptsUs ushr (56 - 8 * i)) and 0xFF).toByte()
        }
        accessUnit.copyInto(out, HEADER_BYTES)
        return out
    }

    /** Decodes one frame.
     *
     * @throws MalformedStreamException on a short payload, a negative timestamp or an empty unit
     */
    fun decode(payload: ByteArray): Frame {
        if (payload.size <= HEADER_BYTES) {
            throw MalformedStreamException(
                "audio payload is ${payload.size} bytes; needs ${HEADER_BYTES + 1} or more",
            )
        }

        var pts = 0L
        for (i in 0 until 8) {
            pts = (pts shl 8) or (payload[i].toLong() and 0xFF)
        }
        if (pts < 0) throw MalformedStreamException("audio ptsUs has the sign bit set")

        return Frame(ptsUs = pts, accessUnit = payload.copyOfRange(HEADER_BYTES, payload.size))
    }
}
