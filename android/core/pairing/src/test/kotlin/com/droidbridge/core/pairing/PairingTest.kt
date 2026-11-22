package com.droidbridge.core.pairing

import com.droidbridge.core.pairing.Pairing.Event
import com.droidbridge.core.pairing.Pairing.Failure
import com.droidbridge.core.pairing.Pairing.Phase
import com.droidbridge.core.pairing.Pairing.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the rule that matters: a peer is not trusted until the user has compared the code.
 *
 * Each test names the failure it prevents, because "the state machine does something" is not a useful
 * assertion — the value is in the transitions that must **not** happen.
 */
class PairingTest {

    private fun connect(): State =
        Pairing.reduce(State(phase = Phase.DISCOVERING), Event.ConnectRequested(hasStoredPin = false))

    @Test
    fun `first pairing stops at confirmation and cannot exchange data`() {
        val established = Pairing.reduce(
            connect(),
            Event.SessionEstablished(requiresConfirmation = true, code = "482913"),
        )

        assertEquals(Phase.CONFIRMING, established.phase)
        assertEquals("482913", established.code)
        assertTrue(established.awaitsUserDecision)
        assertFalse("an unconfirmed peer must not carry application data", established.canExchangeData)
    }

    @Test
    fun `confirmation is the only path from confirming to connected`() {
        val confirming = Pairing.reduce(
            connect(),
            Event.SessionEstablished(requiresConfirmation = true, code = "482913"),
        )

        assertEquals(Phase.CONNECTED, Pairing.reduce(confirming, Event.Confirmed).phase)
        assertEquals(Phase.FAILED, Pairing.reduce(confirming, Event.Rejected).phase)
        assertEquals(Failure.CODE_REJECTED, Pairing.reduce(confirming, Event.Rejected).failure)
    }

    @Test
    fun `a stale confirm callback cannot trust an already connected peer`() {
        val connected = State(phase = Phase.CONNECTED)

        assertEquals(connected, Pairing.reduce(connected, Event.Confirmed))
    }

    @Test
    fun `confirmation without a code fails closed rather than showing an empty prompt`() {
        val broken = Pairing.reduce(
            connect(),
            Event.SessionEstablished(requiresConfirmation = true, code = null),
        )

        assertEquals(Phase.FAILED, broken.phase)
        assertEquals(Failure.HANDSHAKE, broken.failure)
        assertFalse(broken.canExchangeData)
        assertNull(broken.code)
    }

    @Test
    fun `a stored pin goes straight to connected without a prompt`() {
        val established = Pairing.reduce(
            Pairing.reduce(State(phase = Phase.DISCOVERING), Event.ConnectRequested(hasStoredPin = true)),
            Event.SessionEstablished(requiresConfirmation = false, code = null),
        )

        assertEquals(Phase.CONNECTED, established.phase)
        assertTrue(established.canExchangeData)
        assertNull("re-pairing must not show a code the user would learn to click through", established.code)
    }

    @Test
    fun `connecting cannot interrupt a live session`() {
        val connected = State(phase = Phase.CONNECTED)

        assertEquals(connected, Pairing.reduce(connected, Event.ConnectRequested(hasStoredPin = true)))
    }

    @Test
    fun `a drop while confirming returns to reconnecting without silently trusting the peer`() {
        val confirming = Pairing.reduce(
            connect(),
            Event.SessionEstablished(requiresConfirmation = true, code = "482913"),
        )

        val dropped = Pairing.reduce(confirming, Event.Disconnected(attempt = 1, retryable = true))

        assertEquals(Phase.RECONNECTING, dropped.phase)
        assertEquals(1, dropped.attempt)
        assertFalse(dropped.canExchangeData)
        assertNull("the old code must not survive a reconnect", dropped.code)
    }

    @Test
    fun `the retry attempt counter survives the reconnect cycle and resets on success`() {
        val dropped = Pairing.reduce(State(phase = Phase.CONNECTED), Event.Disconnected(3, retryable = true))
        val retrying = Pairing.reduce(dropped, Event.Retrying)
        assertEquals(Phase.CONNECTING, retrying.phase)
        assertEquals(3, retrying.attempt)

        val back = Pairing.reduce(retrying, Event.SessionEstablished(requiresConfirmation = false, code = null))
        assertEquals(0, back.attempt)
    }

    @Test
    fun `an unrecoverable drop gives up instead of retrying forever`() {
        val state = Pairing.reduce(State(phase = Phase.CONNECTED), Event.Disconnected(attempt = 9, retryable = false))

        assertEquals(Phase.FAILED, state.phase)
        assertEquals(Failure.GAVE_UP, state.failure)
    }

    @Test
    fun `discovery cannot restart over a live session`() {
        val connected = State(phase = Phase.CONNECTED)

        assertEquals(connected, Pairing.reduce(connected, Event.DiscoveryStarted))
    }

    @Test
    fun `stopping clears code and failure so a restart does not inherit them`() {
        val failed = State(phase = Phase.FAILED, failure = Failure.PIN_MISMATCH, code = "111111")

        assertEquals(State(phase = Phase.IDLE), Pairing.reduce(failed, Event.Stopped))
    }
}
