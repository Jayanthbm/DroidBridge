package com.droidbridge.experiment.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P02 echo framing tests.
 *
 * These prove the matching rule, not that a real socket carried the bytes — that is physical-device
 * evidence. Keeping the distinction explicit prevents a unit test from being cited as a network
 * result.
 */
class EchoPayloadTest {

    @Test
    fun `encode is stable and parseable`() {
        val payload = EchoPayload.encode(7, 1_234_567_890L)
        assertEquals("P02 7 1234567890", payload)
        assertEquals(7 to 1_234_567_890L, EchoPayload.parse(payload))
    }

    @Test
    fun `exact echo is accepted`() {
        val payload = EchoPayload.encode(42, 9_999)
        assertTrue(EchoPayload.isExactEcho(payload, 42, 9_999))
    }

    @Test
    fun `echo with a different sequence is rejected`() {
        val payload = EchoPayload.encode(42, 9_999)
        assertFalse(EchoPayload.isExactEcho(payload, 43, 9_999))
    }

    @Test
    fun `echo with a different timestamp is rejected`() {
        val payload = EchoPayload.encode(42, 9_999)
        assertFalse(EchoPayload.isExactEcho(payload, 42, 10_000))
    }

    @Test
    fun `null and truncated replies are rejected`() {
        assertFalse(EchoPayload.isExactEcho(null, 1, 2))
        assertFalse(EchoPayload.isExactEcho("P02 1", 1, 2))
    }

    @Test
    fun `malformed payloads do not parse`() {
        assertNull(EchoPayload.parse(null))
        assertNull(EchoPayload.parse(""))
        assertNull(EchoPayload.parse("P02 1"))
        assertNull(EchoPayload.parse("P02 1 notanumber"))
        assertNull(EchoPayload.parse("XX 1 2"))
        assertNull(EchoPayload.parse("P02 -1 2"))
    }

    @Test
    fun `surrounding whitespace is tolerated by parse but not by exact echo`() {
        assertEquals(1 to 2L, EchoPayload.parse("P02 1 2\n"))
        assertFalse(EchoPayload.isExactEcho("P02 1 2\n", 1, 2))
    }
}
