package com.droidbridge.experiment.session

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Confirmed peer pins, encrypted with an Android Keystore key (ADR-011, ADR-024).
 *
 * ## Why encrypt a pin
 *
 * A pin is a public-key digest, not a secret: an attacker who can read it learns nothing they could
 * not read off the wire. The reason this is encrypted anyway is **integrity**, not secrecy. A pin
 * file that anything can rewrite is a pin file that can be silently re-pointed at another key, which
 * turns "known peer" into "attacker" without the user seeing an identity-change warning. The
 * Keystore-backed AES-GCM key means a modification outside the app fails authentication and is
 * treated as "no pin", which degrades to a fresh pairing — never to silent trust.
 *
 * ## Why not EncryptedSharedPreferences
 *
 * `androidx.security:security-crypto` is deprecated, and the pins need one small primitive rather
 * than a library. This uses the Keystore's own AES-256-GCM key directly: generated once, non-
 * exportable, and never leaving the Keystore. No new dependency, and no weaker fallback.
 *
 * ## Failure behaviour
 *
 * Any decryption failure (key invalidated by a lock-screen change, corrupted entry, tampered file)
 * returns null for that peer. Null means "unknown peer" in [PeerPinning], which requires the full
 * first-pairing confirmation. Failing closed in this direction is the whole point: the alternative —
 * trusting an unreadable pin — is the one behaviour an attacker would want.
 */
class KeystorePinStore(context: Context) : PeerPinning.PinStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "P03Pins"
        private const val PREFS_NAME = "p03_peer_pins"
        private const val KEY_ALIAS = "droidbridge-p03-pin-vault"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128

        /** Prefix that keeps pin keys from colliding with future preference keys. */
        private const val KEY_PREFIX = "pin:"
    }

    override fun pinFor(peerId: String): String? {
        val stored = prefs.getString(KEY_PREFIX + peerId, null) ?: return null
        return try {
            decrypt(stored)
        } catch (e: Exception) {
            // Not the pin value, and not the key: the failure class is all that is useful here.
            Log.w(TAG, "stored pin for '$peerId' could not be read (${e.javaClass.simpleName})")
            null
        }
    }

    override fun remember(peerId: String, pin: String) {
        val encrypted = encrypt(pin)
        // commit() rather than apply(): the caller states "the peer is now paired" only after this
        // returns, and a pairing that is not on disk yet must not be reported as stored.
        val written = prefs.edit().putString(KEY_PREFIX + peerId, encrypted).commit()
        if (!written) {
            throw IllegalStateException("could not persist the pin for '$peerId'")
        }
        Log.i(TAG, "pin stored for '$peerId'")
    }

    override fun forget(peerId: String) {
        prefs.edit().remove(KEY_PREFIX + peerId).commit()
        Log.i(TAG, "pin forgotten for '$peerId'")
    }

    override fun snapshot(): Map<String, String> =
        prefs.all.entries
            .filter { it.key.startsWith(KEY_PREFIX) && it.value is String }
            .mapNotNull { entry ->
                val peer = entry.key.removePrefix(KEY_PREFIX)
                val pin = try {
                    decrypt(entry.value as String)
                } catch (e: Exception) {
                    null
                }
                pin?.let { peer to it }
            }
            .toMap()

    private fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        check(iv.size == IV_BYTES) { "unexpected GCM IV length ${iv.size}" }
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(ciphertext, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val separator = stored.indexOf(':')
        require(separator > 0) { "malformed pin entry" }
        val iv = Base64.decode(stored.substring(0, separator), Base64.NO_WRAP)
        val ciphertext = Base64.decode(stored.substring(separator + 1), Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE,
        )
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            // The pin vault protects pairing state, not a user secret: requiring a biometric or
            // lock-screen confirmation to read a pin would make reconnection impossible without
            // the user present, which is worse than the threat it addresses.
            .setUserAuthenticationRequired(false)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }
}
