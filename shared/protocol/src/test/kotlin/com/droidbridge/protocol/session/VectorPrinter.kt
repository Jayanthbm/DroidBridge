package com.droidbridge.protocol.session

import kotlin.test.Test

/** Prints the cross-language SAS vectors so the Swift side can assert the same values. */
class VectorPrinter {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun printVectors() {
        val vectors = listOf(
            Triple("mac-v1", "00112233445566778899aabbccddeeff", "ffeeddccbbaa99887766554433221100"),
            Triple("android-v1", "3082010a0a0b0c0d0e0f", "3082010a0a0b0c0d0e10"),
            Triple("short-ids", "01", "02"),
            Triple("length-ambiguity-a", "6162", "63"),
            Triple("length-ambiguity-b", "61", "6263"),
            Triple("long", "aa".repeat(64), "bb".repeat(64)),
        )
        for ((name, a, b) in vectors) {
            println("VECTOR|$name|$a|$b|${ShortAuthenticationCode.derive(hex(a), hex(b))}")
        }
    }
}
