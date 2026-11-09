package com.droidbridge.experiment.clipboardreceive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P05 payload-limit and identity tests.
 *
 * These prove the cap is checked before allocation and that content identity is stable across
 * encodings. They are not a substitute for the physical clipboard write test.
 */
class ClipboardPayloadTest {

    @Test
    fun `zero and negative lengths are rejected`() {
        assertTrue(ClipboardPayload.tooLarge(0))
        assertTrue(ClipboardPayload.tooLarge(-1))
    }

    @Test
    fun `exactly 100 KiB is accepted`() {
        assertFalse(ClipboardPayload.tooLarge(ClipboardPayload.MAX_BYTES))
    }

    @Test
    fun `one byte over the cap is rejected`() {
        assertTrue(ClipboardPayload.tooLarge(ClipboardPayload.MAX_BYTES + 1))
    }

    @Test
    fun `hash is stable and distinguishes content`() {
        val a = ClipboardPayload.sha256Hex("p05-sample")
        val b = ClipboardPayload.sha256Hex("p05-sample")
        val c = ClipboardPayload.sha256Hex("p05-samplf")
        assertEquals(a, b)
        assertNotEquals(a, c)
    }

    @Test
    fun `hash covers UTF-8 bytes, not UTF-16 code units`() {
        val emoji = ClipboardPayload.sha256Hex("😀")
        val replacement = ClipboardPayload.sha256Hex("?")
        assertNotEquals(emoji, replacement)
    }

    @Test
    fun `short hash is twelve characters`() {
        val short = ClipboardPayload.shortHash(ClipboardPayload.sha256Hex("x"))
        assertEquals(12, short.length)
    }
}
