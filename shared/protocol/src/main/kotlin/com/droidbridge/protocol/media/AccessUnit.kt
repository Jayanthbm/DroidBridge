package com.droidbridge.protocol.media

/**
 * One encoded frame, in the framing the media channel carries on the wire.
 *
 * [payload] is AVCC: repeated `[4-byte big-endian NAL length][NAL bytes]`. It is not
 * Annex-B, and it is not a complete sample buffer — the receiver wraps it in its own
 * container. [ptsUs] is the producer's `MediaCodec.BufferInfo.presentationTimeUs`.
 *
 * A media frame is one access unit, so a sample buffer per payload is one frame per
 * `CMSampleBuffer` on the receiving side (ADR-028).
 */
class AccessUnit(
    val ptsUs: Long,
    val payload: ByteArray,
    val isKeyframe: Boolean,
) {
    /** Size of the length prefix in front of every NAL unit in [payload]. */
    val nalLengthSize: Int get() = AnnexBToAvcc.NAL_LENGTH_SIZE

    override fun toString(): String =
        "AccessUnit(ptsUs=$ptsUs, bytes=${payload.size}, isKeyframe=$isKeyframe)"
}

/**
 * Converts MediaCodec's Annex-B output into the length-prefixed framing CoreMedia requires
 * (ADR-027, R-30).
 *
 * The conversion happens on the producing side so that the wire format has exactly one
 * definition and a framing mistake is caught next to the code that made it.
 */
object AnnexBToAvcc {

    /**
     * 4-byte lengths: the conventional choice among the values
     * `CMVideoFormatDescriptionCreateFromH264ParameterSets` accepts (1, 2 or 4). Anything
     * smaller would limit a NAL unit to 64 KiB, which one 1080p IDR slice can exceed.
     */
    const val NAL_LENGTH_SIZE: Int = 4

    private const val MAX_NAL_UNIT_SIZE: Int = 0x00FF_FFFF

    /**
     * Converts one Annex-B access unit into a length-prefixed payload.
     *
     * @param dropParameterSets when true, SPS and PPS NAL units are removed from the access unit.
     *   This is what a live producer needs: the parameter sets already travel in `mirror.format`, and
     *   repeating them inside a sample is at best redundant and at worst makes a decoder re-evaluate
     *   its format mid-stream. Detection of an IDR still happens over the original units. An access
     *   unit that contained *only* parameter sets throws, because sending an empty payload would
     *   violate the media framing.
     * @throws MalformedStreamException if [annexB] is not a well-formed access unit.
     * @throws IllegalArgumentException if [ptsUs] is negative.
     */
    fun convertAccessUnit(
        annexB: ByteArray,
        ptsUs: Long,
        dropParameterSets: Boolean = false,
    ): AccessUnit {
        require(ptsUs >= 0) { "ptsUs must be a non-negative microsecond timestamp, was $ptsUs" }

        val allUnits = AnnexB.splitNalUnits(annexB)
        var isKeyframe = false
        var payloadSize = 0

        for (nalUnit in allUnits) {
            val type = nalUnitType(nalUnit)
            if (type == NalUnitType.IDR_SLICE) isKeyframe = true
            if (nalUnit.size > MAX_NAL_UNIT_SIZE) {
                throw MalformedStreamException(
                    "NAL unit of ${nalUnit.size} bytes does not fit a $NAL_LENGTH_SIZE-byte length prefix",
                )
            }
        }

        val nalUnits = if (dropParameterSets) {
            allUnits.filter {
                val type = nalUnitType(it)
                type != NalUnitType.SPS && type != NalUnitType.PPS
            }
        } else {
            allUnits
        }

        if (nalUnits.isEmpty()) {
            throw MalformedStreamException(
                "access unit carried only parameter sets; nothing is left to send",
            )
        }

        for (nalUnit in nalUnits) {
            payloadSize += NAL_LENGTH_SIZE + nalUnit.size
        }

        val payload = ByteArray(payloadSize)
        var offset = 0
        for (nalUnit in nalUnits) {
            val size = nalUnit.size
            payload[offset] = (size ushr 24).toByte()
            payload[offset + 1] = (size ushr 16).toByte()
            payload[offset + 2] = (size ushr 8).toByte()
            payload[offset + 3] = size.toByte()
            nalUnit.copyInto(payload, offset + NAL_LENGTH_SIZE)
            offset += NAL_LENGTH_SIZE + size
        }

        return AccessUnit(ptsUs = ptsUs, payload = payload, isKeyframe = isKeyframe)
    }
}
