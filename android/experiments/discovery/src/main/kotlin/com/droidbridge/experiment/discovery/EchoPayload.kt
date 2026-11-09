package com.droidbridge.experiment.discovery

/**
 * The P02 echo payload, kept pure so the framing rule can be unit-tested.
 *
 * A line is `P02 <sequence> <sendNanos>`. The Mac echoes bytes verbatim, so an exact match proves
 * the return path carried the same bytes; the timestamp gives a round-trip latency without a
 * shared clock. Anything else — a different sequence, a truncated line, a substituted payload — is
 * a mismatch and must be counted, not silently accepted.
 */
object EchoPayload {

    private const val PREFIX = "P02"

    fun encode(sequence: Int, sendNanos: Long): String = "$PREFIX $sequence $sendNanos"

    /** True only when the reply is byte-for-byte the payload that was sent. */
    fun isExactEcho(reply: String?, sequence: Int, sendNanos: Long): Boolean =
        reply == encode(sequence, sendNanos)

    /**
     * Parses a received line back into its sequence and timestamp.
     *
     * Returns `null` for anything that is not exactly three fields with the expected prefix and
     * numeric values. The echo test does not need this, but it makes malformed replies testable
     * and gives a future receiver a single validation point.
     */
    fun parse(line: String?): Pair<Int, Long>? {
        val parts = line?.trim()?.split(' ') ?: return null
        if (parts.size != 3 || parts[0] != PREFIX) return null
        val sequence = parts[1].toIntOrNull() ?: return null
        val nanos = parts[2].toLongOrNull() ?: return null
        if (sequence < 0 || nanos < 0) return null
        return sequence to nanos
    }
}
