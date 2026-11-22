package com.droidbridge.feature.mirroring

import com.droidbridge.protocol.session.ControlMessage

/**
 * Where mirroring sends its frames.
 *
 * The capture service does not own a socket. It asks for a transport and writes through it, so the
 * product uses the single authenticated session it already has instead of opening a second one with a
 * second pin, a second code comparison and a second keepalive. The app binds the real session here
 * when it authenticates and clears it when the session ends.
 *
 * Implementations must be safe to call from the encoder and sender threads, must not block for longer
 * than a socket write, and must return false rather than throw when the session is gone.
 */
interface MirrorTransport {

    /** True while an authenticated session exists. */
    val isReady: Boolean

    /** Sentinel peer label for the notification. Never a secret. */
    val peerLabel: String

    /**
     * Increases once per authenticated session.
     *
     * A new session knows nothing about the previous one: it has no `mirror.format`, so the Mac has no
     * SPS/PPS and silently drops every frame it receives. The sender compares this counter and
     * re-publishes the format (plus an IDR) when it changes — measured after a forced reconnect, where
     * the phone kept encoding and the Mac received nothing at all.
     */
    val generation: Long

    /** Sends one control or media frame. Returns false when the session cannot carry it. */
    fun send(type: ControlMessage.Type, payload: ByteArray = ByteArray(0)): Boolean
}

/**
 * Process-wide hand-off between the session owner and the capture service.
 *
 * A live session cannot be parcelled through an `Intent`, and the service must outlive the activity,
 * so something process-scoped has to hold the binding. It is cleared when the session ends, so a
 * stopped session cannot be revived by a stale reference.
 */
object MirrorTransportHolder {

    @Volatile
    var transport: MirrorTransport? = null

    fun clear() {
        transport = null
    }
}
