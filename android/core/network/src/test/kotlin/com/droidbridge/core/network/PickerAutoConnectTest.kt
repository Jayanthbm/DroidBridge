package com.droidbridge.core.network

import com.droidbridge.core.pairing.Pairing.Phase
import com.droidbridge.core.network.ServiceDiscovery.Peer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Guards the system-picker auto-connect.
 *
 * The picker asks the user which Mac to use, and the dashboard used to ask again with a `Connect`
 * button - two approvals for one decision, the second one easy to miss because the first was a system
 * dialog. These cases pin when the app may skip the second tap and, just as importantly, when it may
 * not: an explicit stop or a live session must never be overridden by a late peer list.
 */
class PickerAutoConnectTest {

    private fun peer(name: String, version: Int? = 1) = Peer(
        serviceName = name,
        host = "192.168.31.112",
        port = 51_000,
        protocolVersion = version,
    )

    @Test
    fun `connects to the first compatible peer while discovery is still running`() {
        // IDLE and DISCOVERING are both "no session yet"; startDiscovery() leaves the state in
        // DISCOVERING before the picker opens, so requiring IDLE never fired in practice.
        val mac = peer("DroidBridge-P08-Mac")
        assertEquals(mac, SessionController.pickAutoConnectPeer(listOf(mac), Phase.IDLE))
        assertEquals(mac, SessionController.pickAutoConnectPeer(listOf(mac), Phase.DISCOVERING))
    }

    @Test
    fun `never connects while a session is already live`() {
        listOf(Phase.CONNECTING, Phase.CONFIRMING, Phase.CONNECTED).forEach { phase ->
            assertNull("$phase must not auto-connect",
                SessionController.pickAutoConnectPeer(listOf(peer("mac")), phase))
        }
    }

    @Test
    fun `skips an incompatible peer rather than failing the connect`() {
        val old = peer("old Mac", version = 99)
        assertNull(SessionController.pickAutoConnectPeer(listOf(old), Phase.IDLE))
        val good = peer("good Mac", version = 1)
        assertEquals(good, SessionController.pickAutoConnectPeer(listOf(old, good), Phase.IDLE))
    }

    @Test
    fun `an empty list connects to nothing`() {
        assertNull(SessionController.pickAutoConnectPeer(emptyList(), Phase.IDLE))
    }
}