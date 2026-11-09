package com.droidbridge.experiment.clipboardreceive

import java.security.MessageDigest

/**
 * P05 wire limits and content identity.
 *
 * The cap is the one P04 established: the OS does not enforce a clipboard size, so DroidBridge
 * must. 100 KiB matches the shared protocol's documented clipboard cap and is checked on the wire
 * length before any allocation.
 */
object ClipboardPayload {

    const val MAX_BYTES: Int = 100 * 1024
    const val DEDUP_WINDOW_MS: Long = 5_000

    /** True when a declared frame length cannot be accepted. */
    fun tooLarge(bytes: Int): Boolean = bytes <= 0 || bytes > MAX_BYTES

    /** SHA-256 over the UTF-8 content. Never log the content itself, only this identity. */
    fun sha256Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun shortHash(hash: String): String = hash.take(12)
}
