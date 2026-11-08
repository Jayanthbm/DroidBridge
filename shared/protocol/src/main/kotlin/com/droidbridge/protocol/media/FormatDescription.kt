package com.droidbridge.protocol.media

/**
 * The format-description exchange the macOS receiver performs for every `mirror.format`
 * message.
 *
 * `CMVideoFormatDescriptionCreateFromH264ParameterSets` is the only documented Apple path
 * for H.264, and it is exact about what it wants (R-30): at least two parameter sets, raw
 * NAL units (no start code), [NalUnitHeaderLength] of 4, and samples that are 4-byte
 * length-prefixed. Nothing below reprograms those rules; it just names them where both
 * platforms agree to read the same text.
 */
class FormatDescription {

    /**
     * Parameter sets, in decode order, as bare NAL units. [count] is `sps` plus `pps`.
     * One or the other, or a buffer with a start code in it, is an error here.
     */
    data class ParameterSets(
        val sps: ByteArray,
        val pps: ByteArray,
    ) {
        val count: Int get() = 2
    }

    /**
     * The four facts the receiver's `CMVideoFormatDescriptionCreateFromH264ParameterSets`
     * call takes from a `mirror.format` message:
     *
     * - [width] / [height]  - from `mirror.format`.
     * - [parameterSets]     - SPS then PPS, as delivered in that message.
     * - [nalUnitHeaderLength] - always 4 for the MVP (4-byte length prefix, R-30);
     *   1 or 2 are the other values CoreMedia accepts but don't match our wire.
     * - [applicationType]   - 7 for H.264 profile 42, the internal flag `mp4a`/codec
     *   private data uses; derived from the profile on the Android side.
     */
    data class Builder(
        val width: Int,
        val height: Int,
        val rotationDegrees: Int,
        val parameterSets: ParameterSets,
        val nalUnitHeaderLength: Int = AnnexBToAvcc.NAL_LENGTH_SIZE,
        val applicationType: Int = 7,
    ) {
        init {
            require(parameterSets.count >= 2) {
                "CoreMedia requires at least two parameter sets; got ${parameterSets.count}"
            }
            require(nalUnitHeaderLength in setOf(1, 2, 4)) {
                "CoreMedia accepts nalUnitHeaderLength 1, 2 or 4; got $nalUnitHeaderLength"
            }
        }
    }

    /**
     * The receiver's documented failure responses. All four are documented on the
     * `CMVideoFormatDescriptionCreateFromH264ParameterSets` / `CMSampleBufferCreate`
     * pages (R-30) and are deliberately thrown rather than silently producing a black
     * frame: a bad format description is a producer bug, and the message must name it.
     */
    enum class Failure {
        EMPTY_PARAMETER_SETS,
        START_CODE_IN_PARAMETER_SETS,
        WRONG_PARAMETER_SET_TYPE,
        UNSUPPORTED_NAL_LENGTH_SIZE,
    }
}
