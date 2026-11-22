package com.droidbridge.feature.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.os.Build
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import com.droidbridge.protocol.clipboard.ClipboardSync
import com.droidbridge.protocol.session.ControlMessage

/**
 * Phase 3 on the Android side: carry text between the phone clipboard and the Mac.
 *
 * Two platform facts drive this class, both measured in P04/P05 rather than assumed:
 *
 * * **Reading needs focus.** With the app in the background the clipboard read returns no primary
 *   clip at all (not stale text — nothing). So sending is a foreground action, started by the user,
 *   and the UI must say so instead of pretending a background watch exists.
 * * **Nothing deduplicates.** The OS happily returns the same content twice, so the loop rule has to
 *   live in this code. It does: [ClipboardSync] decides, and this class only moves bytes.
 *
 * ## What is never done here
 *
 * Clipboard text is never logged, never written to disk, and never queued. [incoming] holds at most
 * one pending text in memory, and only until the user applies or dismisses it — "no history by
 * default" applies to memory as much as to storage.
 */
class ClipboardBridge(
    context: Context,
    private val ownOriginId: String,
    private val send: (ControlMessage.Type, ByteArray) -> Boolean,
) {

    companion object {
        private const val TAG = "P04Clipboard"
    }

    private val clipboard = context.applicationContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    /**
     * The last text this device wrote *because the Mac sent it*. Kept in memory only: it exists to
     * suppress exactly one echo, not to remember what was on the clipboard.
     */
    private var lastAppliedRemoteHash: String? = null

    /** Set when the user pauses sync. Nothing is sent or applied while true. */
    @Volatile var paused: Boolean = false
        private set

    /** A text waiting for the user to apply it. Never persisted, never logged. */
    @Volatile var incoming: ClipboardSync.Offer? = null
        private set

    /**
     * The body that arrived for [incoming], held in memory only until the user applies or dismisses
     * it. This is the "manual receive" the phase specifies: the Mac offers, the body arrives, and the
     * clipboard is written only when the user taps.
     */
    @Volatile private var pendingText: String? = null

    /** Last status line for the UI. Contains hashes and counts, never clipboard content. */
    @Volatile var status: String = "idle"
        private set

    fun setPaused(value: Boolean) {
        paused = value
        Log.i(TAG, "PAUSED value=$value")
        send(ControlMessage.Type.CLIPBOARD_PAUSE, (if (value) "pause" else "resume").toByteArray())
    }

    /**
     * Reads the clipboard and offers it to the peer.
     *
     * Returns false when there is nothing to send (no text on the clipboard, sync paused, or the
     * content is the peer's own text coming back). The caller shows the reason; this returns a status
     * string rather than logging clipboard content.
     */
    fun sendLocalClipboard(): String {
        if (paused) return "paused".also { status = it }
        if (!clipboard.hasPrimaryClip()) {
            status = "nothing on the clipboard"
            Log.i(TAG, "SEND_SKIPPED reason=no-primary-clip")
            return status
        }
        val clip = clipboard.primaryClip
        // A password manager marks its clips sensitive. Sending one would be a copy the user never
        // asked for, so it is refused by default rather than offered and withdrawn (NETWORK_PROTOCOL
        // §6 "sensitivity marker if available"). API 33 added the flag; below that it does not exist.
        if (Build.VERSION.SDK_INT >= 33 &&
            clip?.description?.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true
        ) {
            status = "this clipboard entry is marked sensitive and was not sent"
            Log.i(TAG, "SEND_SKIPPED reason=sensitive")
            return status
        }
        val text = clip?.getItemAt(0)?.coerceToText(null)?.toString()
        if (text.isNullOrEmpty()) {
            status = "clipboard has no text"
            Log.i(TAG, "SEND_SKIPPED reason=not-text")
            return status
        }
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > ClipboardSync.MAX_TEXT_BYTES) {
            status = "clipboard text is too large (${bytes.size} bytes)"
            Log.i(TAG, "SEND_SKIPPED reason=too-large bytes=${bytes.size}")
            return status
        }
        val hash = ClipboardSync.hashOf(bytes)
        if (!ClipboardSync.shouldSend(hash, lastAppliedRemoteHash, paused)) {
            status = "skipped: this text came from the Mac"
            Log.i(TAG, "SEND_SKIPPED reason=echo hash=${hash.take(8)}")
            return status
        }

        val offer = ClipboardSync.Offer(
            contentHash = hash,
            byteLength = bytes.size,
            originId = ownOriginId,
            sourceLabel = "Android",
        )
        // Offer first, so a peer that cannot accept can refuse before the body is sent. If the body
        // cannot be sent either, the offer is harmless: it carries no content.
        val offered = send(ControlMessage.Type.CLIPBOARD_OFFER, offer.toJson().toByteArray())
        val delivered = offered && send(ControlMessage.Type.CLIPBOARD_TEXT, bytes)
        status = if (delivered) "sent ${bytes.size} bytes" else "not connected"
        Log.i(TAG, "SEND hash=${hash.take(8)} bytes=${bytes.size} offered=$offered delivered=$delivered")
        return status
    }

    /**
     * Handles a clipboard message from the Mac.
     *
     * Every refusal here is reported to the peer. Android can only write the clipboard while this app
     * has focus (R-09), so an offer arriving in the background is *declined*, not silently dropped —
     * otherwise the Mac would believe the text arrived.
     */
    fun onMessage(type: ControlMessage.Type, payload: ByteArray, canWriteClipboard: Boolean) {
        when (type) {
            ControlMessage.Type.CLIPBOARD_OFFER -> {
                val offer = try {
                    ClipboardSync.Offer.fromJson(String(payload))
                } catch (e: Exception) {
                    Log.e(TAG, "OFFER_PARSE_FAILED ${e.javaClass.simpleName}")
                    reject(ClipboardSync.RejectReason.HASH_MISMATCH)
                    return
                }
                // Focus is not checked here: whether the app can write the clipboard matters at apply
                // time, not at offer time. Checking it now would refuse a transfer the user could
                // accept two seconds later by returning to the app.
                val reason = ClipboardSync.evaluateIncoming(offer, ownOriginId, paused, canWriteClipboard = true)
                Log.i(TAG, "OFFER hash=${offer.contentHash.take(8)} bytes=${offer.byteLength} reason=${reason ?: "accept"}")
                if (reason != null) {
                    reject(reason)
                    status = "declined: ${reason.wire}"
                    return
                }
                incoming = offer
                status = "incoming ${offer.byteLength} bytes"
                send(ControlMessage.Type.CLIPBOARD_ACCEPT, ByteArray(0))
            }

            ControlMessage.Type.CLIPBOARD_TEXT -> {
                val offer = incoming ?: run {
                    Log.i(TAG, "TEXT_WITHOUT_OFFER ignored")
                    return
                }
                if (paused) {
                    reject(ClipboardSync.RejectReason.PAUSED)
                    return
                }
                val text = String(payload, Charsets.UTF_8)
                // Validate the body against the offer now, so a corrupt transfer is refused at the
                // protocol level rather than discovered when the user taps "apply".
                val reason = ClipboardSync.evaluateIncoming(offer, ownOriginId, paused, true, text)
                if (reason != null) {
                    reject(reason)
                    status = "refused: ${reason.wire}"
                    return
                }
                pendingText = text
                status = "ready to apply ${offer.byteLength} bytes"
                Log.i(TAG, "TEXT_READY hash=${offer.contentHash.take(8)} bytes=${offer.byteLength}")
            }

            ControlMessage.Type.CLIPBOARD_PAUSE -> {
                val action = String(payload)
                paused = action == "pause"
                Log.i(TAG, "PEER_PAUSE action=$action")
                status = if (paused) "paused by the Mac" else "resumed by the Mac"
            }

            else -> Unit
        }
    }

    /** The user applied the pending text. Writes the clipboard and suppresses the resulting echo. */
    fun applyPending(canWriteClipboard: Boolean): String {
        val offer = incoming ?: return "nothing to apply"
        val text = pendingText ?: return "nothing to apply"
        if (!canWriteClipboard) {
            status = "cannot write the clipboard without focus"
            return status
        }
        return apply(offer, text.toByteArray(Charsets.UTF_8))
    }

    fun dismissPending() {
        incoming = null
        pendingText = null
        status = "dismissed"
        Log.i(TAG, "DISMISSED")
    }

    private fun apply(offer: ClipboardSync.Offer, bytes: ByteArray): String {
        val text = String(bytes, Charsets.UTF_8)
        val reason = ClipboardSync.evaluateIncoming(offer, ownOriginId, paused, true, text)
        if (reason != null) {
            incoming = null
            Log.i(TAG, "APPLY_REFUSED reason=${reason.wire}")
            reject(reason)
            status = "refused: ${reason.wire}"
            return status
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("DroidBridge", text))
        pendingText = null
        // Remember what we wrote so the next local read is not mistaken for a user copy.
        lastAppliedRemoteHash = offer.contentHash
        incoming = null
        send(ControlMessage.Type.CLIPBOARD_ACK, offer.contentHash.toByteArray())
        status = "applied ${bytes.size} bytes from the Mac"
        Log.i(TAG, "APPLIED hash=${offer.contentHash.take(8)} bytes=${bytes.size}")
        return status
    }

    private fun reject(reason: ClipboardSync.RejectReason) {
        incoming = null
        send(ControlMessage.Type.CLIPBOARD_REJECT, reason.wire.toByteArray())
        Log.i(TAG, "REJECT reason=${reason.wire}")
    }
}
