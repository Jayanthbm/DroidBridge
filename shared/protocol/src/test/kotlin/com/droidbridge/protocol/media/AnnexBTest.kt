package com.droidbridge.protocol.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AnnexBTest {

    @Test
    fun `splits a stream that mixes four byte and three byte start codes`() {
        val stream = hex("00 00 00 01") + Vectors.SPS +
            hex("00 00 01") + Vectors.PPS +
            hex("00 00 00 01") + Vectors.IDR

        val nalUnits = AnnexB.splitNalUnits(stream)

        assertEquals(3, nalUnits.size)
        assertBytesEqual(Vectors.SPS, nalUnits[0], "first NAL unit")
        assertBytesEqual(Vectors.PPS, nalUnits[1], "second NAL unit")
        assertBytesEqual(Vectors.IDR, nalUnits[2], "third NAL unit")
    }

    @Test
    fun `does not leave a stray zero byte when the start code is four bytes`() {
        val nalUnits = AnnexB.splitNalUnits(hex("00 00 00 01") + Vectors.SPS)

        assertEquals(1, nalUnits.size)
        assertEquals(0x67, nalUnits[0][0].toInt() and 0xFF, "NAL unit must begin with the SPS header byte")
        assertEquals(Vectors.SPS.size, nalUnits[0].size, "a 0x00 would add a byte")
    }

    @Test
    fun `trims trailing zero padding before the next start code`() {
        val stream = hex("00 00 01") + Vectors.NON_IDR + hex("00 00 00 00 01") + Vectors.AUD

        val nalUnits = AnnexB.splitNalUnits(stream)

        assertEquals(2, nalUnits.size)
        assertBytesEqual(Vectors.NON_IDR, nalUnits[0], "padded NAL unit")
        assertBytesEqual(Vectors.AUD, nalUnits[1], "following NAL unit")
    }

    @Test
    fun `tolerates zero padding before the first start code`() {
        val nalUnits = AnnexB.splitNalUnits(hex("00 00 00 00 01") + Vectors.AUD)

        assertEquals(1, nalUnits.size)
        assertBytesEqual(Vectors.AUD, nalUnits[0])
    }

    @Test
    fun `rejects non-zero bytes before the first start code`() {
        val failure = assertFailsWith<MalformedStreamException> {
            AnnexB.splitNalUnits(hex("ff 00 00 00 01") + Vectors.AUD)
        }
        assertEquals(true, failure.message?.contains("before the first start code"), failure.message)
    }

    @Test
    fun `rejects an empty NAL unit between two start codes`() {
        assertFailsWith<MalformedStreamException> {
            AnnexB.splitNalUnits(hex("00 00 01 00 00 01") + Vectors.AUD)
        }
    }

    @Test
    fun `rejects a buffer with no start code`() {
        assertFailsWith<MalformedStreamException> {
            AnnexB.splitNalUnits(Vectors.PPS)
        }
    }

    @Test
    fun `rejects an empty buffer`() {
        assertFailsWith<MalformedStreamException> {
            AnnexB.splitNalUnits(ByteArray(0))
        }
    }
}
