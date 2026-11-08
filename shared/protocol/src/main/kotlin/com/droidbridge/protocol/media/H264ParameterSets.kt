package com.droidbridge.protocol.media

/**
 * The SPS and PPS a receiver needs before it can build a format description.
 *
 * CoreMedia's `CMVideoFormatDescriptionCreateFromH264ParameterSets` requires the parameter
 * sets as raw NAL units — no start code, no length prefix — and requires at least two of
 * them (R-30). This type is the single place where that framing is enforced on the
 * producing side, so the raw bytes can never be forwarded in the wrong shape.
 */
class H264ParameterSets private constructor(
    /** Sequence parameter set, bare NAL unit. */
    val sps: ByteArray,
    /** Picture parameter set, bare NAL unit. */
    val pps: ByteArray,
) {

    /** How many parameter sets the receiver must receive before decoding can start. */
    val parameterSetCount: Int get() = 2

    companion object {

        /**
         * Builds the parameter sets from MediaCodec's codec-specific data.
         *
         * MediaCodec delivers a separate buffer per parameter set (`csd-0` = SPS, `csd-1` =
         * PPS), each prefixed with a start code, which is exactly the framing CoreMedia will
         * not accept. This parses and strips it.
         *
         * @throws MalformedStreamException if a buffer does not hold exactly one NAL unit of
         *   the expected type.
         */
        fun fromCodecSpecificData(spsBuffer: ByteArray, ppsBuffer: ByteArray): H264ParameterSets =
            H264ParameterSets(
                sps = requireSingleNalUnit(spsBuffer, NalUnitType.SPS, "csd-0"),
                pps = requireSingleNalUnit(ppsBuffer, NalUnitType.PPS, "csd-1"),
            )

        /**
         * Builds the parameter sets from bytes that are already bare NAL units.
         *
         * This is the path for data that did not come from `csd-0`/`csd-1` — a stored
         * parameter set, a test vector, or a receiver that already holds bare units. Start
         * codes are rejected rather than stripped, because a caller that passes them here has
         * misunderstood the framing and would otherwise ship it.
         */
        fun fromBareNalUnits(sps: ByteArray, pps: ByteArray): H264ParameterSets {
            requireBare(sps, NalUnitType.SPS, "sps")
            requireBare(pps, NalUnitType.PPS, "pps")
            return H264ParameterSets(sps.copyOf(), pps.copyOf())
        }

        private fun requireSingleNalUnit(buffer: ByteArray, expectedType: Int, label: String): ByteArray {
            val nalUnits = AnnexB.splitNalUnits(buffer)
            if (nalUnits.size != 1) {
                throw MalformedStreamException(
                    "$label must carry exactly one NAL unit but carried ${nalUnits.size}; " +
                        "MediaCodec delivers one parameter set per codec-specific-data buffer",
                )
            }
            val nalUnit = nalUnits.first()
            val type = nalUnitType(nalUnit)
            if (type != expectedType) {
                throw MalformedStreamException(
                    "$label must carry a ${nalUnitTypeName(expectedType)} but carried a ${nalUnitTypeName(type)}",
                )
            }
            return nalUnit
        }

        private fun requireBare(nalUnit: ByteArray, expectedType: Int, label: String) {
            if (nalUnit.isEmpty()) throw MalformedStreamException("$label is empty")
            if (containsStartCode(nalUnit)) {
                throw MalformedStreamException(
                    "$label still contains an Annex-B start code; a format description needs bare NAL units (R-30)",
                )
            }
            val type = nalUnitType(nalUnit)
            if (type != expectedType) {
                throw MalformedStreamException(
                    "$label must be a ${nalUnitTypeName(expectedType)} but is a ${nalUnitTypeName(type)}",
                )
            }
        }
    }
}
