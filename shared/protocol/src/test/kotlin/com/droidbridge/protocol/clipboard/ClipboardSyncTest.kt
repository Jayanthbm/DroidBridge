package com.droidbridge.protocol.clipboard

import com.droidbridge.protocol.clipboard.ClipboardSync.RejectReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 3's two dangerous behaviours, tested directly: echoing a clipboard back to its origin, and
 * applying something the receiver cannot actually accept.
 */
class ClipboardSyncTest {

    private val phone = "phone-id"
    private val mac = "mac-id"

    private fun offerFor(text: String, origin: String = mac) = ClipboardSync.Offer(
        contentHash = ClipboardSync.hashOf(text),
        byteLength = text.toByteArray(Charsets.UTF_8).size,
        originId = origin,
        sourceLabel = null,
    )

    @Test
    fun `text applied from the peer is not sent back as a local copy`() {
        val incoming = "hello from the mac"
        val applied = ClipboardSync.hashOf(incoming)

        // The clipboard monitor sees exactly this text right after we wrote it.
        assertFalse(
            ClipboardSync.shouldSend(applied, lastAppliedRemoteHash = applied, paused = false),
            "writing peer text must not be re-sent as a local change",
        )
    }

    @Test
    fun `a genuinely new local copy is still sent while an old remote hash is remembered`() {
        val applied = ClipboardSync.hashOf("from the mac")

        assertTrue(ClipboardSync.shouldSend(ClipboardSync.hashOf("copied on the phone"), applied, paused = false))
    }

    @Test
    fun `the same text is a new copy once the applied hash is cleared`() {
        val text = "same text"
        val hash = ClipboardSync.hashOf(text)

        assertFalse(ClipboardSync.shouldSend(hash, hash, paused = false))
        assertTrue(
            ClipboardSync.shouldSend(hash, null, paused = false),
            "after the applied hash is cleared, re-copying the same text must send again",
        )
    }

    @Test
    fun `paused sync sends nothing even for new content`() {
        assertFalse(ClipboardSync.shouldSend(ClipboardSync.hashOf("new"), null, paused = true))
    }

    @Test
    fun `content that originated here is never applied back`() {
        val reason = ClipboardSync.evaluateIncoming(
            offerFor("my own text", origin = phone),
            ownOriginId = phone,
            paused = false,
            canWriteClipboard = true,
        )

        assertEquals(RejectReason.OWN_ORIGIN, reason)
    }

    @Test
    fun `an app that cannot write its clipboard rejects instead of pretending`() {
        val reason = ClipboardSync.evaluateIncoming(
            offerFor("text"),
            ownOriginId = phone,
            paused = false,
            canWriteClipboard = false,
        )

        assertEquals(RejectReason.NOT_FOREGROUND, reason)
    }

    @Test
    fun `an oversized offer is refused before any body is read`() {
        val oversized = ClipboardSync.Offer(
            contentHash = ClipboardSync.hashOf("x"),
            byteLength = ClipboardSync.MAX_TEXT_BYTES + 1,
            originId = mac,
            sourceLabel = null,
        )

        assertEquals(RejectReason.TOO_LARGE, ClipboardSync.evaluateIncoming(oversized, phone, false, true))
    }

    @Test
    fun `a body that does not match its offer is a hash mismatch, not a success`() {
        val offer = offerFor("expected text")

        assertEquals(
            RejectReason.HASH_MISMATCH,
            ClipboardSync.evaluateIncoming(offer, phone, paused = false, canWriteClipboard = true, text = "something else"),
        )
    }

    @Test
    fun `a matching body is accepted`() {
        val text = "shared text"
        assertNull(ClipboardSync.evaluateIncoming(offerFor(text), phone, false, true, text))
    }

    @Test
    fun `multi-byte text is measured in bytes, not characters`() {
        val emoji = "🙂🙂🙂"
        val offer = offerFor(emoji)

        assertEquals(12, offer.byteLength)
        assertNull(ClipboardSync.evaluateIncoming(offer, phone, false, true, emoji))
    }

    @Test
    fun `an offer round-trips through JSON without losing the origin`() {
        val original = ClipboardSync.Offer(
            contentHash = ClipboardSync.hashOf("payload"),
            byteLength = 7,
            originId = mac,
            sourceLabel = "Notes",
        )

        assertEquals(original, ClipboardSync.Offer.fromJson(original.toJson()))
    }

    @Test
    fun `an offer without an origin is rejected rather than defaulted`() {
        val error = assertFailsWith<com.droidbridge.protocol.session.FramingException> {
            ClipboardSync.Offer.fromJson("{\"hash\":\"${ClipboardSync.hashOf("x")}\",\"bytes\":1}")
        }
        assertTrue(error.message!!.contains("origin"))
    }
}
