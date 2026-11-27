package com.jayanth.droidbridge.network

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class TrustedMac(
    val fingerprint: String,
    val deviceId: String,
    val name: String,
    val model: String,
    val osVersion: String,
    val pairedAt: Long,
)

/// The pinned Mac fingerprint lives in preferences, but the JSON is encrypted
/// with an AES-GCM key held by Android Keystore. The previous plaintext value
/// is read once for migration and replaced on the next save.
class TrustStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("droidbridge.trust", Context.MODE_PRIVATE)

    @Synchronized
    fun load(): TrustedMac? {
        val raw = prefs.getString(KEY_MAC, null) ?: return null
        val jsonText = decrypt(raw) ?: raw.takeIf { it.trimStart().startsWith("{") } ?: return null
        return try {
            val json = JSONObject(jsonText)
            val fingerprint = json.optString("fingerprint")
            if (fingerprint.isBlank()) null else TrustedMac(
                fingerprint = fingerprint,
                deviceId = json.optString("deviceId"),
                name = json.optString("name", "Mac"),
                model = json.optString("model", "Mac"),
                osVersion = json.optString("osVersion", "macOS"),
                pairedAt = json.optLong("pairedAt"),
            )
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    fun save(mac: TrustedMac) {
        val json = JSONObject()
            .put("fingerprint", mac.fingerprint)
            .put("deviceId", mac.deviceId)
            .put("name", mac.name)
            .put("model", mac.model)
            .put("osVersion", mac.osVersion)
            .put("pairedAt", mac.pairedAt)
        val value = runCatching { encrypt(json.toString()) }.getOrNull() ?: return
        prefs.edit().putString(KEY_MAC, value).apply()
    }

    @Synchronized
    fun clear() {
        prefs.edit().remove(KEY_MAC).apply()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return PREFIX +
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(ciphertext, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String? {
        if (!value.startsWith(PREFIX)) return null
        return runCatching {
            val parts = value.removePrefix(PREFIX).split(":", limit = 2)
            if (parts.size != 2) return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)),
            )
            String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEY_MAC = "mac"
        const val KEY_ALIAS = "droidbridge.trust.key"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val PREFIX = "v1:"
    }
}
