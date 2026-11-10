package com.droidbridge.core.network

import android.annotation.SuppressLint
import android.util.Log
import com.droidbridge.core.security.DeviceIdentity
import com.droidbridge.protocol.session.ControlMessage
import com.droidbridge.protocol.session.FrameReader
import com.droidbridge.protocol.session.Framing
import com.droidbridge.protocol.session.FramingException
import com.droidbridge.protocol.session.ReplayException
import com.droidbridge.protocol.session.ReplayWindow
import com.droidbridge.protocol.session.ShortAuthenticationCode
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.ArrayDeque
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
 * So this class installs a trust manager that does something the two standard options do not: it
 * accepts a peer **only** against an explicit pin, and it fails closed on anything else.
 *
 * ## Two distinct states, because "the handshake worked" is not "the peer is trusted"
 *
 * A first pairing has no pin yet, so the handshake must be allowed to complete to learn the peer's
 * key and derive the short authentication code. That session is [State.AWAITING_CONFIRMATION]: the
 * cipher is up, but **no application byte moves in either direction** until the user confirms the
 * code on both screens and [authenticate] is called. [send] and [receive] refuse while unconfirmed.
 * This is what makes the confirmation UI control the session rather than decorate it.
 *
 * A session with a stored pin goes straight to [State.AUTHENTICATED]: the pin was checked inside
 * the handshake, and re-asking the user to compare codes on every reconnect would train them to
 * press "confirm" without looking.
 *
 * ## Why the pin check is inside the trust manager
 *
 * Doing the check after `startHandshake()` returns would mean the handshake completed with an
 * unverified peer, and any bytes written before the check would already be on the wire. Putting it
 * in `checkServerTrusted` means an unpinned peer fails the handshake itself, before a single
 * application byte moves. The trust manager also records the pin it accepted, and [connect] asserts
 * that it equals the pin on the negotiated session, so a future code path cannot silently skip it.
 *
 * ## What is deliberately not here
 *
 * No plaintext fallback, no `setHostnameVerifier { true }` (there is no hostname to verify for an IP
 * literal, and a permissive verifier would be a lie rather than a fix), and no protocol downgrade.
 * TLS 1.3 is required, and the negotiated protocol is asserted after the handshake rather than
 * assumed from the request.
 */
