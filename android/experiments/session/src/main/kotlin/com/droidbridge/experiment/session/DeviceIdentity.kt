package com.droidbridge.experiment.session

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate

/**
 * The device's per-install identity (ADR-011, ADR-024).
 *
 * One EC P-256 key in the Android Keystore, generated on first use and never leaving it. The
 * Keystore hands back a self-signed certificate for the key for free, which is exactly what a
 * pinned local peer needs: no CA, no network, no trust store.
 *
 * ## Why the pin is the public key, not the certificate
 *
 * ADR-024 pins the peer by public-key digest. That matters because a certificate is a container:
 * the same key can be re-certified (new validity dates, a new serial) without the identity changing.
 * Pinning the certificate would force a re-pair for a renewal that changed nothing security-relevant,
 * and would train users to click through "identity changed" warnings that are usually harmless —
 * which is precisely how a real identity change gets ignored. The digest is over the
 * SubjectPublicKeyInfo, so it tracks the key alone.
 *
 * ## What this is not
 *
 * This class does not decide whether a peer is trusted. It produces this device's identity and
 * computes digests. The trust decision is [PeerPinning]'s job, and it is deliberately separate so
 * that "we generated a key" can never be mistaken for "we authenticated someone".
 */
class DeviceIdentity private constructor(
    val privateKey: PrivateKey,
    val certificate: X509Certificate,
) {

    companion object {
        /** Keystore alias. Stable across launches; the key behind it is the identity. */
        private const val ALIAS = "droidbridge-p03-identity"

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"

        /** A P-256 SubjectPublicKeyInfo is 91 bytes; the macOS side digests the same form. */
        private const val P256_SPKI_BYTES = 91

        /**
         * Loads the identity, generating it on first run.
         *
         * Idempotent by design: a second call returns the same key, because an identity that changed
         * on every launch would make pairing impossible. Key generation is the one moment an
         * identity comes into existence, so it is the one moment worth logging.
         */
        fun loadOrCreate(): DeviceIdentity {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

            if (!keyStore.containsAlias(ALIAS)) {
                generate(keyStore)
            }

            val privateKey = keyStore.getKey(ALIAS, null) as PrivateKey
            val certificate = keyStore.getCertificate(ALIAS) as X509Certificate

            // The pin and the short authentication code are both digests of this exact form. If the
            // platform produced a different encoding, every pair would show two different codes and
            // the failure would look like a peer problem instead of a form mismatch.
            val spki = certificate.publicKey.encoded
            check(spki.size == P256_SPKI_BYTES) {
                "device identity exported a ${spki.size}-byte SubjectPublicKeyInfo; " +
                    "P-256 must be $P256_SPKI_BYTES"
            }

            return DeviceIdentity(privateKey, certificate)
        }

        private fun generate(keyStore: KeyStore) {
            val spec = KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
            )
                .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
                // DIGEST_NONE is required, not incidental. The platform TLS stack signs the TLS 1.3
                // CertificateVerify through `NONEwithECDSA` over an already-computed digest; a key
                // that permits only SHA-256 makes that call fail with "Keystore operation failed"
                // and the handshake dies after the server has requested a client certificate. This
                // was found on the S24 Ultra, not by any build or unit test.
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_NONE)
                // ECDSA needs no padding parameter; naming the signature padding would be wrong
                // here and the Keystore rejects it for EC keys.
                .setUserAuthenticationRequired(false)
                .build()

            val generator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC,
                ANDROID_KEYSTORE,
            )
            generator.initialize(spec)
            generator.generateKeyPair()
        }

        /**
         * The pin for a certificate: SHA-256 over its SubjectPublicKeyInfo.
         *
         * `certificate.publicKey.encoded` is the DER SubjectPublicKeyInfo, which is the same bytes
         * the macOS side digests from `Certificate.PublicKey.serializeAsPEM().derBytes`. Both sides
         * must hash the same form or the pins never match — see P03's report for the vector that
         * pins this.
         */
        fun pinOf(certificate: X509Certificate): String =
            sha256Hex(certificate.publicKey.encoded)

        /** SHA-256 of arbitrary bytes, lowercase hex. */
        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }
    }

    /** This device's own pin, for display and for the peer to compare. */
    val pin: String get() = pinOf(certificate)

    /**
     * The identity bytes the short authentication code is derived from.
     *
     * The SPKI, not the certificate, for the same reason the pin is: the code must be a function of
     * the key, so that re-certifying the same key does not change the code the user is asked to
     * compare. Both sides pass this same form.
     */
    val sasIdentityBytes: ByteArray get() = certificate.publicKey.encoded
}
