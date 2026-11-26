package com.droidbridge.protocol.media

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AudioPayloadTest {

    @Test
    fun `round-trips a frame`() {
        val unit = byteArrayOf(0x21.toByte(), 0x10, 0x05, 0x60.toByte())
        val encoded = AudioPayload.encode(ptsUs = 123_456_789L, accessUnit = unit)

        assertEquals(AudioPayload.HEADER_BYTES + unit.size, encoded.size)

        val frame = AudioPayload.decode(encoded)
        assertEquals(123_456_789L, frame.ptsUs)
        assertContentEquals(unit, frame.accessUnit)
    }

    @Test
    fun `encodes the timestamp big-endian`() {
        val encoded = AudioPayload.encode(ptsUs = 0x0102_0304_0506_0708L, accessUnit = byteArrayOf(0x01))
        assertContentEquals(byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x01), encoded)
    }

    @Test
    fun `rejects an empty access unit`() {
        assertFailsWith<IllegalArgumentException> { AudioPayload.encode(0, ByteArray(0)) }
    }

    @Test
    fun `rejects a negative timestamp`() {
        assertFailsWith<IllegalArgumentException> { AudioPayload.encode(-1, byteArrayOf(0x01)) }
    }

    @Test
    fun `rejects a payload with no access unit`() {
        assertFailsWith<MalformedStreamException> { AudioPayload.decode(ByteArray(AudioPayload.HEADER_BYTES)) }
    }

    @Test
    fun `rejects a payload shorter than the header`() {
        assertFailsWith<MalformedStreamException> { AudioPayload.decode(byteArrayOf(0x00, 0x01)) }
    }
}
