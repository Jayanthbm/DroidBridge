package com.droidbridge.app

import android.app.Application
import com.droidbridge.core.network.ServiceDiscovery
import com.droidbridge.core.network.SessionController
import com.droidbridge.core.security.DeviceIdentity
import com.droidbridge.core.security.KeystorePinStore
import com.droidbridge.feature.mirroring.MirrorTransportHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Owns the authenticated session for the whole process.
 *
 * The session used to live in `MainActivity`, and `onDestroy` stopped it — which meant an orientation
 * change tore the connection down mid-stream. Measured: rotating during a mirroring session logged
 * `session closed: stopped by app` on the phone and `peer closed the connection` on the Mac within
 * 120 ms, and the stream never resumed. A picture rotating is not a reason to drop a session.
 *
 * ADR-023 puts the session in a `connectedDevice` foreground service eventually; until that service
 * owns the reconnect policy, the application object is the next-smallest owner that survives a
 * configuration change, and it makes the rotation rule true today rather than after another phase.
 */
class DroidBridgeApplication : Application() {

    private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val controller: SessionController by lazy {
        SessionController(
            identity = DeviceIdentity.loadOrCreate(),
            pinStore = KeystorePinStore(this),
            discovery = ServiceDiscovery(this),
            scope = processScope,
        ).also { session ->
            // Mirroring writes through the one session, so the binding belongs to the process rather
            // than to whichever activity happens to be on screen.
            MirrorTransportHolder.transport = SessionMirrorTransport(session)
            session.onMirrorMessage = { type, _ ->
                // The only mirroring frame the Mac sends mid-stream is a keyframe request, after it
                // flushed its decoder. Ignoring it leaves the Mac blank until the next scheduled GOP.
                if (type == com.droidbridge.protocol.session.ControlMessage.Type.MIRROR_KEYFRAME_REQUEST) {
                    com.droidbridge.feature.mirroring.MirroringService.requestKeyframe()
                }
            }
        }
    }
}