class SecureSession private constructor(
    private val socket: SSLSocket,
    val peerPin: String,
    val peerCertificate: X509Certificate,
    initiallyAuthenticated: Boolean,
    private val idleTimeoutMs: Int,
    private val maxFramesPerSecond: Int,
    private val maxVideoFramesPerSecond: Int,
) {

    /** Whether application data may move on this session. */
    enum class State {
        /** TLS is up and identities are known, but the user has not compared the codes. */
        AWAITING_CONFIRMATION,

        /** The peer is confirmed: its pin is stored, or it matched a stored pin. */
        AUTHENTICATED,

        /** The connection is gone; every later call fails. */
        CLOSED,
    }

    private val lock = Any()

    @Volatile
    private var sessionState: State =
        if (initiallyAuthenticated) State.AUTHENTICATED else State.AWAITING_CONFIRMATION

    @Volatile
    private var closeReason: String? = null

    val state: State get() = sessionState

    /** True only when application data is permitted in both directions. */
    val isAuthenticated: Boolean get() = sessionState == State.AUTHENTICATED

    /** Why the session closed, or null while it is open. */
    val closedReason: String? get() = closeReason

    /**
     * One decoder for the life of the connection.
     *
     * A per-call reader would drop the bytes it had already buffered: TCP delivers a stream, so a
     * read can end mid-frame or carry several frames. The shared [FrameReader] accumulates partial
     * frames; [pending] holds frames that arrived together so none is lost when the caller asks for
     * one message.
     */
    private val reader = FrameReader()
    private val pending = ArrayDeque<ByteArray>()

    /** Per-direction replay window; reset only by creating a new session. */
    private val replayWindow = ReplayWindow()

    private var outboundSequence: Long = 0

    private var rateWindowStartedNanos: Long = System.nanoTime()
    private var rateWindowFrames: Int = 0

    /**
     * Media is paced on its own budget.
     *
     * A 30 fps stream is normal traffic, so counting it against a 20/s control budget would close a
     * healthy session in its first second. The separate budget still refuses a flooding peer, which
     * is the property the limit exists for.
     */
    private var videoWindowStartedNanos: Long = System.nanoTime()
    private var videoWindowFrames: Int = 0
    private var videoFramesSent: Long = 0

    companion object {
        private const val TAG = "P03Session"

        /** TLS 1.3 is the only accepted protocol. */
        private const val REQUIRED_PROTOCOL = "TLSv1.3"

        /** A P-256 SubjectPublicKeyInfo is 91 bytes; anything else is not a peer we can pin. */
        private const val P256_SPKI_BYTES = 91

        /**
         * Connects to a peer and completes a mutually authenticated TLS 1.3 handshake.
         *
         * @param expectedPin the pin the user confirmed, or null on a first pairing. Null does **not**
         *   mean "trust anything": it means the caller intends to run the code-confirmation flow,
         *   and the handshake is allowed to proceed so the peer's certificate can be read and a code
         *   derived. The returned session is [State.AWAITING_CONFIRMATION] and refuses data.
         */
        fun connect(
            host: String,
            port: Int,
            identity: DeviceIdentity,
            expectedPin: String?,
            connectTimeoutMs: Int = 5_000,
            idleTimeoutMs: Int = 30_000,
            maxFramesPerSecond: Int = 20,
            maxVideoFramesPerSecond: Int = 120,
        ): SecureSession {
            require(connectTimeoutMs > 0) { "connectTimeoutMs must be positive" }
            require(idleTimeoutMs > 0) { "idleTimeoutMs must be positive" }

            val trustManager = PinnedTrustManager(expectedPin)
            val context = buildContext(identity, trustManager)

            // Connect the TCP socket first so the connect attempt has a real bound. An SSLSocket
            // created by the factory does a blocking connect with no timeout, which is how a wrong
            // IP turns into an app that appears frozen.
            val raw = Socket()
            try {
                raw.tcpNoDelay = true
                raw.connect(InetSocketAddress(host, port), connectTimeoutMs)
            } catch (e: Exception) {
                runCatching { raw.close() }
                throw SessionException(
                    "TCP connect to $host:$port failed: ${e.javaClass.simpleName}",
                )
            }

            val socket = try {
                context.socketFactory.createSocket(raw, host, port, true) as SSLSocket
            } catch (e: Exception) {
                runCatching { raw.close() }
                throw SessionException("could not start TLS: ${e.javaClass.simpleName}")
            }

            try {
                socket.soTimeout = idleTimeoutMs
                // TLS 1.3 only. Not "1.3 preferred": a fallback is a downgrade path, and there is no
                // legacy peer to accommodate on a LAN between two apps we ship.
                socket.enabledProtocols = arrayOf(REQUIRED_PROTOCOL)
                // Client authentication is what makes this mutual. Without it the Mac would accept
                // an anonymous client and the pin would only protect one direction.
                socket.useClientMode = true

                socket.startHandshake()

                val negotiated = socket.session.protocol
                if (negotiated != REQUIRED_PROTOCOL) {
                    // The request above should make this impossible; asserting it means a future
                    // provider change cannot silently produce a TLS 1.2 session.
                    throw SessionException("negotiated $negotiated, required $REQUIRED_PROTOCOL")
                }

                val peerCert = socket.session.peerCertificates.firstOrNull() as? X509Certificate
                    ?: throw SessionException("peer presented no certificate")

                val actualPin = DeviceIdentity.pinOf(peerCert)
                val checkedPin = trustManager.acceptedPin
                if (checkedPin == null) {
                    // The trust manager must have made a decision; reaching here without one would
                    // mean the handshake bypassed it.
                    throw SessionException("peer pin was never checked during the handshake")
                }
                if (checkedPin != actualPin) {
                    throw SessionException("peer pin changed between verification and session")
                }

                Log.i(
                    TAG,
                    "handshake complete: protocol=$negotiated peerPin=$actualPin " +
                        "authenticated=${expectedPin != null}",
                )

                return SecureSession(
                    socket = socket,
                    peerPin = actualPin,
                    peerCertificate = peerCert,
                    initiallyAuthenticated = expectedPin != null,
                    idleTimeoutMs = idleTimeoutMs,
                    maxFramesPerSecond = maxFramesPerSecond,
                    maxVideoFramesPerSecond = maxVideoFramesPerSecond,
                )
            } catch (e: Exception) {
                runCatching { socket.close() }
                when (e) {
                    is SessionException -> throw e
                    else -> throw SessionException(
                        "TLS handshake failed: ${e.javaClass.simpleName}: ${e.message}",
                    )
                }
            }
        }

        /**
         * Builds an SSLContext whose trust manager accepts exactly one pin.
         *
         * The identity is installed as the client key so the peer can authenticate us in return.
         */
        private fun buildContext(
            identity: DeviceIdentity,
            trustManager: PinnedTrustManager,
        ): SSLContext {
            val keyStore = KeyStore.getInstance("PKCS12").apply { load(null, null) }
            // An empty password is correct here: this keystore is an in-memory carrier for a key
            // that lives in the Android Keystore, and it never touches disk.
            keyStore.setKeyEntry(
                "identity",
                identity.privateKey,
                charArrayOf(),
                arrayOf(identity.certificate),
            )

            val keyManagerFactory = KeyManagerFactory.getInstance("PKIX")
            keyManagerFactory.init(keyStore, charArrayOf())

            val context = SSLContext.getInstance(REQUIRED_PROTOCOL)
            context.init(
                keyManagerFactory.keyManagers,
                arrayOf<TrustManager>(trustManager),
                null,
            )
            return context
        }
    }

    /**
     * Accepts a peer only if its public-key digest matches the expected pin.
     *
     * When [expectedPin] is null the manager records the pin it saw and accepts, because the caller
     * is about to run code confirmation and needs the certificate in hand. The resulting session is
     * [State.AWAITING_CONFIRMATION] and cannot send or receive data. This is the one place the
     * prototype is permissive, and it is permissive only about *reading* a certificate.
     */
    // Lint's CustomX509TrustManager check exists because most hand-written trust managers end up
    // calling checkServerTrusted with an empty chain ("trust everything") or skipping verification
    // entirely. This one is the opposite: it is the only trust decision this transport has, it
    // fails closed, and it is covered by the P13 device evidence (pin mismatch refused, no-cert
    // client refused). Suppressed here rather than globally, because a second custom trust manager
    // anywhere in the app should have to justify itself again.
    @SuppressLint("CustomX509TrustManager")
    private class PinnedTrustManager(private val expectedPin: String?) : X509TrustManager {

        /** The pin this manager accepted, recorded so the caller can assert it was checked. */
        @Volatile
        var acceptedPin: String? = null
            private set

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            verify(chain)
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            verify(chain)
        }

        private fun verify(chain: Array<out X509Certificate>?) {
            val leaf = chain?.firstOrNull()
                ?: throw CertificateException("peer presented an empty certificate chain")

            val spki = leaf.publicKey?.encoded
                ?: throw CertificateException("peer certificate has no public key")
            if (spki.size != P256_SPKI_BYTES) {
                // Rejecting by key form is not cosmetic: a different key type would digest to a
                // differently-shaped SPKI, so the SAC and the pin would never agree with the peer.
                throw CertificateException(
                    "peer key is not P-256 (${spki.size}-byte SPKI, expected $P256_SPKI_BYTES)",
                )
            }

            val actual = DeviceIdentity.pinOf(leaf)
            if (expectedPin == null) {
                acceptedPin = actual
                return
            }

            if (actual != expectedPin) {
                // Fail the handshake itself. Not a warning, not a callback: an unpinned peer must
                // not get a completed handshake.
                throw CertificateException(
                    "peer pin mismatch: expected $expectedPin, got $actual",
                )
            }
            acceptedPin = actual
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /**
     * Marks the session as authenticated.
     *
     * Only the owner of a passed code comparison may call this. Idempotent, because a reconnect
     * path may authenticate a session that is already authenticated.
     */
    fun authenticate() {
        synchronized(lock) {
            when (sessionState) {
                State.AWAITING_CONFIRMATION -> {
                    sessionState = State.AUTHENTICATED
                    Log.i(TAG, "session authenticated after code confirmation")
                }

                State.AUTHENTICATED -> Unit

                State.CLOSED -> throw SessionException("session is closed")
            }
        }
    }

    /**
     * Sends one framed control message.
     *
     * @throws SessionNotAuthenticatedException before the user has confirmed the pairing
     */
    fun send(type: ControlMessage.Type, payload: ByteArray = ByteArray(0)) {
        synchronized(lock) {
            requireAuthenticated("send")
            outboundSequence += 1
            val frame = Framing.encodeFrame(
                ControlMessage.encode(type, outboundSequence, payload),
            )
            try {
                outputStream.write(frame)
                outputStream.flush()
            } catch (e: IOException) {
                failLocked(SessionException("send failed: ${e.javaClass.simpleName}"))
            }
            if (type.isMedia) {
                // One log line per frame would drown the log at 30 fps, and the line would be about
                // the peer's screen. Counted instead; the session-level summary reports the rate.
                videoFramesSent += 1
            } else {
                Log.i(
                    TAG,
                    "sent ${ControlMessage.describe(ControlMessage.Message(type, outboundSequence, payload))}",
                )
            }
        }
    }

    /** Media frames sent since the session was created. For status display, never content. */
    val mediaFramesSent: Long get() = synchronized(lock) { videoFramesSent }

    /**
     * Waits for the next control message, up to [timeoutMs].
     *
     * Returns null on a caller-imposed timeout **without** closing the session: a bounded wait from
     * the UI is not an idle session. A timeout at or beyond [idleTimeoutMs] is an idle session and
     * closes it.
     *
     * @throws SessionNotAuthenticatedException before confirmation
     * @throws SessionException on close, framing violation, replay or rate limiting
     */
    fun tryReceive(timeoutMs: Int): ControlMessage.Message? {
        require(timeoutMs > 0) { "timeoutMs must be positive" }
        ensureAuthenticated("receive")

        val closeOnTimeout = timeoutMs >= idleTimeoutMs
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        return readNext(deadline, closeOnTimeout)
    }

    /** Waits for the next control message under the session's idle timeout. */
    fun receive(): ControlMessage.Message =
        tryReceive(idleTimeoutMs) ?: throw SessionTimeoutException(
            "no control message within ${idleTimeoutMs}ms",
        )

    private fun readNext(deadlineNanos: Long, closeOnTimeout: Boolean): ControlMessage.Message? {
        val buffer = ByteArray(8192)
        while (true) {
            synchronized(lock) {
                if (sessionState == State.CLOSED) {
                    throw SessionException("session is closed (${closeReason ?: "no reason"})")
                }
                if (pending.isNotEmpty()) {
                    return decodeLocked(pending.removeFirst())
                }
            }

            val remainingMs = (deadlineNanos - System.nanoTime()) / 1_000_000L
            if (remainingMs <= 0) {
                return if (closeOnTimeout) {
                    failLocked(SessionTimeoutException("session idle for ${idleTimeoutMs}ms"))
                } else {
                    null
                }
            }

            // Bound each read by both the caller's deadline and the idle timeout, so a peer that
            // dribbles one byte at a time cannot hold a session open forever.
            socket.soTimeout = minOf(remainingMs, idleTimeoutMs.toLong())
                .coerceAtLeast(1L)
                .toInt()

            val read = try {
                inputStream.read(buffer)
            } catch (e: SocketTimeoutException) {
                continue
            } catch (e: IOException) {
                failLocked(SessionException("read failed: ${e.javaClass.simpleName}"))
            }

            if (read < 0) {
                failLocked(SessionException("peer closed the connection"))
            }

            val frames = try {
                reader.feed(buffer, 0, read)
            } catch (e: FramingException) {
                failLocked(e)
            }

            synchronized(lock) {
                pending.addAll(frames)
            }
        }
    }

    /** Decodes, replay-checks and rate-limits one frame. Caller holds [lock]. */
    private fun decodeLocked(frame: ByteArray): ControlMessage.Message {
        try {
            val message = ControlMessage.decode(frame)
            replayWindow.accept(message.sequence)
            admitFrameLocked(message.type.isMedia)
            return message
        } catch (e: ReplayException) {
            failLocked(e)
        } catch (e: FramingException) {
            failLocked(e)
        } catch (e: RateLimitException) {
            failLocked(e)
        }
    }

    /**
     * A fixed one-second window, which is enough to stop a flood and simple enough to explain.
     *
     * Not a token bucket: P03 needs a bound it can demonstrate, and a strict per-second count is
     * testable without timing subtleties.
     */
    private fun admitFrameLocked(isMedia: Boolean) {
        val now = System.nanoTime()
        if (isMedia) {
            if (now - videoWindowStartedNanos >= 1_000_000_000L) {
                videoWindowStartedNanos = now
                videoWindowFrames = 0
            }
            videoWindowFrames += 1
            if (videoWindowFrames > maxVideoFramesPerSecond) {
                throw RateLimitException(
                    "peer exceeded $maxVideoFramesPerSecond media frames in one second",
                )
            }
            return
        }

        if (now - rateWindowStartedNanos >= 1_000_000_000L) {
            rateWindowStartedNanos = now
            rateWindowFrames = 0
        }
        rateWindowFrames += 1
        if (rateWindowFrames > maxFramesPerSecond) {
            throw RateLimitException(
                "peer exceeded $maxFramesPerSecond control frames in one second",
            )
        }
    }

    /** The short authentication code for this peer, given our own identity. */
    fun shortAuthenticationCode(ownIdentity: DeviceIdentity): String =
        ShortAuthenticationCode.derive(
            ownIdentity.sasIdentityBytes,
            peerCertificate.publicKey.encoded,
        )

    private fun ensureAuthenticated(operation: String) {
        synchronized(lock) {
            if (sessionState == State.CLOSED) {
                throw SessionException("session is closed (${closeReason ?: "no reason"})")
            }
            if (sessionState != State.AUTHENTICATED) {
                throw SessionNotAuthenticatedException(
                    "refusing $operation: pairing is not confirmed",
                )
            }
        }
    }

    private fun requireAuthenticated(operation: String) = ensureAuthenticated(operation)

    /** Closes the session and records why. Idempotent. Caller holds [lock]. */
    private fun failLocked(reason: Exception): Nothing {
        synchronized(lock) {
            if (sessionState != State.CLOSED) {
                sessionState = State.CLOSED
                closeReason = reason.message ?: reason.javaClass.simpleName
                Log.w(TAG, "session closed: $closeReason")
                runCatching { socket.close() }
            }
        }
        throw reason
    }

    /** Closes the session. Idempotent. */
    fun close(reason: String = "closed by app") {
        synchronized(lock) {
            if (sessionState == State.CLOSED) return
            sessionState = State.CLOSED
            closeReason = reason
            Log.i(TAG, "session closed: $reason")
            runCatching { socket.close() }
        }
    }

    private val inputStream: InputStream get() = socket.inputStream
    private val outputStream: OutputStream get() = socket.outputStream
}

/** A session-level failure that is not a TLS or framing error. */
open class SessionException(message: String) : Exception(message)

/** Application data was attempted before the pairing was confirmed. */
class SessionNotAuthenticatedException(message: String) : SessionException(message)

/** The session was idle for longer than its idle timeout. */
class SessionTimeoutException(message: String) : SessionException(message)

/** The peer sent more control frames than the session permits. */
class RateLimitException(message: String) : SessionException(message)
