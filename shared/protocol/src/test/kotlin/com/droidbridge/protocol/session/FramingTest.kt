package com.droidbridge.protocol.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P03: framing must reject a bad length before it allocates anything.
 *
 * The length prefix is attacker-controlled. Every test here is about what happens when a peer lies
 * about how much it is about to send.
 */
class FramingTest {

    // --- length encoding -------------------------------------------------------------------

    @Test
    fun aLengthPrefixRoundTrips() {
        for (length in listOf(1, 255, 256, 65_535, 65_536, 1 shl 20)) {
            val header = Framing.encodeLength(length)
            assertEquals(4, header.size)
            assertEquals(length.toLong(), Framing.decodeLength(header), "length $length")
        }
    }

    @Test
    fun aLengthPrefixIsBigEndian() {
        // 0x01020304 must appear on the wire in that order, not reversed.
        val header = Framing.encodeLength(0x01020304)
        assertEquals(listOf(1, 2, 3, 4), header.map { it.toInt() and 0xFF })
    }

    @Test
    fun aHighBitLengthIsUnsignedNotNegative() {
        // The bug this guards: reading the prefix as a signed Int turns 0xFFFFFFFF into -1, and
        // every "> max" check then passes. The unsigned value is what makes the bound real.
        val header = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        assertEquals(4_294_967_295L, Framing.decodeLength(header))
    }

    @Test
    fun aZeroLengthFrameIsRejected() {
        assertFailsWith<FramingException> { Framing.validateLength(0) }
    }

    @Test
    fun anOversizedLengthIsRejectedWithTheDeclaredValue() {
        val error = assertFailsWith<FrameTooLargeException> {
            Framing.validateLength(2L * 1024 * 1024 * 1024)
        }
        assertEquals(2L * 1024 * 1024 * 1024, error.declared)
        assertEquals(Framing.MAX_CONTROL_PAYLOAD_BYTES, error.maxPayloadBytes)
    }

    @Test
    fun theLimitItselfIsAcceptedAndOnePastItIsNot() {
        Framing.validateLength(Framing.MAX_CONTROL_PAYLOAD_BYTES.toLong())
        assertFailsWith<FrameTooLargeException> {
            Framing.validateLength(Framing.MAX_CONTROL_PAYLOAD_BYTES + 1L)
        }
    }

    // --- frame encoding --------------------------------------------------------------------

    @Test
    fun aFrameIsPrefixPlusPayload() {
        val payload = "hello".toByteArray()
        val frame = Framing.encodeFrame(payload)

        assertEquals(4 + payload.size, frame.size)
        assertEquals(payload.size.toLong(), Framing.decodeLength(frame))
        assertEquals("hello", frame.copyOfRange(4, frame.size).toString(Charsets.UTF_8))
    }

    @Test
    fun anEmptyPayloadIsNotAFrame() {
        assertFailsWith<FramingException> { Framing.encodeFrame(ByteArray(0)) }
    }

    // --- incremental reading ---------------------------------------------------------------

    @Test
    fun aWholeFrameInOneChunkIsEmitted() {
        val reader = FrameReader()
        val frames = reader.feed(Framing.encodeFrame("abc".toByteArray()))

        assertEquals(1, frames.size)
        assertEquals("abc", frames[0].toString(Charsets.UTF_8))
        assertEquals(0, reader.pendingBytes())
    }

    @Test
    fun aFrameSplitAcrossReadsIsReassembled() {
        // TCP gives a stream, not messages: the header can arrive one byte at a time.
        val reader = FrameReader()
        val frame = Framing.encodeFrame("split-me".toByteArray())

        val emitted = mutableListOf<ByteArray>()
        for (byte in frame) {
            emitted += reader.feed(byteArrayOf(byte))
        }

        assertEquals(1, emitted.size)
        assertEquals("split-me", emitted[0].toString(Charsets.UTF_8))
    }

