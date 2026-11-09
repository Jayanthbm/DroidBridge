package com.droidbridge.protocol.session

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Control envelope and replay-window tests.
 *
 * The wire bytes are pinned as hex so the Swift implementation can assert the same literals; two
 * hand-written codecs that only test themselves can agree with themselves and still disagree on the
 * wire.
 */
class ControlMessageTest {

    @Test
    fun `encode produces the documented wire shape`() {
        val bytes = ControlMessage.encode(ControlMessage.Type.PING, 1L)
        assertEquals(
            "010100000001",
            bytes.joinToString("") { "%02x".format(it) },
        )
    }

    @Test
    fun `encode carries a payload after the header`() {
        val bytes = ControlMessage.encode(
            ControlMessage.Type.PONG,
            0x01020304L,
            "ok".toByteArray(),
        )
        assertEquals("0102010203046f6b", bytes.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun `decode round-trips type sequence and payload`() {
        val original = ControlMessage.Message(
            ControlMessage.Type.PONG,
            42L,
            byteArrayOf(0, 1, 2, 3, 0xFF.toByte()),
        )
        val decoded = ControlMessage.decode(
            ControlMessage.encode(original.type, original.sequence, original.payload),
        )
        assertEquals(original, decoded)
        assertContentEquals(original.payload, decoded.payload)
    }

    @Test
    fun `max legal sequence survives the round trip`() {
        val decoded = ControlMessage.decode(
            ControlMessage.encode(ControlMessage.Type.CLOSE, 0xFFFF_FFFFL),
        )
        assertEquals(0xFFFF_FFFFL, decoded.sequence)
    }

    @Test
    fun `sequence zero is rejected`() {
        val encoded = ControlMessage.encode(ControlMessage.Type.PING, 1L)
        encoded[2] = 0
        encoded[3] = 0
        encoded[4] = 0
        encoded[5] = 0
        val error = assertFailsWith<FramingException> { ControlMessage.decode(encoded) }
        assertTrue(error.message!!.contains("start at 1"))
    }

    @Test
    fun `unknown version is rejected rather than upgraded`() {
        val encoded = ControlMessage.encode(ControlMessage.Type.PING, 1L)
        encoded[0] = 2
        assertFailsWith<FramingException> { ControlMessage.decode(encoded) }
    }

    @Test
    fun `unknown type is rejected`() {
        val encoded = ControlMessage.encode(ControlMessage.Type.PING, 1L)
        encoded[1] = 0x7F
        assertFailsWith<FramingException> { ControlMessage.decode(encoded) }
    }

    @Test
    fun `truncated header is rejected`() {
        assertFailsWith<FramingException> {
            ControlMessage.decode(byteArrayOf(1, 1, 0, 0, 0))
        }
    }

    @Test
    fun `over-long payload is refused before encoding`() {
        val tooLarge = ByteArray(ControlMessage.MAX_PAYLOAD_BYTES + 1)
        assertFailsWith<IllegalArgumentException> {
            ControlMessage.encode(ControlMessage.Type.PING, 1L, tooLarge)
        }
    }

    @Test
    fun `replay window accepts an increasing stream`() {
        val window = ReplayWindow()
        window.accept(1)
        window.accept(2)
        window.accept(9)
        assertEquals(9, window.highest)
    }

    @Test
    fun `replay window rejects a duplicate`() {
        val window = ReplayWindow()
        window.accept(5)
        val error = assertFailsWith<ReplayException> { window.accept(5) }
        assertTrue(error.message!!.contains("replayed"))
    }

    @Test
    fun `replay window rejects a rewind`() {
        val window = ReplayWindow()
        window.accept(5)
        assertFailsWith<ReplayException> { window.accept(4) }
    }

    @Test
    fun `control describe never leaks binary payloads wholesale`() {
        val binary = ControlMessage.Message(
            ControlMessage.Type.PING,
            7,
            byteArrayOf(0x00, 0x01, 0x02),
        )
        assertTrue(ControlMessage.describe(binary).endsWith("<3 bytes>"))
    }
}
