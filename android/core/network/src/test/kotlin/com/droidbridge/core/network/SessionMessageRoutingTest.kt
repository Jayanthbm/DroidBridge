package com.droidbridge.core.network

import com.droidbridge.protocol.session.ControlMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins which feature hook receives each session message.
 *
 * The 2026-11-24 regression: a refactor of the dispatch sent `CLIPBOARD_OFFER/TEXT/ACCEPT/ACK/REJECT`
 * into the mirroring hook, which ignores clipboard types. Offers arrived, the log claimed
 * `handler=true`, and the clipboard card stayed idle forever — Mac→phone receive was silently dead
 * until a human re-ran the Phase 3 rows. This contract is the guard so every message type is routed
 * explicitly and a new type cannot drift into the wrong feature unnoticed.
 */
class SessionMessageRoutingTest {

    @Test
    fun `clipboard types route to the clipboard hook`() {
        val clipboardTypes = listOf(
            ControlMessage.Type.CLIPBOARD_OFFER,
            ControlMessage.Type.CLIPBOARD_TEXT,
            ControlMessage.Type.CLIPBOARD_ACCEPT,
            ControlMessage.Type.CLIPBOARD_ACK,
            ControlMessage.Type.CLIPBOARD_REJECT,
            ControlMessage.Type.CLIPBOARD_PAUSE,
        )
        clipboardTypes.forEach { type ->
            assertEquals("clipboard type $type must reach the clipboard feature, not the mirroring hook",
                MessageTarget.CLIPBOARD,
                SessionController.messageTarget(type))
        }
    }

    @Test
    fun `mirror types route to the mirroring hook`() {
        val mirrorTypes = listOf(
            ControlMessage.Type.MIRROR_KEYFRAME_REQUEST,
            ControlMessage.Type.MIRROR_STOP,
            ControlMessage.Type.MIRROR_STARTED,
            ControlMessage.Type.MIRROR_FORMAT,
        )
        mirrorTypes.forEach { type ->
            assertEquals("mirror type $type must reach the mirroring feature",
                MessageTarget.MIRROR,
                SessionController.messageTarget(type))
        }
    }

    @Test
    fun `keepalive and lifecycle types are not feature-routed`() {
        // The keepalive loop itself interprets PING/PONG/CLOSE; they must not be handed to a
        // feature hook, or a heartbeat would count as content.
        listOf(
            ControlMessage.Type.PING,
            ControlMessage.Type.PONG,
            ControlMessage.Type.CLOSE,
        ).forEach { type ->
            assertNull("$type is keepalive, not content", SessionController.messageTarget(type))
        }
    }
}
