package com.droidbridge.protocol.session

import java.security.MessageDigest

/**
 * The short authentication code shown on both screens during pairing (ADR-011, NETWORK_PROTOCOL §3).
 *
 * Both sides derive the same code from the two public identities. The user compares them and
 * confirms. If a man-in-the-middle substituted its own keys, the two codes differ and the user
 * rejects — so the code's whole value is that it is a function of *both* identities.
 *
 * ## Why the inputs are sorted
 *
 * The Mac and the Android device must compute an identical code without agreeing who goes first.
 * Sorting the two identity byte strings gives a single canonical order, so neither side needs to
 * know whether it is the "client". Sorting by content, not by role, is what makes that safe: a
 * role-based order would let a peer choose the role that produces a code it prefers.
 *
 * ## Why the length is prefixed
 *
 * Concatenating two variable-length identities is ambiguous: `("ab", "c")` and `("a", "bc")` both
 * produce `"abc"`. An attacker who controls one identity could exploit that collision to make two
 * different key pairs yield the same code. Each identity is therefore length-prefixed before
 * hashing, which makes the encoding injective.
 *
 * ## What this is not
 *
 * The code is a *confirmation* value, not a key and not a secret. It is derived from public keys,
 * so anyone who observes both keys can compute it. Its only job is to let a human notice a
 * substitution. The session's confidentiality comes from TLS; the code only authenticates.
 */
object ShortAuthenticationCode {

    /** Digits in the displayed code. */
    const val DIGITS = 6

    /**
     * Domain separator.
     *
     * Without it, these same bytes could be hashed for another purpose in a future version and the
     * two values would collide. Prefixing a purpose string keeps every derived value distinct by
     * construction.
     */
    private const val DOMAIN = "droidbridge/sas/v1"

    /**
     * Derives the code from two public identities.
     *
     * @param identityA one side's public identity bytes (e.g. the DER-encoded certificate or the
     *   SubjectPublicKeyInfo; whichever the caller uses, both sides must use the same form)
     * @param identityB the other side's
     * @return exactly [DIGITS] decimal digits, zero-padded
     */
    fun derive(identityA: ByteArray, identityB: ByteArray): String {
        require(identityA.isNotEmpty()) { "identity must not be empty" }
        require(identityB.isNotEmpty()) { "identity must not be empty" }

        // Canonical order: compare by content so both sides hash the same sequence.
        val (first, second) = if (compareUnsigned(identityA, identityB) <= 0) {
            identityA to identityB
        } else {
            identityB to identityA
        }

        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(DOMAIN.toByteArray(Charsets.UTF_8))
        updateLengthPrefixed(digest, first)
        updateLengthPrefixed(digest, second)
        val hash = digest.digest()

        // Take the low 32 bits and reduce modulo 10^6.
        //
        // Modulo bias: 2^32 is not a multiple of 10^6, so the first 967_296 values of the range
        // appear one extra time. The bias is about 0.02% and irrelevant here -- the code is a
        // human comparison aid against an attacker who must also win a TLS handshake, not a
        // password -- but it is stated rather than left for a reader to wonder about.
        val value = ((hash[0].toLong() and 0xFF) shl 24) or
            ((hash[1].toLong() and 0xFF) shl 16) or
            ((hash[2].toLong() and 0xFF) shl 8) or
            (hash[3].toLong() and 0xFF)

        return (value % 1_000_000L).toString().padStart(DIGITS, '0')
    }

    /**
     * Formats a code for display as `123 456`.
     *
     * Grouped so a person can read it aloud and compare it without losing their place. Display
     * only: the canonical form is the unspaced string, and comparison always uses that.
     */
    fun formatForDisplay(code: String): String {
        require(code.length == DIGITS) { "expected a $DIGITS digit code, got ${code.length}" }
        return "${code.substring(0, 3)} ${code.substring(3)}"
    }

    /**
     * Compares two codes without leaking how far they matched.
     *
     * A short-circuiting comparison on a value a user is being asked to verify is the kind of
     * detail that costs nothing to get right.
     */
    fun matches(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) {
            diff = diff or (a[i].code xor b[i].code)
        }
        return diff == 0
    }

    private fun updateLengthPrefixed(digest: MessageDigest, value: ByteArray) {
        val length = value.size
        digest.update(
            byteArrayOf(
                ((length ushr 24) and 0xFF).toByte(),
                ((length ushr 16) and 0xFF).toByte(),
                ((length ushr 8) and 0xFF).toByte(),
                (length and 0xFF).toByte(),
            ),
        )
        digest.update(value)
    }

    /** Lexicographic comparison treating bytes as unsigned. */
    private fun compareUnsigned(a: ByteArray, b: ByteArray): Int {
        val limit = minOf(a.size, b.size)
        for (i in 0 until limit) {
            val x = a[i].toInt() and 0xFF
            val y = b[i].toInt() and 0xFF
            if (x != y) return x - y
        }
        return a.size - b.size
    }
}
