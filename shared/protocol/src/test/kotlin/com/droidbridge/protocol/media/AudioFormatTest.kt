package com.droidbridge.protocol.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AudioFormatTest {

    @Test
    fun `encodes the documented payload with deterministic key order`() {
        val format = AudioFormat(
            sampleRate = 44_100,
            channels = 2,
            bitrate = 128_000,
            audioSpecificConfig = byteArrayOf(0x12, 0x10),
        )

        assertEquals(
            """{"codec":"aac-lc","sampleRate":44100,"channels":2,"bitrate":128000,"audioSpecificConfig":"EhA="}""",
            format.toJsonPayload(),
        )
    }

    @Test
    fun `accepts mono at the lowest supported rate`() {
        val format = AudioFormat(8_000, 1, 24_000, byteArrayOf(0x15.toByte(), 0x88.toByte()))
        assertEquals("aac-lc", format.codec)
        assertTrue(format.toJsonPayload().contains("\"sampleRate\":8000"))
    }

    @Test
    fun `rejects a sample rate outside the supported range`() {
        assertFailsWith<IllegalArgumentException> {
            AudioFormat(96_000, 2, 128_000, byteArrayOf(0x12, 0x10))
        }
    }

    @Test
    fun `rejects an unsupported channel count`() {
        assertFailsWith<IllegalArgumentException> {
            AudioFormat(48_000, 6, 256_000, byteArrayOf(0x12, 0x10))
        }
    }

    @Test
    fun `rejects an empty configuration`() {
        assertFailsWith<IllegalArgumentException> {
            AudioFormat(48_000, 2, 128_000, ByteArray(0))
        }
    }
}
