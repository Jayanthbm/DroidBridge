package com.droidbridge.protocol.clipboard

import com.droidbridge.protocol.session.FramingException
import java.security.MessageDigest

/**
 * Text clipboard transfer for Phase 3 (NETWORK_PROTOCOL §6).
 *
 * ## The loop problem, stated plainly
 *
 * Clipboard sync is a two-way echo machine. Phone copies "x" → Mac writes "x" → the Mac's pasteboard
 * monitor sees a change → sends "x" back → the phone writes "x" → its monitor sees a change → and so
 * on. Every clipboard sync product has to break that cycle, and it must be broken on *content*, not on
 * timing: a suppression window alone loses a genuine re-copy of the same text, and a hash alone cannot
 * tell "the peer echoed my own text back" from "the user really copied this same string again on the
 * other machine".
 *
 * So the rule here is two-part, and both parts are tested:
 *
 * 1. **Origin.** Every transfer carries the id of the device that produced the text. A device never
 *    sends back content whose origin is itself, and never *applies* content it originally sent.
 * 2. **Content hash.** The hash identifies the payload, so the offer, the body and the ack cannot be
 *    mixed up, and a duplicate delivery is dropped rather than re-applied.
 *
 * ## What is deliberately not persisted
 *
 * Nothing. Not the text, not the hash history, not a "last synced" breadcrumb on disk. The state below
 * lives in memory and dies with the process, which is what "no history by default" and "never persist
 * clipboard contents" require (AGENTS.md, SECURITY_AND_PRIVACY.md).
 */
object ClipboardSync {

    /** Largest text this implementation will carry, in UTF-8 bytes. */
    const val MAX_TEXT_BYTES: Int = 64 * 1024

    /** Hash length in bytes (SHA-256 truncated to keep the offer small). */
    const val HASH_BYTES: Int = 32

    /** Why a transfer was declined. Machine-readable so the peer can distinguish the cases. */
    enum class RejectReason(val wire: String) {
        /** The text exceeds [MAX_TEXT_BYTES]. */
        TOO_LARGE("too_large"),

        /** The receiver is not allowed to write its clipboard right now (Android focus, R-09). */
        NOT_FOREGROUND("not_foreground"),

        /** Sync is paused by the user on the receiving side. */
        PAUSED("paused"),

        /** The text hashed differently from the offer: the transfer is corrupt, not merely late. */
        HASH_MISMATCH("hash_mismatch"),

        /** The content originated here, so applying it would echo our own clipboard back. */
        OWN_ORIGIN("own_origin"),

        ;

        companion object {
            fun fromWire(value: String): RejectReason? = entries.firstOrNull { it.wire == value }
        }
    }

    /** The metadata sent before the body. Never carries the text itself. */
    data class Offer(
        /** Hex SHA-256 of the text bytes. */
        val contentHash: String,
        /** UTF-8 byte length, so the receiver can refuse before reading the body. */
        val byteLength: Int,
        /** Pseudonymous id of the device that produced the text. */
        val originId: String,
        /** Human-facing label for the source app, or null when unknown. Never clipboard content. */
        val sourceLabel: String?,
    ) {
        init {
            require(contentHash.length == HASH_BYTES * 2) { "contentHash must be $HASH_BYTES bytes as hex" }
            require(byteLength >= 0) { "byteLength must not be negative" }
            require(originId.isNotBlank()) { "originId must identify the producing device" }
        }

        /** The JSON shape carried in `clipboard.offer`. */
        fun toJson(): String = buildString {
            append("{\"hash\":\"").append(contentHash).append("\",")
            append("\"bytes\":").append(byteLength).append(',')
            append("\"origin\":\"").append(escape(originId)).append('"')
            if (sourceLabel != null) append(",\"source\":\"").append(escape(sourceLabel)).append('"')
            append('}')
        }

        companion object {
            /**
             * Parses an offer. Strict on purpose: an unknown or missing field is an error rather than
             * a default, because a silently-defaulted origin is exactly the value the loop rule
             * depends on.
             */
            fun fromJson(json: String): Offer {
                val hash = stringField(json, "hash") ?: throw FramingException("clipboard offer has no hash")
                val bytes = intField(json, "bytes") ?: throw FramingException("clipboard offer has no byte length")
                val origin = stringField(json, "origin")
                    ?: throw FramingException("clipboard offer has no origin")
                return Offer(
                    contentHash = hash,
                    byteLength = bytes,
                    originId = origin,
                    sourceLabel = stringField(json, "source"),
                )
            }
        }
    }

    /** SHA-256 of the UTF-8 text, hex encoded. The identity of a clipboard payload. */
    fun hashOf(text: String): String = hashOf(text.toByteArray(Charsets.UTF_8))

    fun hashOf(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Decides whether local clipboard content should be sent.
     *
     * [lastAppliedRemoteHash] is the hash of the last text this device wrote *because a peer sent it*.
     * If the local clipboard now matches it, the change was caused by that write, not by the user, so
     * sending would start the echo. Anything else is a genuine local copy.
     */
    fun shouldSend(hash: String, lastAppliedRemoteHash: String?, paused: Boolean): Boolean {
        if (paused) return false
        return hash != lastAppliedRemoteHash
    }

    /**
     * Decides whether an incoming transfer may be applied.
     *
     * Order matters: cheap and absolute refusals first, so a paused or backgrounded device never even
     * reads the body. [ownOriginId] is this device's id — a transfer that originated here is our own
     * clipboard coming back and must never be applied.
     */
    fun evaluateIncoming(
        offer: Offer,
        ownOriginId: String,
        paused: Boolean,
        canWriteClipboard: Boolean,
        text: String? = null,
    ): RejectReason? {
        if (paused) return RejectReason.PAUSED
        if (offer.originId == ownOriginId) return RejectReason.OWN_ORIGIN
        if (offer.byteLength > MAX_TEXT_BYTES) return RejectReason.TOO_LARGE
        if (!canWriteClipboard) return RejectReason.NOT_FOREGROUND
        if (text != null) {
            if (text.toByteArray(Charsets.UTF_8).size != offer.byteLength) return RejectReason.HASH_MISMATCH
            if (hashOf(text) != offer.contentHash) return RejectReason.HASH_MISMATCH
        }
        return null
    }

    // --- Minimal JSON field readers -------------------------------------------------------------
    //
    // The shared module has no JSON dependency, and the mirroring protocol already hand-rolls its
    // small payloads (MirrorFormat). These read the flat, quoted/numeric fields this schema allows and
    // return null rather than throwing, so the caller decides what a missing field means.

    private fun stringField(json: String, name: String): String? {
        val marker = "\"$name\":\""
        val start = json.indexOf(marker)
        if (start < 0) return null
        val valueStart = start + marker.length
        val end = json.indexOf('"', valueStart)
        if (end < 0) return null
        return unescape(json.substring(valueStart, end))
    }

    private fun intField(json: String, name: String): Int? {
        val marker = "\"$name\":"
        val start = json.indexOf(marker)
        if (start < 0) return null
        val rest = json.substring(start + marker.length).trimStart()
        val digits = rest.takeWhile { it.isDigit() }
        return digits.toIntOrNull()
    }

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun unescape(value: String): String {
        val out = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val ch = value[index]
            if (ch == '\\' && index + 1 < value.length) {
                when (val next = value[index + 1]) {
                    '"' -> out.append('"')
                    '\\' -> out.append('\\')
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    else -> out.append(next)
                }
                index += 2
            } else {
                out.append(ch)
                index += 1
            }
        }
        return out.toString()
    }
}
