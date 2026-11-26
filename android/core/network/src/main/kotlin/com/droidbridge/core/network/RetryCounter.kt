package com.droidbridge.core.network

/**
 * Consecutive failed reconnect attempts.
 *
 * The budget bounds a *streak* of failures, not a lifetime count: a peer that keeps coming back can
 * never exhaust it, because every established session proves the peer is reachable and starts the
 * streak over. The 2026-11-26 bug reset the counter only after a fresh pairing confirmation, so
 * silent stored-pin reconnects accumulated toward the cap and the phone gave up after 10 lifetime
 * drops even though every one had succeeded — the overnight P14 run had 21. Long sessions need the
 * counter to reset on *any* successful handshake, confirmed or not.
 */
internal class RetryCounter(private val maxAttempts: Int) {
    /** Consecutive failures currently charged to the budget. */
    var failures: Int = 0
        private set

    /** The attempt number to log for a connect that is about to be made. */
    fun nextAttempt(): Int = failures + 1

    /** Charges one failure. Returns false when this streak has exhausted the budget. */
    fun charge(): Boolean {
        failures += 1
        return failures <= maxAttempts
    }

    /** A successful session proves the peer is reachable; the failure streak starts over. */
    fun reset() {
        failures = 0
    }
}
