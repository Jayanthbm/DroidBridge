package com.droidbridge.protocol.media

import java.util.Base64

/**
 * Payload of the `mirror.format` control message (NETWORK_PROTOCOL §7).
 *
 * Sent once at stream start and again after every re-encode. A receiver that has not yet
 * received this message cannot build a decoder, which is why the parameter sets travel in
 * the control channel rather than in-band: they are configuration, not frames.
 *
 * The message is JSON and the parameter sets are binary, so they are Base64 encoded. This is
 * control traffic, not video — the media channel itself stays binary (NETWORK_PROTOCOL §5).
 */
class MirrorFormat(
    val width: Int,
    val height: Int,
    /** Degrees clockwise the captured content was rotated to reach this size: 0, 90, 180 or 270. */
    val rotation: Int,
    val parameterSets: H264ParameterSets,
    /** Bytes in each length prefix on the media channel; CoreMedia accepts 1, 2 or 4. */
    val nalLengthSize: Int = AnnexBToAvcc.NAL_LENGTH_SIZE,
) {

    val sps: ByteArray get() = parameterSets.sps
    val pps: ByteArray get() = parameterSets.pps

    init {
        require(width > 0) { "width must be positive, was $width" }
        require(height > 0) { "height must be positive, was $height" }
        require(rotation in ALLOWED_ROTATIONS) {
            "rotation must be one of ${ALLOWED_ROTATIONS.joinToString()}, was $rotation"
        }
        require(nalLengthSize in ALLOWED_NAL_LENGTH_SIZES) {
            "nalLengthSize must be one of ${ALLOWED_NAL_LENGTH_SIZES.joinToString()} (the lengths " +
                "CMVideoFormatDescriptionCreateFromH264ParameterSets accepts), was $nalLengthSize"
        }
    }

    /**
     * Serialises the payload for a `mirror.format` control message.
     *
     * Key order is fixed so the encoding is deterministic and can be asserted as a test vector.
     */
    fun toJsonPayload(): String = buildString {
        append('{')
        append("\"width\":").append(width).append(',')
        append("\"height\":").append(height).append(',')
        append("\"rotation\":").append(rotation).append(',')
        append("\"sps\":\"").append(BASE64_ENCODER.encodeToString(sps)).append("\",")
        append("\"pps\":\"").append(BASE64_ENCODER.encodeToString(pps)).append("\",")
        append("\"nalLengthSize\":").append(nalLengthSize)
        append('}')
    }

    override fun toString(): String =
        "MirrorFormat(${width}x$height, rotation=$rotation, nalLengthSize=$nalLengthSize)"

    companion object {
        val ALLOWED_ROTATIONS: Set<Int> = linkedSetOf(0, 90, 180, 270)
        val ALLOWED_NAL_LENGTH_SIZES: Set<Int> = linkedSetOf(1, 2, 4)

        private val BASE64_ENCODER: Base64.Encoder = Base64.getEncoder()
    }
}
