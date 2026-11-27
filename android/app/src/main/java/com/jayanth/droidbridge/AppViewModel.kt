package com.jayanth.droidbridge

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.jayanth.droidbridge.network.BridgeLink
import com.jayanth.droidbridge.network.LinkEvent
import com.jayanth.droidbridge.network.TrustStore

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val trust = TrustStore(application)
    private val link = BridgeLink(application) { event -> handle(event) }
    private var autoPair = false

    var state by mutableStateOf(AppState())
        private set

    init {
        val saved = trust.load()
        if (saved != null) {
            state = state.copy(
                screen = AppScreen.Home,
                hasPaired = true,
                isConnecting = true,
                isSearching = true,
                pairingStatus = "Reconnecting securely…",
                session = MacSession(
                    name = saved.name,
                    detail = "${saved.model} · ${saved.osVersion}",
                    connected = false,
                    clipboardSync = false,
                    lastClipboardSync = "Not synced",
                ),
            )
            link.start(allowNewPairing = false, autoPair = autoPair)
        }
    }

    fun setAutoPair(enabled: Boolean) {
        autoPair = enabled
    }

    fun togglePermission(kind: PermissionKind) {
        state = state.copy(
            permissions = state.permissions.map {
                if (it.kind == kind) it.copy(granted = !it.granted) else it
            },
        )
    }

    fun allowAllPermissions() {
        state = state.copy(
            permissions = state.permissions.map { it.copy(granted = true) },
            message = "Permissions ready. One more step.",
        )
    }

    fun continueFromOnboarding() {
        if (!state.allGranted) {
            state = state.copy(message = "Allow the four permissions to continue.")
            return
        }
        startPairing()
    }

    private fun startPairing() {
        state = state.copy(
            screen = AppScreen.Pairing,
            pairingCode = "------",
            isPairing = false,
            isPairingCodeReady = false,
            isSearching = true,
            isConnecting = false,
            pairingStatus = "Searching for your Mac…",
            message = null,
        )
        link.start(allowNewPairing = true, autoPair = autoPair)
    }

    fun back() {
        if (state.isPairing) return
        when (state.screen) {
            AppScreen.Pairing -> {
                if (!state.hasPaired) link.disconnect()
                state = state.copy(
                    screen = if (state.hasPaired) AppScreen.Home else AppScreen.Onboarding,
                    isSearching = false,
                )
            }
            AppScreen.Home -> Unit
            AppScreen.Onboarding -> Unit
        }
    }

    fun pairWithMac() {
        if (state.isPairing || !state.isPairingCodeReady) return
        state = state.copy(
            isPairing = true,
            pairingStatus = "Waiting for Mac to confirm…",
            message = null,
        )
        link.pair()
    }

    fun toggleClipboardSync() {
        state = state.copy(
            session = state.session.copy(clipboardSync = !state.session.clipboardSync),
            message = if (state.session.clipboardSync) "Clipboard sync paused." else "Clipboard sync turned on.",
        )
    }

    fun syncClipboardNow() {
        if (!state.session.connected || !state.session.clipboardSync) return
        state = state.copy(
            session = state.session.copy(lastClipboardSync = "Just now"),
            message = "Clipboard sent to your Mac.",
        )
    }

    fun toggleMirroring() {
        if (!state.session.connected) return
        val next = !state.session.mirroring
        state = state.copy(
            session = state.session.copy(mirroring = next),
            message = if (next) "Screen mirroring preview started." else "Screen mirroring stopped.",
        )
    }

    fun disconnect() {
        link.disconnect()
        state = state.copy(
            session = state.session.copy(connected = false, mirroring = false, clipboardSync = false),
            isConnecting = false,
            isSearching = false,
            message = "Disconnected from ${state.session.name}.",
        )
    }

    fun reconnect() {
        if (!state.hasPaired) {
            startPairing()
            return
        }
        state = state.copy(
            isConnecting = true,
            isSearching = true,
            pairingStatus = "Reconnecting securely…",
            message = null,
        )
        link.reconnect()
    }

    fun pairAnotherMac() {
        trust.clear()
        link.disconnect()
        state = state.copy(
            screen = AppScreen.Pairing,
            hasPaired = false,
            isPairing = false,
            isPairingCodeReady = false,
            isSearching = true,
            isConnecting = false,
            pairingCode = "------",
            pairingStatus = "Searching for your Mac…",
            session = MacSession(
                detail = "Waiting for a secure connection",
                connected = false,
                clipboardSync = false,
                lastClipboardSync = "Not synced",
            ),
        )
        link.start(allowNewPairing = true, autoPair = autoPair)
    }

    fun clearMessage() {
        state = state.copy(message = null)
    }

    fun applyDebugScreen(screen: String?) {
        when (screen) {
            "pairing" -> {
                state = state.copy(permissions = state.permissions.map { it.copy(granted = true) })
                startPairing()
            }

            "home", "disconnected" -> state = state.copy(
                screen = AppScreen.Home,
                permissions = state.permissions.map { it.copy(granted = true) },
                hasPaired = true,
                isConnecting = false,
                isSearching = false,
                isPairing = false,
                isPairingCodeReady = true,
                pairingCode = "392641",
                session = MacSession(
                    name = "Jayanth's Mac",
                    detail = "MacBook Air · macOS 26",
                    connected = screen == "home",
                    clipboardSync = screen == "home",
                    lastClipboardSync = "Just now",
                ),
            )
        }
    }

    private fun handle(event: LinkEvent) {
        when (event) {
            is LinkEvent.Searching -> state = state.copy(
                isSearching = true,
                isConnecting = state.hasPaired,
                pairingStatus = if (state.hasPaired) "Reconnecting securely…" else "Searching for your Mac…",
            )

            is LinkEvent.Pairing -> state = state.copy(
                screen = AppScreen.Pairing,
                pairingCode = event.code,
                isPairingCodeReady = true,
                isPairing = false,
                isSearching = false,
                isConnecting = false,
                pairingStatus = "Compare this code on both devices.",
                session = state.session.copy(
                    name = event.mac.name,
                    detail = "${event.mac.model} · ${event.mac.osVersion}",
                ),
            )

            is LinkEvent.PairingPending -> state = state.copy(
                isPairing = true,
                pairingStatus = "Waiting for Mac to confirm…",
            )

            is LinkEvent.Connected -> state = state.copy(
                screen = AppScreen.Home,
                hasPaired = true,
                isPairing = false,
                isPairingCodeReady = false,
                isSearching = false,
                isConnecting = false,
                pairingStatus = "Connected securely",
                session = MacSession(
                    name = event.mac.name,
                    detail = "${event.mac.model} · ${event.mac.osVersion}",
                    connected = true,
                    clipboardSync = state.session.clipboardSync,
                    lastClipboardSync = state.session.lastClipboardSync,
                ),
                message = "Connected to ${event.mac.name} over Wi-Fi.",
            )

            is LinkEvent.Disconnected -> state = state.copy(
                isPairing = false,
                isConnecting = event.willReconnect,
                isSearching = event.willReconnect,
                pairingStatus = event.reason,
                session = state.session.copy(connected = false, mirroring = false),
                message = if (event.willReconnect) "Reconnecting to your Mac…" else event.reason,
            )

            is LinkEvent.Failed -> state = state.copy(
                isSearching = false,
                isConnecting = false,
                pairingStatus = event.message,
                message = event.message,
            )
        }
    }

    override fun onCleared() {
        link.stop()
        super.onCleared()
    }
}
