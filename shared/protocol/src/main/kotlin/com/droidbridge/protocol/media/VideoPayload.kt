package com.droidbridge.protocol.media

/**
 * Payload of one `VIDEO` message (NETWORK_PROTOCOL §7).
 *
 * Wire shape: `[8-byte big-endian ptsUs][AVCC access unit]`, where the AVCC part is the repeated
 * `[4-byte big-endian NAL length][NAL bytes]` produced by [AnnexBToAvcc].
 *
 * ## Why the timestamp travels in the payload and not in the envelope
 *
 * The envelope's sequence number is an ordering check, not a clock: it says nothing about when a
 * frame should be shown. CoreMedia needs a presentation timestamp, and the only honest source is
 * the producer's `MediaCodec.BufferInfo.presentationTimeUs`. It goes here, next to the access unit
 * it belongs to, so a frame and its timestamp can never be separated by a decoder that drops or
 * reorders one of them.
 *
 * ## Why the AVCC structure is validated on the way in
 *
 * A length prefix is attacker-controlled data. `CMSampleBufferCreateReady` is handed the block
 * buffer and a format description that says "lengths are 4 bytes"; if the lengths inside the
 * payload disagree with the buffer size, the decoder is being asked to read past it. Validating here
 * — before the buffer is built — turns a malformed frame into a refused frame.
 */
object VideoPayload {

    /** Bytes before the AVCC access unit: one 64-bit presentation timestamp. */
    const val HEADER_BYTES: Int = 8

    /** One access unit in the wire form. */
    class Frame(
        /** Producer's presentation timestamp, microseconds, non-negative. */
        val ptsUs: Long,
        /** AVCC bytes: repeated `[4-byte length][NAL]`. */
        val avcc: ByteArray,
    ) {
        override fun toString(): String = "VideoFrame(ptsUs=$ptsUs, bytes=${avcc.size})"
    }

    /**
     * Encodes one frame.
     *
     * @throws MalformedStreamException if [avcc] is empty or is not well-formed AVCC
     */
    fun encode(ptsUs: Long, avcc: ByteArray): ByteArray {
        require(ptsUs >= 0) { "ptsUs must be non-negative, was $ptsUs" }
        validateAvcc(avcc)

        val out = ByteArray(HEADER_BYTES + avcc.size)
        for (i in 0 until 8) {
            out[i] = ((ptsUs ushr (56 - 8 * i)) and 0xFF).toByte()
        }
        avcc.copyInto(out, HEADER_BYTES)
        return out
    }

    /**
     * Decodes one frame.
     *
     * @throws MalformedStreamException on a short payload, a negative timestamp, or malformed AVCC
     */
    fun decode(payload: ByteArray): Frame {
        if (payload.size <= HEADER_BYTES) {
            throw MalformedStreamException(
                "video payload is ${payload.size} bytes; needs ${HEADER_BYTES + 1} or more",
            )
        }

        var pts = 0L
        for (i in 0 until 8) {
            pts = (pts shl 8) or (payload[i].toLong() and 0xFF)
        }
        if (pts < 0) {
            // Bit 63 set would mean a timestamp ~292,000 years in the future; the producer never
            // sends one, so it is a malformed frame rather than a clock.
            throw MalformedStreamException("video ptsUs has the sign bit set")
        }

        val avcc = payload.copyOfRange(HEADER_BYTES, payload.size)
        validateAvcc(avcc)
        return Frame(ptsUs = pts, avcc = avcc)
    }

    /**
     * Checks that [avcc] is exactly a sequence of length-prefixed NAL units.
     *
     * Zero-length NAL units are rejected: they carry nothing, and a decoder that trusted the length
     * would be handed a format description that does not match the payload.
     */
    fun validateAvcc(avcc: ByteArray) {
        if (avcc.isEmpty()) throw MalformedStreamException("access unit is empty")

        var offset = 0
        var nalCount = 0
        while (offset < avcc.size) {
            if (avcc.size - offset < AnnexBToAvcc.NAL_LENGTH_SIZE) {
                throw MalformedStreamException(
                    "access unit ends with ${avcc.size - offset} trailing bytes instead of a length prefix",
                )
            }
            var length = 0
            for (i in 0 until AnnexBToAvcc.NAL_LENGTH_SIZE) {
                length = (length shl 8) or (avcc[offset + i].toInt() and 0xFF)
            }
            offset += AnnexBToAvcc.NAL_LENGTH_SIZE

            if (length <= 0) {
                throw MalformedStreamException("access unit carries a zero-length NAL unit")
            }
            if (length > avcc.size - offset) {
                throw MalformedStreamException(
                    "NAL unit declares $length bytes but only ${avcc.size - offset} remain",
                )
            }
            offset += length
            nalCount++
        }

        if (nalCount == 0) throw MalformedStreamException("access unit carries no NAL unit")
    }
}
