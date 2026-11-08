package com.droidbridge.protocol.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * P03: the short authentication code must be a function of both identities, and the two sides must
 * derive it independently without agreeing on an order.
 */
class ShortAuthenticationCodeTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private val mac = bytes(0x30, 0x82, 0x01, 0x0A, 0xAA, 0xBB)
    private val android = bytes(0x30, 0x82, 0x01, 0x0A, 0xCC, 0xDD)

    @Test
    fun theCodeIsSixDigits() {
        val code = ShortAuthenticationCode.derive(mac, android)
        assertEquals(6, code.length)
        assertTrue(code.all { it in '0'..'9' }, "expected digits only, got $code")
    }

    @Test
    fun bothSidesDeriveTheSameCodeRegardlessOfArgumentOrder() {
        // The Mac and the Android device must agree without either knowing who is "first".
        assertEquals(
            ShortAuthenticationCode.derive(mac, android),
            ShortAuthenticationCode.derive(android, mac),
        )
    }

    @Test
    fun theCodeIsStableAcrossCalls() {
        // If this were not deterministic, two devices could never agree.
        assertEquals(
            ShortAuthenticationCode.derive(mac, android),
            ShortAuthenticationCode.derive(mac, android),
        )
    }

    @Test
    fun aSubstitutedIdentityChangesTheCode() {
        // The entire security value: a man-in-the-middle swapping in its own key must change what
        // the user sees, or the confirmation step is theatre.
        val genuine = ShortAuthenticationCode.derive(mac, android)
        val mitm = bytes(0x30, 0x82, 0x01, 0x0A, 0xAA, 0xBC)

        assertNotEquals(genuine, ShortAuthenticationCode.derive(mac, mitm))
    }

    @Test
    fun changingEitherIdentityChangesTheCode() {
        val base = ShortAuthenticationCode.derive(mac, android)
        val otherMac = bytes(0x30, 0x82, 0x01, 0x0A, 0xAA, 0xBC)

        assertNotEquals(base, ShortAuthenticationCode.derive(otherMac, android))
        assertNotEquals(base, ShortAuthenticationCode.derive(mac, otherMac))
    }

    @Test
    fun aLengthAmbiguousPairingDoesNotCollide() {
        // Without length prefixes, ("ab","c") and ("a","bc") both concatenate to "abc" and would
        // produce the same code, letting an attacker pick a key pair that matches a target code.
        val left = ShortAuthenticationCode.derive(bytes(0x61, 0x62), bytes(0x63))
        val right = ShortAuthenticationCode.derive(bytes(0x61), bytes(0x62, 0x63))

        assertNotEquals(left, right)
    }

    @Test
    fun theCodeIsZeroPadded() {
        // A code below 100000 must still be six characters, or the two screens show different
        // things and the user rejects a valid pairing.
        for (i in 0 until 200) {
            val code = ShortAuthenticationCode.derive(bytes(i), bytes(i + 1))
            assertEquals(6, code.length, "code for $i was '$code'")
        }
    }

    @Test
    fun anEmptyIdentityIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            ShortAuthenticationCode.derive(ByteArray(0), android)
        }
        assertFailsWith<IllegalArgumentException> {
            ShortAuthenticationCode.derive(mac, ByteArray(0))
        }
    }

    @Test
    fun anIdenticalIdentityPairStillProducesACode() {
        // Degenerate but must not throw: the two sides would show the same code, which is a
        // pairing the user should reject, not a crash.
        val code = ShortAuthenticationCode.derive(mac, mac)
        assertEquals(6, code.length)
    }

    @Test
    fun theCodeIsNotTriviallyDerivedFromOneIdentity() {
        // A code that depended only on one side would not detect a substitution on the other.
        val a = ShortAuthenticationCode.derive(mac, android)
        val b = ShortAuthenticationCode.derive(mac, bytes(0x01, 0x02, 0x03))
        assertNotEquals(a, b)
    }

    @Test
    fun displayFormattingGroupsTheDigits() {
        assertEquals("123 456", ShortAuthenticationCode.formatForDisplay("123456"))
    }

    @Test
    fun displayFormattingRejectsTheWrongLength() {
        assertFailsWith<IllegalArgumentException> {
            ShortAuthenticationCode.formatForDisplay("12345")
        }
    }

    @Test
    fun matchingComparesContent() {
        assertTrue(ShortAuthenticationCode.matches("123456", "123456"))
        assertFalse(ShortAuthenticationCode.matches("123456", "123457"))
        assertFalse(ShortAuthenticationCode.matches("123456", "12345"))
        assertFalse(ShortAuthenticationCode.matches("12345", "123456"))
    }

    @Test
    fun theDistributionIsNotPathological() {
        // Not a statistical test -- just a guard against a derivation that collapses to a handful
        // of values, which would make the code useless as a comparison.
        val codes = (0 until 500).map { ShortAuthenticationCode.derive(bytes(it and 0xFF, it shr 8), android) }
        assertTrue(codes.toSet().size > 400, "only ${codes.toSet().size} distinct codes in 500 draws")
    }
}
