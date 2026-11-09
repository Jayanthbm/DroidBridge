package com.droidbridge.experiment.session

import android.util.Log
import com.droidbridge.protocol.session.FrameReader
import com.droidbridge.protocol.session.Framing
import com.droidbridge.protocol.session.FramingException
import com.droidbridge.protocol.session.ShortAuthenticationCode
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * The TLS 1.3 session (ADR-012), mutually authenticated by pinned identity (ADR-011).
 *
 * ## The trust model, stated plainly
 *
 * There is no CA and no chain. Each side presents a self-signed certificate; the other side accepts
 * it **only** if its public-key digest matches a pin the user confirmed. That means the default
 * `X509TrustManager` cannot be used — it would reject every self-signed peer — and the usual
 * shortcut of "trust everything" would accept every peer, including an attacker's.
 *
 * So this class installs a trust manager that does something the two standard options do not:
 * it accepts a peer **only** against an explicit pin, and it fails closed on anything else. The
 * dangerous middle ground — accept anything, then check later — is not available here, because
 * "later" is after the handshake has already sent data.
 *
 * ## Why the pin check is inside the trust manager
 *
 * Doing the check after `startHandshake()` returns would mean the handshake completed with an
 * unverified peer, and any bytes written before the check would already be on the wire. Putting it
 * in `checkServerTrusted`/`checkClientTrusted` means an unpinned peer fails the handshake itself,
 * before a single application byte moves.
 *
 * ## What is deliberately not here
 *
 * No plaintext fallback, no `setHostnameVerifier { true }` (there is no hostname to verify for an IP
 * literal, and a permissive verifier would be a lie rather than a fix), and no protocol downgrade.
 * TLS 1.3 is required, not preferred.
 */
