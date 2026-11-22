package com.droidbridge.app

import com.droidbridge.core.network.SessionController
import com.droidbridge.core.pairing.Pairing
import com.droidbridge.feature.mirroring.MirrorTransport
import com.droidbridge.protocol.session.ControlMessage

/**
 * Gives the capture service the session the app already has.
 *
 * Mirroring must ride the authenticated session, not open its own: a second connection would mean a
 * second pin, a second code comparison the user never sees, and a second thing to keep alive. This
 * adapter is the whole of that decision — it forwards frames into [SessionController.sendControl],
 * which queues them onto one ordered writer, and reports readiness from the pairing state so a
 * stopped or unpinned session can never look streamable.
 */
class SessionMirrorTransport(
    private val controller: SessionController,
) : MirrorTransport {

    override val isReady: Boolean
        get() = controller.state.value.phase == Pairing.Phase.CONNECTED

    override val peerLabel: String
        get() = "Mac"

    override fun send(type: ControlMessage.Type, payload: ByteArray): Boolean =
        controller.sendControl(type, payload)
}
