package com.droidbridge.protocol.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * `device.info` payload tests (NETWORK_PROTOCOL §7).
 *
 * The JSON is asserted as a literal because the Swift parser has its own test against the same
 * string: two hand-written codecs that only round-trip against themselves can agree with themselves
 * and still disagree on the wire.
 */
class DeviceInfoTest {

    private fun sample() = DeviceInfo(
        manufacturer = "samsung",
        model = "SM-S928B",
        androidRelease = "17",
        sdkInt = 37,
        appVersion = "0.1.0",
    )

    @Test
    fun `encodes the documented key order`() {
        assertEquals(
            "{\"manufacturer\":\"samsung\",\"model\":\"SM-S928B\",\"androidRelease\":\"17\"," +
                "\"sdkInt\":37,\"appVersion\":\"0.1.0\"}",
            sample().toJsonPayload(),
        )
    }

    @Test
    fun `escapes quotes and backslashes in a device-controlled string`() {
        val payload = DeviceInfo(
            manufacturer = "acme",
            model = "X\"1\\\\2",
            androidRelease = "17",
            sdkInt = 37,
            appVersion = "0.1.0",
        ).toJsonPayload()
        assertEquals(true, payload.contains("X\\\"1\\\\\\\\2"))
    }

    @Test
    fun `display name joins manufacturer and model`() {
        assertEquals("samsung SM-S928B", sample().displayName)
    }

    @Test
    fun `refuses blank fields`() {
        assertFailsWith<IllegalArgumentException> {
            DeviceInfo("", "SM-S928B", "17", 37, "0.1.0")
        }
        assertFailsWith<IllegalArgumentException> {
            DeviceInfo("samsung", "  ", "17", 37, "0.1.0")
        }
    }

    @Test
    fun `refuses an impossible api level`() {
        assertFailsWith<IllegalArgumentException> {
            DeviceInfo("samsung", "SM-S928B", "17", 0, "0.1.0")
        }
    }

    @Test
    fun `refuses an over-long field`() {
        assertFailsWith<IllegalArgumentException> {
            DeviceInfo("samsung", "x".repeat(DeviceInfo.MAX_FIELD_CHARS + 1), "17", 37, "0.1.0")
        }
    }
}