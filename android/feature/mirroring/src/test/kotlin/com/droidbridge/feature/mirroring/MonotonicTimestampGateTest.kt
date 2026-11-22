package com.droidbridge.feature.mirroring

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonotonicTimestampGateTest {

    @Test
    fun `admits strictly increasing timestamps`() {
        val gate = MonotonicTimestampGate()
        assertTrue(gate.admit(0))
        assertTrue(gate.admit(1))
        assertTrue(gate.admit(30_000))
    }

    @Test
    fun `rejects negative duplicate and decreasing timestamps without advancing state`() {
        val gate = MonotonicTimestampGate()
        assertTrue(gate.admit(10))
        assertFalse(gate.admit(-1))
        assertFalse(gate.admit(10))
        assertFalse(gate.admit(9))
        assertTrue(gate.admit(11))
    }

    @Test
    fun `reset begins a new encoder format epoch`() {
        val gate = MonotonicTimestampGate()
        assertTrue(gate.admit(50_000))
        gate.reset()
        assertTrue(gate.admit(0))
        assertFalse(gate.admit(0))
    }
}