    @Test
    fun severalFramesInOneChunkAreAllEmittedInOrder() {
        val reader = FrameReader()
        val chunk = Framing.encodeFrame("one".toByteArray()) +
            Framing.encodeFrame("two".toByteArray()) +
            Framing.encodeFrame("three".toByteArray())

        val frames = reader.feed(chunk)

        assertEquals(listOf("one", "two", "three"), frames.map { it.toString(Charsets.UTF_8) })
    }

    @Test
    fun aTrailingPartialFrameIsHeldUntilCompleted() {
        val reader = FrameReader()
        val complete = Framing.encodeFrame("first".toByteArray())
        val partial = Framing.encodeFrame("second".toByteArray())

        // Feed the first frame plus the first two bytes of the next one's header.
        val frames = reader.feed(complete + partial.copyOfRange(0, 2))

        assertEquals(1, frames.size)
        assertEquals("first", frames[0].toString(Charsets.UTF_8))
        assertEquals(2, reader.pendingBytes())

        // The rest completes it.
        val rest = reader.feed(partial.copyOfRange(2, partial.size))
        assertEquals(1, rest.size)
        assertEquals("second", rest[0].toString(Charsets.UTF_8))
    }

    @Test
    fun anOversizedHeaderIsRejectedWithoutWaitingForThePayload() {
        // The decisive case: a peer announces 2 GiB and sends nothing more. The reader must fail
        // on the header alone, not block forever and not reserve 2 GiB.
        val reader = FrameReader()
        val header = byteArrayOf(0x80.toByte(), 0x00, 0x00, 0x00) // 2 GiB

        val error = assertFailsWith<FrameTooLargeException> { reader.feed(header) }

        assertEquals(2L * 1024 * 1024 * 1024, error.declared)
        assertTrue(reader.isFailed)
    }

    @Test
    fun aReaderThatFailedStaysFailed() {
        // Resynchronising after a framing error is how a decoder starts reading payload bytes as
        // headers. The reader must refuse every later feed instead.
        val reader = FrameReader()
        assertFailsWith<FrameTooLargeException> {
            reader.feed(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()))
        }

        assertTrue(reader.isFailed)
        assertFailsWith<FramingException> {
            reader.feed(Framing.encodeFrame("valid".toByteArray()))
        }
    }

    @Test
    fun aZeroLengthHeaderIsRejected() {
        val reader = FrameReader()
        assertFailsWith<FramingException> {
            reader.feed(byteArrayOf(0, 0, 0, 0))
        }
        assertTrue(reader.isFailed)
    }

    @Test
    fun aCustomLimitIsHonoured() {
        val reader = FrameReader(maxPayloadBytes = 8)
        assertFailsWith<FrameTooLargeException> {
            reader.feed(Framing.encodeFrame(ByteArray(9) { 1 }, maxPayloadBytes = 16))
        }
    }

    @Test
    fun aPayloadAtTheLimitIsAccepted() {
        val reader = FrameReader(maxPayloadBytes = 8)
        val frames = reader.feed(Framing.encodeFrame(ByteArray(8) { 7 }, maxPayloadBytes = 8))
        assertEquals(1, frames.size)
        assertEquals(8, frames[0].size)
    }

    @Test
    fun aBinaryPayloadSurvivesUnchanged() {
        // Payloads are not text. Every byte value must round-trip, including 0x00 and 0xFF.
        val payload = ByteArray(256) { it.toByte() }
        val frames = FrameReader().feed(Framing.encodeFrame(payload))

        assertEquals(1, frames.size)
        assertTrue(payload.contentEquals(frames[0]))
    }

    @Test
    fun aFailedReaderDoesNotReportSuccess() {
        val reader = FrameReader()
        assertFalse(reader.isFailed)
        assertFailsWith<FramingException> { reader.feed(byteArrayOf(0, 0, 0, 0)) }
        assertTrue(reader.isFailed)
    }
}
