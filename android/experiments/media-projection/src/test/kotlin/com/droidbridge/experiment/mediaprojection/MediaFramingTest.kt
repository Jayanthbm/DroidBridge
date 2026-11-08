package com.droidbridge.experiment.mediaprojection

import com.droidbridge.core.protocol.MediaFraming
import com.droidbridge.protocol.media.MalformedStreamException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P01: exercise the shared framing rules against buffers shaped like real MediaCodec output.
 *
 * This is the part of P01 that can be verified without a device and without consent, so it is
 * verified here rather than deferred. The buffers below are hand-built Annex-B access units with
 * the NAL types a screen encoder actually emits (SPS 7, PPS 8, IDR 5, non-IDR 1), including the
 * awkward cases: a four-byte start code, trailing zero padding, and a buffer with no start code.
 *
 * What this does NOT prove: that a real encoder's buffer boundaries match these assumptions. That
 * requires a live session and is recorded as pending in the P01 report.
 */
class MediaFramingTest {

    private fun annexB(vararg nalUnits: ByteArray): ByteArray {
        val out = ArrayList<Byte>()
        nalUnits.forEach { nal ->
            // Four-byte start code, which is what MediaCodec commonly emits for the first NAL.
            out += listOf<Byte>(0, 0, 0, 1)
            out += nal.toList()
        }
        return out.toByteArray()
    }

    private fun nal(type: Int, payloadSize: Int, fill: Byte = 0x42): ByteArray {
        // First byte is forbidden_zero(1) | nal_ref_idc(2) | nal_unit_type(5).
        val header = ((type and 0x1F) or 0x60).toByte()
        return ByteArray(payloadSize) { i -> if (i == 0) header else fill }
    }

    @Test
    fun splitsAKeyframeAccessUnitIntoItsNalUnits() {
        val sps = nal(7, 12)
        val pps = nal(8, 4)
        val idr = nal(5, 200)
        val buffer = annexB(sps, pps, idr)

        val units = MediaFraming.splitNalUnits(buffer)

        assertEquals(3, units.size)
        assertEquals(7, units[0][0].toInt() and 0x1F)
        assertEquals(8, units[1][0].toInt() and 0x1F)
        assertEquals(5, units[2][0].toInt() and 0x1F)
        assertEquals(12, units[0].size)
        assertEquals(4, units[1].size)
        assertEquals(200, units[2].size)
    }

    @Test
    fun convertsAKeyframeToAvccAndFlagsItAsAKeyframe() {
        val buffer = annexB(nal(7, 12), nal(8, 4), nal(5, 200))

        val accessUnit = MediaFraming.toAvcc(buffer, ptsUs = 1_234_567L)

        assertTrue("an IDR slice must mark the access unit as a keyframe", accessUnit.isKeyframe)
        assertEquals(1_234_567L, accessUnit.ptsUs)
        // 3 NAL units, each with a 4-byte length prefix.
        assertEquals(3 * 4 + 12 + 4 + 200, accessUnit.payload.size)
        assertEquals(4, accessUnit.nalLengthSize)
    }

    @Test
    fun convertsANonIdrFrameAndDoesNotFlagItAsAKeyframe() {
        val buffer = annexB(nal(1, 120))

        val accessUnit = MediaFraming.toAvcc(buffer, ptsUs = 0L)

        assertFalse("a non-IDR slice must not be flagged as a keyframe", accessUnit.isKeyframe)
        assertEquals(4 + 120, accessUnit.payload.size)
    }

    @Test
    fun handlesThreeByteStartCodes() {
        val idr = nal(5, 40)
        val buffer = byteArrayOf(0, 0, 1) + idr

        val units = MediaFraming.splitNalUnits(buffer)

        assertEquals(1, units.size)
        assertEquals(40, units[0].size)
    }

    @Test
    fun stripsTrailingZeroPaddingBeforeTheNextStartCode() {
        // A NAL followed by zero padding, then the next start code. The padding belongs to the
        // start code, not the NAL unit, or the AVCC length prefix overstates the unit.
        val first = nal(1, 20)
        val second = nal(1, 30)
        val buffer = byteArrayOf(0, 0, 0, 1) + first + byteArrayOf(0, 0) +
            byteArrayOf(0, 0, 0, 1) + second

        val units = MediaFraming.splitNalUnits(buffer)

        assertEquals(2, units.size)
        assertEquals(20, units[0].size)
        assertEquals(30, units[1].size)
    }

    @Test
    fun rejectsABufferWithNoStartCode() {
        // A raw NAL with no start code is not an access unit. Failing loudly here is the point:
        // silently treating it as one would put a malformed frame on the wire.
        val raw = nal(1, 50)

        val failure = runCatching { MediaFraming.splitNalUnits(raw) }.exceptionOrNull()

        assertTrue(
            "expected MalformedStreamException, got $failure",
            failure is MalformedStreamException,
        )
    }

    @Test
    fun rejectsAnEmptyNalUnit() {
        // Two start codes with nothing between them.
        val buffer = byteArrayOf(0, 0, 0, 1, 0, 0, 0, 1) + nal(1, 10)

        val failure = runCatching { MediaFraming.splitNalUnits(buffer) }.exceptionOrNull()

        assertTrue(
            "expected MalformedStreamException, got $failure",
            failure is MalformedStreamException,
        )
    }

    @Test
    fun rejectsANegativeTimestamp() {
        val buffer = annexB(nal(5, 10))

        val failure = runCatching { MediaFraming.toAvcc(buffer, ptsUs = -1L) }.exceptionOrNull()

        assertTrue(
            "expected IllegalArgumentException, got $failure",
            failure is IllegalArgumentException,
        )
    }
}
