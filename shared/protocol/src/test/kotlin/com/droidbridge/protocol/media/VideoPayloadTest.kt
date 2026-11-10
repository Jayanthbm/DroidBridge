package com.droidbridge.protocol.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class VideoPayloadTest {

    private val accessUnit = AnnexBToAvcc.convertAccessUnit(Vectors.KEYFRAME_ACCESS_UNIT, ptsUs = 0)

    @Test
    fun `round trips a frame with its timestamp`() {
        val payload = VideoPayload.encode(1_234_567L, accessUnit.payload)
        val decoded = VideoPayload.decode(payload)

        assertEquals(1_234_567L, decoded.ptsUs)
        assertBytesEqual(accessUnit.payload, decoded.avcc, "AVCC survives the round trip")
    }

    @Test
    fun `writes the timestamp big-endian in the first eight bytes`() {
        // 0x0000_0000_0001_E240 is 123456, so the byte layout is checkable by eye.
        val payload = VideoPayload.encode(123_456L, accessUnit.payload)

        assertEquals(
            "00 00 00 00 00 01 e2 40",
            payload.take(8).toByteArray().toHex(),
        )
        assertEquals(VideoPayload.HEADER_BYTES + accessUnit.payload.size, payload.size)
    }

    @Test
    fun `preserves emulation prevention bytes in the payload`() {
        // The bytes are opaque to this layer, and a "helpful" removal here would corrupt the slice.
        val payload = VideoPayload.encode(0, accessUnit.payload)
        val decoded = VideoPayload.decode(payload)

        assertTrue(
            decoded.avcc.toHex().contains("00 00 03"),
            "emulation prevention bytes must survive: ${decoded.avcc.toHex()}",
        )
    }

    @Test
    fun `rejects an empty access unit`() {
        assertFailsWith<IllegalArgumentException> { VideoPayload.encode(0, ByteArray(0)) }
    }

    @Test
    fun `rejects a negative timestamp on encode`() {
        assertFailsWith<IllegalArgumentException> { VideoPayload.encode(-1, accessUnit.payload) }
    }

    @Test
    fun `rejects a payload with no AVCC bytes`() {
        // Eight bytes is exactly the header: a valid timestamp with no frame behind it.
        val headerOnly = VideoPayload.encode(0, accessUnit.payload).copyOfRange(0, 8)

        assertFailsWith<MalformedStreamException> { VideoPayload.decode(headerOnly) }
    }

    @Test
    fun `rejects a NAL length that runs past the payload`() {
        // A 40-byte NAL declared in an 8-byte access unit: the decoder would read past the buffer.
        val lying = hex("00 00 00 28 65 88")

        assertFailsWith<MalformedStreamException> { VideoPayload.decode(header(0) + lying) }
    }

    @Test
    fun `rejects a zero-length NAL unit`() {
        val zeroNal = hex("00 00 00 00")

        assertFailsWith<MalformedStreamException> { VideoPayload.decode(header(0) + zeroNal) }
    }

    @Test
    fun `rejects trailing bytes that are not a length prefix`() {
        // One good NAL followed by two orphan bytes.
        val trailing = accessUnit.payload + hex("00 01")

        assertFailsWith<MalformedStreamException> { VideoPayload.decode(header(0) + trailing) }
    }

    @Test
    fun `rejects a timestamp with the sign bit set`() {
        val signed = ByteArray(8) { 0xFF.toByte() } + accessUnit.payload

        assertFailsWith<MalformedStreamException> { VideoPayload.decode(signed) }
    }

    @Test
    fun `accepts a frame whose timestamp is zero`() {
        // The first frame of a producer session legitimately has ptsUs == 0.
        val decoded = VideoPayload.decode(VideoPayload.encode(0, accessUnit.payload))

        assertEquals(0L, decoded.ptsUs)
    }

    private fun header(ptsUs: Long): ByteArray = VideoPayload.encode(ptsUs, accessUnit.payload).copyOfRange(0, 8)
}
