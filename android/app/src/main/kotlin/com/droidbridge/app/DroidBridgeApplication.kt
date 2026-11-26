package com.droidbridge.app

import android.app.Application
import com.droidbridge.core.common.BuildInfo
import com.droidbridge.core.network.ServiceDiscovery
import com.droidbridge.core.network.SessionController
import com.droidbridge.core.security.DeviceIdentity
import com.droidbridge.core.security.KeystorePinStore
import com.droidbridge.feature.mirroring.MirrorTransportHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

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
            // A retry made while the phone is asleep cannot reach the network, so it must not be
            // charged to the budget (ADR-061). Nothing holds this process yet - no connectedDevice
            // service - so the display going off is what ends an idle session, and without this the
            // budget burned out and left a terminal give-up for a Mac that was still listening.
            retryGate = ScreenOnGate(this),
        ).also { session ->
            // Mirroring writes through the one session, so the binding belongs to the process rather
            // than to whichever activity happens to be on screen.
            MirrorTransportHolder.transport = SessionMirrorTransport(session)
            session.onMirrorMessage = { type, payload ->
                when (type) {
                    // The Mac flushed its decoder and needs a fresh IDR; ignoring this leaves it blank
                    // until the next scheduled GOP.
                    com.droidbridge.protocol.session.ControlMessage.Type.MIRROR_KEYFRAME_REQUEST ->
                        com.droidbridge.feature.mirroring.MirroringService.requestKeyframe()
                    // The Mac asked to stop sharing (viewer's stop control). Same path as the in-app
                    // Stop button: the pipeline tears down, `mirror.stop` goes back, session stays up.
                    com.droidbridge.protocol.session.ControlMessage.Type.MIRROR_STOP -> {
                        android.util.Log.i(
                            "P08Mirroring",
                            "stop requested by the Mac reason=" +
                                (payload?.toString(Charsets.UTF_8) ?: "none"),
                        )
                        if (com.droidbridge.feature.mirroring.MirroringService.isCapturing()) {
                            com.droidbridge.feature.mirroring.MirroringService.requestStop()
                        }
                    }
                    else -> Unit
                }
            }

            // Tell the Mac what this phone is, once per session. The handshake carries no model
            // name, so the Mac's sidebar said "Android device" and had no way to learn better. The
            // hook is on the state flow rather than on the connect button because silent reconnects
            // land in CONNECTED too, and a Mac that restarted has forgotten the name.
            processScope.launch {
                session.state
                    .map { it.phase }
                    .distinctUntilChanged()
                    .collect { phase ->
                        if (phase == com.droidbridge.core.pairing.Pairing.Phase.CONNECTED) {
                            announceDevice(session)
                        }
                    }
            }
        }
    }

    /**
     * Sends `device.info` (NETWORK_PROTOCOL §7).
     *
     * Only the facts the phone's own About screen shows: manufacturer, model, Android release, API
     * level and this app's version. No serial, no build fingerprint, no user-set device name - the
     * Mac has already authenticated, but a payload that never contains personal data cannot leak it
     * into a log later.
     */
    private fun announceDevice(session: SessionController) {
        val info = BuildInfo.describe()
        val payload = com.droidbridge.protocol.session.DeviceInfo(
            manufacturer = info.manufacturer,
            model = info.model,
            androidRelease = info.androidRelease,
            sdkInt = info.sdkInt,
            appVersion = info.versionName,
        ).toJsonPayload()
        val sent = session.sendControl(
            com.droidbridge.protocol.session.ControlMessage.Type.DEVICE_INFO,
            payload.toByteArray(Charsets.UTF_8),
        )
        android.util.Log.i("P03Shell", "device info sent=$sent ${info.manufacturer} ${info.model} android=${info.androidRelease}")
    }
}
