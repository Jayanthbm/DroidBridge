package com.droidbridge.protocol.media

/**
 * NAL unit types that may appear in a DroidBridge mirroring stream.
 *
 * Types 0 and 24-31 are reserved by the specification and are rejected rather than
 * forwarded: a stream that contains them is a producer bug, and passing them to a
 * decoder produces a silently black viewer instead of an error.
 */
object NalUnitType {
    const val NON_IDR_SLICE = 1
    const val PARTITION_A = 2
    const val PARTITION_B = 3
    const val PARTITION_C = 4
    const val IDR_SLICE = 5
    const val SEI = 6
    const val SPS = 7
    const val PPS = 8
    const val ACCESS_UNIT_DELIMITER = 9
    const val END_OF_SEQUENCE = 10
    const val END_OF_STREAM = 11
    const val FILLER = 12
    const val SPS_EXTENSION = 13
}

/**
 * Thrown when bytes cannot be interpreted as the H.264 framing the media channel expects.
 *
 * This is always a producer bug — a wrong buffer, a wrong start-code assumption, or a
 * codec-specific-data buffer that was never split — so it extends [IllegalArgumentException]
 * and carries a message that names the rule that was broken.
 */
class MalformedStreamException(message: String) : IllegalArgumentException(message)

internal const val FORBIDDEN_ZERO_BIT: Int = 0x80
internal const val NAL_TYPE_MASK: Int = 0x1F

/** Highest NAL unit type defined by H.264 (14-23 are defined by extensions/other profiles). */
private const val HIGHEST_DEFINED_NAL_TYPE = 23

/**
 * Returns the NAL unit type of the first byte of [nalUnit], rejecting structurally invalid
 * headers instead of letting them reach a decoder.
 */
internal fun nalUnitType(nalUnit: ByteArray): Int {
    if (nalUnit.isEmpty()) throw MalformedStreamException("NAL unit is empty: no header byte to read a type from")
    val header = nalUnit[0].toInt() and 0xFF
    if (header and FORBIDDEN_ZERO_BIT != 0) {
        throw MalformedStreamException(
            "NAL unit header 0x${header.toString(16).padStart(2, '0')} has forbidden_zero_bit set; " +
                "the byte is not an H.264 NAL unit header (usually a start code was included in the payload)",
        )
    }
    val type = header and NAL_TYPE_MASK
    if (type == 0 || type > HIGHEST_DEFINED_NAL_TYPE) {
        throw MalformedStreamException(
            "NAL unit type $type is reserved by H.264 and must not appear in a mirroring stream",
        )
    }
    return type
}

internal fun nalUnitTypeName(type: Int): String = when (type) {
    NalUnitType.NON_IDR_SLICE -> "non-IDR slice"
    NalUnitType.IDR_SLICE -> "IDR slice"
    NalUnitType.SEI -> "SEI"
    NalUnitType.SPS -> "SPS"
    NalUnitType.PPS -> "PPS"
    NalUnitType.ACCESS_UNIT_DELIMITER -> "access unit delimiter"
    NalUnitType.SPS_EXTENSION -> "SPS extension"
    else -> "NAL type $type"
}

/**
 * True when [bytes] contains an Annex-B start code anywhere, which is how a caller detects
 * that parameter sets were handed over in the wrong framing (R-30).
 *
 * A valid RBSP never contains `00 00 01`, because emulation prevention inserts `0x03`
 * before any `00 00` sequence that would otherwise form one. A match is therefore always
 * a start code and never payload data.
 */
internal fun containsStartCode(bytes: ByteArray): Boolean {
    for (index in 0..bytes.size - 3) {
        if (bytes[index] == 0x00.toByte() &&
            bytes[index + 1] == 0x00.toByte() &&
            bytes[index + 2] == 0x01.toByte()
        ) {
            return true
        }
    }
    return false
}
