package com.droidbridge.protocol.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnnexBToAvccTest {

    @Test
    fun `writes four byte big endian length prefixes`() {
        val annexB = hex("00 00 01") + Vectors.AUD + hex("00 00 01") + Vectors.PPS

        val accessUnit = AnnexBToAvcc.convertAccessUnit(annexB, ptsUs = 0)

        assertBytesEqual(
            expected = hex("00 00 00 02 09 f0 00 00 00 04 68 ce 3c 80"),
            actual = accessUnit.payload,
            message = "AUD is 2 bytes, PPS is 4 bytes",
        )
    }

    @Test
    fun `keeps the presentation timestamp it was given`() {
        val accessUnit = AnnexBToAvcc.convertAccessUnit(Vectors.INTER_ACCESS_UNIT, ptsUs = 1_234_567)

        assertEquals(1_234_567L, accessUnit.ptsUs)
    }

    @Test
    fun `preserves emulation prevention bytes`() {
        val accessUnit = AnnexBToAvcc.convertAccessUnit(hex("00 00 01") + Vectors.SPS, ptsUs = 0)

        assertEquals(AnnexBToAvcc.NAL_LENGTH_SIZE + Vectors.SPS.size, accessUnit.payload.size)
        assertBytesEqual(
            expected = Vectors.SPS,
            actual = accessUnit.payload.copyOfRange(AnnexBToAvcc.NAL_LENGTH_SIZE, accessUnit.payload.size),
            message = "conversion replaces a start code with a length; it is not a byte rewrite",
        )
        assertTrue(
            accessUnit.payload.toHex().contains("00 00 03"),
            "the 00 00 03 sequence must survive conversion",
        )
    }

    @Test
    fun `marks a unit as a keyframe only when it carries an IDR slice`() {
        val keyframe = AnnexBToAvcc.convertAccessUnit(Vectors.KEYFRAME_ACCESS_UNIT, ptsUs = 0)
        val interFrame = AnnexBToAvcc.convertAccessUnit(Vectors.INTER_ACCESS_UNIT, ptsUs = 0)

        assertTrue(keyframe.isKeyframe, "an access unit containing an IDR slice is a keyframe")
        assertFalse(interFrame.isKeyframe, "a non-IDR slice is not a keyframe")
    }

    @Test
    fun `does not treat parameter sets alone as a keyframe`() {
        val annexB = hex("00 00 01") + Vectors.SPS + hex("00 00 01") + Vectors.PPS

        val accessUnit = AnnexBToAvcc.convertAccessUnit(annexB, ptsUs = 0)

        assertFalse(accessUnit.isKeyframe, "SPS and PPS are configuration, not a decodable frame")
    }

    @Test
    fun `round trips through a length prefix parser`() {
        val annexB = Vectors.KEYFRAME_ACCESS_UNIT

        val payload = AnnexBToAvcc.convertAccessUnit(annexB, ptsUs = 0).payload

        assertBytesEqual(Vectors.SEI, firstNalUnit(payload), "first NAL unit")
        assertBytesEqual(Vectors.IDR, secondNalUnit(payload), "second NAL unit")
    }

    @Test
    fun `rejects a NAL unit with the forbidden zero bit set`() {
        assertFailsWith<MalformedStreamException> {
            AnnexBToAvcc.convertAccessUnit(hex("00 00 01 e7 88"), ptsUs = 0)
        }
    }

    @Test
    fun `rejects reserved NAL unit types`() {
        assertFailsWith<MalformedStreamException> {
            // 0x60 & 0x1F == 0, which H.264 reserves.
            AnnexBToAvcc.convertAccessUnit(hex("00 00 01 60 11"), ptsUs = 0)
        }
        assertFailsWith<MalformedStreamException> {
            // 0x78 & 0x1F == 24, the first reserved type above the specified range.
            AnnexBToAvcc.convertAccessUnit(hex("00 00 01 78 11"), ptsUs = 0)
        }
    }

    @Test
    fun `rejects an access unit with no start code`() {
        assertFailsWith<MalformedStreamException> {
            AnnexBToAvcc.convertAccessUnit(Vectors.IDR, ptsUs = 0)
        }
    }

    @Test
    fun `rejects a negative timestamp`() {
        assertFailsWith<IllegalArgumentException> {
            AnnexBToAvcc.convertAccessUnit(Vectors.INTER_ACCESS_UNIT, ptsUs = -1)
        }
    }

    @Test
    fun `drops parameter sets when the producer already sent them in the format message`() {
        // A live encoder may repeat SPS/PPS in-band. They are configuration, not a frame, and they
        // already travelled in mirror.format, so a live access unit must not carry them again.
        val annexB = hex("00 00 00 01") + Vectors.SPS +
            hex("00 00 00 01") + Vectors.PPS +
            hex("00 00 00 01") + Vectors.IDR

        val unit = AnnexBToAvcc.convertAccessUnit(annexB, ptsUs = 0, dropParameterSets = true)

        assertEquals(1, nalCount(unit.payload))
        assertBytesEqual(Vectors.IDR, firstNalUnit(unit.payload), "only the IDR slice remains")
        assertTrue(unit.isKeyframe, "dropping parameter sets must not lose the IDR detection")
    }

    @Test
    fun `keeps parameter sets when the caller did not ask for them to be dropped`() {
        val annexB = hex("00 00 00 01") + Vectors.SPS +
            hex("00 00 00 01") + Vectors.PPS +
            hex("00 00 00 01") + Vectors.IDR

        val unit = AnnexBToAvcc.convertAccessUnit(annexB, ptsUs = 0)

        assertEquals(3, nalCount(unit.payload))
    }

    @Test
    fun `refuses an access unit that carried only parameter sets`() {
        // Sending an empty access unit would violate the media framing, so this is an error rather
        // than a silent skip.
        val annexB = hex("00 00 00 01") + Vectors.SPS + hex("00 00 00 01") + Vectors.PPS

        assertFailsWith<MalformedStreamException> {
            AnnexBToAvcc.convertAccessUnit(annexB, ptsUs = 0, dropParameterSets = true)
        }
    }

    /** Reads the first length-prefixed NAL unit, independently of the production code. */

    private fun nalCount(payload: ByteArray): Int {
        var offset = 0
        var count = 0
        while (offset < payload.size) {
            offset += 4 + lengthPrefix(payload, offset)
            count++
        }
        return count
    }
    private fun firstNalUnit(payload: ByteArray): ByteArray = nalUnitAt(payload, 0)

    private fun secondNalUnit(payload: ByteArray): ByteArray {
        val firstSize = lengthPrefix(payload, 0)
        return nalUnitAt(payload, 4 + firstSize)
    }

    private fun nalUnitAt(payload: ByteArray, offset: Int): ByteArray {
        val size = lengthPrefix(payload, offset)
        return payload.copyOfRange(offset + 4, offset + 4 + size)
    }

    private fun lengthPrefix(payload: ByteArray, offset: Int): Int =
        ((payload[offset].toInt() and 0xFF) shl 24) or
            ((payload[offset + 1].toInt() and 0xFF) shl 16) or
            ((payload[offset + 2].toInt() and 0xFF) shl 8) or
            (payload[offset + 3].toInt() and 0xFF)
}
