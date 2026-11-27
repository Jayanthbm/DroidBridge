package com.jayanth.droidbridge

enum class AppScreen {
    Onboarding,
    Pairing,
    Home,
}

enum class PermissionKind {
    Notifications,
    NearbyDevices,
    ScreenCapture,
    Clipboard,
}

data class PermissionItem(
    val kind: PermissionKind,
    val title: String,
    val description: String,
    val granted: Boolean = false,
)

data class MacSession(
    val name: String = "Jayanth's Mac",
    val detail: String = "MacBook Air · macOS 26",
    val connected: Boolean = true,
    val clipboardSync: Boolean = true,
    val lastClipboardSync: String = "Just now",
    val mirroring: Boolean = false,
)

data class AppState(
    val screen: AppScreen = AppScreen.Onboarding,
    val permissions: List<PermissionItem> = defaultPermissions,
    val pairingCode: String = "------",
    val session: MacSession = MacSession(
        detail = "Waiting for a secure connection",
        connected = false,
        clipboardSync = false,
        lastClipboardSync = "Not synced",
    ),
    val hasPaired: Boolean = false,
    val isPairing: Boolean = false,
    val isPairingCodeReady: Boolean = false,
    val isSearching: Boolean = true,
    val isConnecting: Boolean = false,
    val pairingStatus: String = "Searching for your Mac…",
    val message: String? = null,
) {
    val grantedCount: Int get() = permissions.count { it.granted }
    val allGranted: Boolean get() = permissions.all { it.granted }
}

val defaultPermissions = listOf(
    PermissionItem(
        kind = PermissionKind.Notifications,
        title = "Notifications",
        description = "Pairing prompts and connection alerts.",
    ),
    PermissionItem(
        kind = PermissionKind.NearbyDevices,
        title = "Nearby devices",
        description = "Find your Mac on the local network and bond over Bluetooth.",
    ),
    PermissionItem(
        kind = PermissionKind.ScreenCapture,
        title = "Screen capture",
        description = "Share this phone's display when you start mirroring.",
    ),
    PermissionItem(
        kind = PermissionKind.Clipboard,
        title = "Clipboard sync",
        description = "Copy on one device and paste on the other while connected.",
    ),
)
