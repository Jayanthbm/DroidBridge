package com.droidbridge.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the retry budget as a streak of consecutive failures, not a lifetime count.
 *
 * The 2026-11-26 bug reset the counter only after a fresh pairing confirmation. Silent stored-pin
 * reconnects therefore accumulated toward the cap, and the phone gave up after 10 lifetime drops even
 * though every one succeeded — the overnight P14 run had 21. Long sessions need every successful
 * handshake, confirmed or not, to start the streak over.
 */
class RetryCounterTest {

    private val counter = RetryCounter(maxAttempts = 10)

    @Test
    fun `a real give-up still happens after ten consecutive failures`() {
        repeat(9) { assertTrue("failure ${it + 1} is within budget", counter.charge()) }
        assertEquals(9, counter.failures)
        assertTrue("the tenth failure is still allowed", counter.charge())
        assertEquals(10, counter.failures)
        assertFalse("the eleventh failure gives up", counter.charge())
    }

    @Test
    fun `a successful reconnect resets the streak`() {
        repeat(9) { counter.charge() }
        assertEquals(9, counter.failures)
        assertEquals(10, counter.nextAttempt())

        // A stored-pin reconnect succeeds with no confirmation. It must clear the streak.
        counter.reset()
        assertEquals(0, counter.failures)
        assertEquals(1, counter.nextAttempt())
        repeat(9) { assertTrue("post-reset failure ${it + 1} is within budget", counter.charge()) }
        assertTrue(counter.charge())
    }

    @Test
    fun `a session with more reconnects than the cap never gives up`() {
        // 21 successful reconnects is what the overnight P14 run measured. Each one had a failed
        // attempt before it; under the old lifetime counter this died at the tenth.
        repeat(21) {
            assertTrue("failure before reconnect ${it + 1} is within budget", counter.charge())
            counter.reset() // successful stored-pin handshake
        }
        assertEquals(0, counter.failures)
        assertTrue("a fresh failure after 21 reconnects still has the full budget", counter.charge())
    }
}
