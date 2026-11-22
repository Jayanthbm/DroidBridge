package com.droidbridge.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class BackoffPolicyTest {

    @Test
    fun `delays grow exponentially and stop at the cap`() {
        val policy = BackoffPolicy(baseDelayMs = 500, maxDelayMs = 30_000, jitterFraction = 0.0)
        val delays = (1..8).map { policy.delayFor(it) }

        assertEquals(listOf(500L, 1000L, 2000L, 4000L, 8000L, 16_000L, 30_000L, 30_000L), delays)
    }

    @Test
    fun `a huge attempt count does not overflow into a negative delay`() {
        val policy = BackoffPolicy(baseDelayMs = 500, maxDelayMs = 30_000, jitterFraction = 0.0)

        val delay = policy.delayFor(64)

        assertEquals(30_000L, delay)
    }

    @Test
    fun `jitter never exceeds the computed delay and never returns zero`() {
        val policy = BackoffPolicy(
            baseDelayMs = 1000,
            maxDelayMs = 30_000,
            jitterFraction = 0.25,
            random = Random(seed = 7),
        )

        repeat(200) {
            val attempt = 1 + it % 12
            val delay = policy.delayFor(attempt)
            val ceiling = BackoffPolicy(1000, 30_000, jitterFraction = 0.0).delayFor(attempt)

            assertTrue("delay $delay must be at least 1 ms", delay >= 1)
            assertTrue("delay $delay must not exceed the un-jittered $ceiling", delay <= ceiling)
            assertTrue(
                "delay $delay must stay within 25% below $ceiling",
                delay >= ceiling * 3 / 4,
            )
        }
    }

    @Test
    fun `jittered retries are not identical, so clients do not stampede`() {
        val policy = BackoffPolicy(baseDelayMs = 1000, maxDelayMs = 30_000, jitterFraction = 0.25)

        val samples = (1..40).map { policy.delayFor(4) }.toSet()

        assertTrue("expected varied delays, got $samples", samples.size > 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `attempt zero is rejected rather than silently treated as the first retry`() {
        BackoffPolicy().delayFor(0)
    }
}
