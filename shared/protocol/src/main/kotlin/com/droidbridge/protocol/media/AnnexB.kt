package com.droidbridge.protocol.media

/**
 * Splitting of Annex-B byte streams into NAL units.
 *
 * MediaCodec hands out H.264 in Annex-B form: every NAL unit begins with a start code of
 * either `00 00 01` or `00 00 00 01` (R-30). CoreMedia needs the units *without* any start
 * code, so this split is the first half of the conversion.
 */
object AnnexB {

    private const val ZERO = 0x00.toByte()
    private const val ONE = 0x01.toByte()

    /**
     * Splits [data] into its NAL units, with start codes and trailing zero padding removed.
     *
     * The result is what a format description or a length-prefixed sample needs: NAL unit
     * bytes only, emulation prevention bytes untouched.
     *
     * @throws MalformedStreamException if there is no start code, if a start code is
     *   immediately followed by another start code (an empty NAL unit), or if non-zero
     *   bytes precede the first start code.
     */
    fun splitNalUnits(data: ByteArray): List<ByteArray> {
        val nalUnits = ArrayList<ByteArray>()
        var nalUnitStart = -1
        var firstStartCodeIndex = -1
        var index = 0

        // Scanning for the three-byte form finds a four-byte start code at its second zero,
        // which puts the NAL unit start in the same place: 00 00 00 01 is read as 00 00 01
        // beginning one byte later. The byte left behind then belongs to the previous unit's
        // padding, and sliceNalUnit trims it. One rule therefore covers both forms.
        while (index + 2 < data.size) {
            if (data[index] == ZERO && data[index + 1] == ZERO && data[index + 2] == ONE) {
                if (nalUnitStart < 0) {
                    firstStartCodeIndex = index
                } else {
                    nalUnits += sliceNalUnit(data, nalUnitStart, index)
                }
                index += 3
                nalUnitStart = index
                continue
            }
            index++
        }

        if (nalUnitStart < 0) {
            throw MalformedStreamException(
                "no Annex-B start code (00 00 01) found in ${data.size} bytes; " +
                    "MediaCodec output is always Annex-B, so this buffer is not an access unit",
            )
        }

        // Leading zero padding is legal (trailing_zero_8bits). Anything else before the first
        // start code means the buffer does not begin on an access unit boundary.
        for (leading in 0 until firstStartCodeIndex) {
            if (data[leading] != ZERO) {
                throw MalformedStreamException(
                    "unexpected byte 0x${(data[leading].toInt() and 0xFF).toString(16)} at offset $leading, " +
                        "before the first start code at offset $firstStartCodeIndex",
                )
            }
        }

        nalUnits += sliceNalUnit(data, nalUnitStart, data.size)
        return nalUnits
    }

    private fun sliceNalUnit(data: ByteArray, from: Int, toExclusive: Int): ByteArray {
        var end = toExclusive
        // A valid RBSP ends with rbsp_stop_one_bit, so its final byte is never 0x00. Trailing
        // zeros belong to the next start code's padding and must not become part of the NAL
        // unit, or the AVCC length prefix would overstate the unit by that many bytes.
        while (end > from && data[end - 1] == ZERO) {
            end--
        }
        if (end == from) {
            throw MalformedStreamException(
                "empty NAL unit at offset $from: a start code is immediately followed by another start code",
            )
        }
        return data.copyOfRange(from, end)
    }
}