class SecureSession private constructor(
    private val socket: SSLSocket,
    val peerPin: String,
    val peerCertificate: X509Certificate,
    /**
     * Whether the peer's pin was checked against a stored pin during the handshake.
     *
     * False means the handshake completed so a code could be derived, and the user has not yet
     * confirmed. Application data must not be sent until this is true.
     */
    val isConfirmed: Boolean,
) {

    companion object {
        private const val TAG = "P03Session"

        /**
         * Connects to a peer and completes a mutually authenticated TLS 1.3 handshake.
         *
         * @param expectedPin the pin the user confirmed, or null on a first pairing. Null does **not**
         *   mean "trust anything": it means the caller intends to run the code-confirmation flow,
         *   and the handshake is allowed to proceed so the peer's certificate can be read and a code
         *   derived. The returned session is explicitly marked as unconfirmed.
         */
        fun connect(
            host: String,
            port: Int,
            identity: DeviceIdentity,
            expectedPin: String?,
            timeoutMs: Int = 10_000,
        ): SecureSession {
            val context = buildContext(identity, expectedPin)
            val socket = context.socketFactory.createSocket(host, port) as SSLSocket

            socket.soTimeout = timeoutMs
            // TLS 1.3 only. Not "1.3 preferred": a fallback is a downgrade path, and there is no
            // legacy peer to accommodate on a LAN between two apps we ship.
            socket.enabledProtocols = arrayOf("TLSv1.3")
            // Client authentication is what makes this mutual. Without it the Mac would accept an
            // anonymous client and the pin would only protect one direction.
            socket.useClientMode = true

            socket.startHandshake()

            val peerCertificates = socket.session.peerCertificates
            val peerCert = peerCertificates.firstOrNull() as? X509Certificate
                ?: throw SessionException("peer presented no certificate")

            val actualPin = DeviceIdentity.pinOf(peerCert)
            Log.i(TAG, "handshake complete: protocol=${socket.session.protocol} pin=$actualPin")

            return SecureSession(
                socket = socket,
                peerPin = actualPin,
                peerCertificate = peerCert,
                isConfirmed = expectedPin != null,
            )
        }

        /**
         * Builds an SSLContext whose trust manager accepts exactly one pin.
         *
         * The identity is installed as the client key so the peer can authenticate us in return.
         */
        private fun buildContext(identity: DeviceIdentity, expectedPin: String?): SSLContext {
            val keyStore = KeyStore.getInstance("PKCS12").apply { load(null, null) }
            // An empty password is correct here: this keystore is an in-memory carrier for a key
            // that already lives in the Android Keystore, and it never touches disk.
            keyStore.setKeyEntry(
                "identity",
                identity.privateKey,
                charArrayOf(),
                arrayOf(identity.certificate),
            )

            val keyManagerFactory = KeyManagerFactory.getInstance("PKIX")
            keyManagerFactory.init(keyStore, charArrayOf())

            val context = SSLContext.getInstance("TLSv1.3")
            context.init(
                keyManagerFactory.keyManagers,
                arrayOf<TrustManager>(PinnedTrustManager(expectedPin)),
                null,
            )
            return context
        }
    }

    /**
     * Accepts a peer only if its public-key digest matches the expected pin.
     *
     * When [expectedPin] is null the manager records the pin it saw and accepts, because the caller
     * is about to run code confirmation and needs the certificate in hand. [isConfirmed] on the
     * resulting session is false, and the caller must not treat it as authenticated until the user
     * has compared the codes. This is the one place the prototype is deliberately permissive, and it
     * is permissive only about *reading* a certificate, never about sending data.
     */
    private class PinnedTrustManager(private val expectedPin: String?) : X509TrustManager {

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            verify(chain)
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            verify(chain)
        }

        private fun verify(chain: Array<out X509Certificate>?) {
            val leaf = chain?.firstOrNull()
                ?: throw CertificateException("peer presented an empty certificate chain")

            if (expectedPin == null) {
                // First pairing: allow the handshake so the code can be derived and shown. The
                // session is marked unconfirmed and no application data is sent until the user
                // confirms.
                return
            }

            val actual = DeviceIdentity.pinOf(leaf)
            if (actual != expectedPin) {
                // Fail the handshake itself. Not a warning, not a callback: an unpinned peer must
                // not get a completed handshake.
                throw CertificateException(
                    "peer pin mismatch: expected $expectedPin, got $actual",
                )
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /** Sends one framed control message. */
    fun send(payload: ByteArray) {
        val frame = Framing.encodeFrame(payload)
        outputStream.write(frame)
        outputStream.flush()
    }

    /**
     * Reads one framed control message, blocking until it arrives.
     *
     * The [FrameReader] is the shared one from `shared/protocol`, so the length validation and the
     * fail-closed behaviour are the same code the unit tests cover rather than a second
     * implementation that could drift.
     */
    fun receive(): ByteArray {
        val reader = FrameReader()
        val buffer = ByteArray(8192)
        while (true) {
            val read = inputStream.read(buffer)
            if (read < 0) throw SessionException("peer closed the connection")
            val frames = reader.feed(buffer, 0, read)
            if (frames.isNotEmpty()) return frames.first()
        }
    }

    /**
     * Reads one frame, returning null on a clean close or a framing violation.
     *
     * Framing errors are surfaced as null rather than thrown because the only correct response is to
     * close the session, and a caller that has to distinguish "peer hung up" from "peer sent
     * garbage" can inspect [lastFramingError].
     */
    var lastFramingError: FramingException? = null
        private set

    fun receiveOrNull(): ByteArray? {
        val reader = FrameReader()
        val buffer = ByteArray(8192)
        return try {
            while (true) {
                val read = inputStream.read(buffer)
                if (read < 0) return null
                val frames = reader.feed(buffer, 0, read)
                if (frames.isNotEmpty()) return frames.first()
            }
            @Suppress("UNREACHABLE_CODE")
            null
        } catch (e: FramingException) {
            lastFramingError = e
            null
        }
    }

    /** The short authentication code for this peer, given our own identity. */
    fun shortAuthenticationCode(ownIdentity: DeviceIdentity): String =
        ShortAuthenticationCode.derive(ownIdentity.sasIdentityBytes, peerCertificate.publicKey.encoded)

    private val inputStream: InputStream get() = socket.inputStream
    private val outputStream: OutputStream get() = socket.outputStream

    /** Closes the session. Idempotent. */
    fun close() {
        try {
            socket.close()
        } catch (e: Exception) {
            Log.w(TAG, "close failed: ${e.javaClass.simpleName}")
        }
    }
}

/** A session-level failure that is not a TLS or framing error. */
class SessionException(message: String) : Exception(message)
