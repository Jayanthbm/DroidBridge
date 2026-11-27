package com.jayanth.droidbridge

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class AppViewModel : ViewModel() {

    var state by mutableStateOf(AppState())
        private set

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
        if (state.allGranted) {
            state = state.copy(screen = AppScreen.Pairing)
        } else {
            state = state.copy(message = "Allow the four permissions to continue.")
        }
    }

    fun back() {
        if (state.isPairing) return
        state = when (state.screen) {
            AppScreen.Pairing -> state.copy(screen = if (state.hasPaired) AppScreen.Home else AppScreen.Onboarding)
            AppScreen.Home -> state
            AppScreen.Onboarding -> state
        }
    }

    fun pairWithMac() {
        if (state.isPairing) return
        state = state.copy(isPairing = true, message = null)
        viewModelScope.launch {
            delay(1400)
            state = state.copy(
                screen = AppScreen.Home,
                isPairing = false,
                hasPaired = true,
                session = state.session.copy(connected = true, mirroring = false),
                message = "Paired with ${state.session.name}.",
            )
        }
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
        state = state.copy(
            session = state.session.copy(connected = false, mirroring = false, clipboardSync = false),
            message = "Disconnected from ${state.session.name}.",
        )
    }

    fun reconnect() {
        state = state.copy(
            session = state.session.copy(connected = true),
            message = "Reconnected to ${state.session.name}.",
        )
    }

    fun pairAnotherMac() {
        state = state.copy(
            screen = AppScreen.Pairing,
            isPairing = false,
        )
    }

    fun clearMessage() {
        state = state.copy(message = null)
    }

    fun applyDebugScreen(screen: String?) {
        when (screen) {
            "pairing" -> state = state.copy(
                screen = AppScreen.Pairing,
                permissions = state.permissions.map { it.copy(granted = true) },
            )
            "home", "disconnected" -> state = state.copy(
                screen = AppScreen.Home,
                permissions = state.permissions.map { it.copy(granted = true) },
                hasPaired = true,
                session = state.session.copy(
                    connected = screen == "home",
                    mirroring = false,
                    clipboardSync = screen == "home",
                ),
            )
        }
    }
}
