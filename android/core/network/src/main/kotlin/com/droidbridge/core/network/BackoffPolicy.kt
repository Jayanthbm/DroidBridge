package com.droidbridge.core.network

import kotlin.math.min
import kotlin.random.Random

/**
 * Reconnect timing for Phase 2, kept as a pure function so it can be tested without a clock.
 *
 * The protocol asks for exponential backoff with jitter, a cap, and a reset on success. Jitter is
 * not decoration: without it, every client that lost the same Wi-Fi event retries in lockstep and the
 * Mac sees a synchronised stampede. The jitter is applied *downwards only* (`delay * [1-jitter, 1]`)
 * so a computed delay is an upper bound and the retry never arrives later than the policy allows.
 */
class BackoffPolicy(
    private val baseDelayMs: Long = 500,
    private val maxDelayMs: Long = 30_000,
    private val multiplier: Double = 2.0,
    private val jitterFraction: Double = 0.25,
    private val random: Random = Random.Default,
) {

    init {
        require(baseDelayMs > 0) { "baseDelayMs must be positive" }
        require(maxDelayMs >= baseDelayMs) { "maxDelayMs must not be below baseDelayMs" }
        require(multiplier >= 1.0) { "multiplier must not shrink the delay" }
        require(jitterFraction in 0.0..1.0) { "jitterFraction must be in [0, 1]" }
    }

    /**
     * Delay before retry number [attempt], where attempt 1 is the first retry after a drop.
     * The result is always within `[delay * (1 - jitter), delay]` and never exceeds [maxDelayMs].
     */
    fun delayFor(attempt: Int): Long {
        require(attempt >= 1) { "attempt is 1-based" }
        // Grow by the multiplier per attempt, but clamp in log space first: computing 2^n for a large
        // attempt overflows a Long before it can be compared against the cap.
        var delay = baseDelayMs.toDouble()
        repeat(attempt - 1) {
            if (delay >= maxDelayMs) return@repeat
            delay *= multiplier
        }
        val capped = min(delay, maxDelayMs.toDouble())
        if (jitterFraction == 0.0) return capped.toLong()
        val lowest = capped * (1.0 - jitterFraction)
        val span = capped - lowest
        val jittered = capped - random.nextDouble() * span
        return jittered.toLong().coerceIn(1L, maxDelayMs)
    }
}
