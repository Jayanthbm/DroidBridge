package com.droidbridge.protocol.media

import kotlin.test.assertEquals

/** Parses a hex string such as `"00 00 00 01 67"` into bytes. Whitespace is ignored. */
internal fun hex(text: String): ByteArray {
    val cleaned = text.filterNot { it.isWhitespace() || it == '_' }
    require(cleaned.length % 2 == 0) { "hex string must have an even number of digits: '$text'" }
    return ByteArray(cleaned.length / 2) { index ->
        cleaned.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}

internal fun ByteArray.toHex(): String = joinToString(" ") { "%02x".format(it) }

internal fun assertBytesEqual(expected: ByteArray, actual: ByteArray, message: String? = null) {
    assertEquals(expected.toHex(), actual.toHex(), message)
}

/**
 * Fixed NAL units used across the tests. They are synthetic but structurally valid: the
 * start-code-adjacent padding, the emulation prevention bytes and the NAL headers are the
 * parts the conversion code actually inspects.
 */
internal object Vectors {
    /** Baseline-profile SPS; contains the `00 00 03` emulation prevention sequence. */
    val SPS = hex("67 42 00 1F E5 81 40 3C 01 0D 40 00 00 03 00 40 00 00 0C A3 C4 89 E0")

    /** PPS. */
    val PPS = hex("68 CE 3C 80")

    /** IDR slice; also contains emulation prevention bytes. */
    val IDR = hex("65 88 84 00 0A 7B 00 00 03 00 01 23 45")

    /** Non-IDR slice. */
    val NON_IDR = hex("41 9A 21 00 00 03 00 02")

    /** SEI message. */
    val SEI = hex("06 05 10 20 30 40 80")

    /** Access unit delimiter. */
    val AUD = hex("09 F0")

    /** MediaCodec's `csd-0`: an SPS with a start code, as the codec delivers it. */
    val CSD_0 = hex("00 00 00 01") + SPS

    /** MediaCodec's `csd-1`: a PPS with a start code. */
    val CSD_1 = hex("00 00 00 01") + PPS

    /** A typical IDR access unit: AUD, SPS and PPS are not repeated in-band; SEI then IDR. */
    val KEYFRAME_ACCESS_UNIT = hex("00 00 00 01") + SEI + hex("00 00 01") + IDR

    /** A typical inter frame. */
    val INTER_ACCESS_UNIT = hex("00 00 00 01") + NON_IDR
}
