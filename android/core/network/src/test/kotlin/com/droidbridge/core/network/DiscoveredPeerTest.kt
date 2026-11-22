package com.droidbridge.core.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The compatibility rule for discovered peers.
 *
 * A peer that publishes no version is accepted (older builds predate the TXT record), but a peer that
 * publishes a version this build does not speak must never be dialled: the protocol says an unknown
 * version is not guessed at.
 */
class DiscoveredPeerTest {

    private fun peer(version: Int?) = ServiceDiscovery.Peer(
        serviceName = "DroidBridge-P08-Mac",
        host = "192.168.31.109",
        port = 59649,
        protocolVersion = version,
    )

    @Test
    fun `a matching protocol version is compatible`() {
        assertTrue(peer(ServiceDiscovery.SUPPORTED_PROTOCOL_VERSION).isCompatible)
    }

    @Test
    fun `a different protocol version is not compatible`() {
        assertFalse(peer(ServiceDiscovery.SUPPORTED_PROTOCOL_VERSION + 1).isCompatible)
        assertFalse(peer(0).isCompatible)
    }

    @Test
    fun `a peer that publishes no version is treated as compatible`() {
        assertTrue(peer(null).isCompatible)
    }

    @Test
    fun `the identity key is the service name, not the address`() {
        val moving = peer(1).copy(host = "10.0.0.7", port = 1234)

        assertTrue("a peer that changes address must keep its identity", peer(1).key == moving.key)
    }
}
