# Product Requirements Document

## 1. Product summary

DroidBridge is a local-only companion product consisting of a native Android application and a native macOS application. It connects the user's Android phone and Mac over the same Wi-Fi network. Bluetooth may optionally supplement discovery or input experiments.

The first target device is the owner's Samsung Galaxy S24 Ultra running Android 17 / One UI 9, India region, as reported by the owner. The architecture should avoid Samsung-only dependencies in core functionality so other Android devices can be considered later.

## 2. Product goals

### P0 — Core goals
- Pair one Android device with one Mac securely.
- Transfer text clipboard content between the two devices using local Wi-Fi.
- Capture Android screen content only after explicit MediaProjection consent.
- Stream the permitted screen content to a native macOS window.
- Respect protected screen content and Android user controls.
- Operate without USB after installation and without USB/wireless debugging.
- Keep data local; no cloud account, relay or storage.

### P1 — Experimental goals
- Determine whether remote keyboard and mouse interaction can be achieved without Accessibility, ADB, root or privileged/OEM APIs.
- Test Bluetooth HID in the Mac-to-Android direction.
- Test whether the Mac can act as a supported Bluetooth HID peripheral.
- Investigate audio streaming under Android's playback-capture rules.

### P2 — Future goals
- Incoming call notifications and call control.
- Call audio routing, only if supported by public APIs and user-approved system roles.
- Support more Android manufacturers and versions.
- Optional image/file clipboard support after text sync is stable.

## 3. Non-goals

- Cross-internet or cross-network remote access.
- Cloud sync or account-based sync.
- Remote control of banking apps, secure lock screens or DRM applications.
- Bypassing `FLAG_SECURE`, protected surfaces or app-specific restrictions.
- Root, custom ROM or system image modification.
- Accessibility-based remote control.
- ADB-based runtime control.
- Replacing Samsung DeX, Phone Link or the Android default dialer.
- Background surveillance or silent screen capture.

## 4. User stories

### Pairing
- As a user, I can open both apps on the same Wi-Fi and pair them.
- As a user, I can verify a short authentication code on both devices.
- As a user, I can revoke a paired device.
- As a user, I can see whether the peer is connected and trusted.

### Clipboard
- As a user, I can copy text on Mac and send it to Android.
- As a user, I can send the currently available Android clipboard text to Mac when Android permits the app to read it.
- As a user, I can manually trigger sync.
- As a user, I can pause sync and exclude sensitive content.
- As a user, I can use clipboard sync even when screen mirroring is off.

### Mirroring
- As a user, I can start screen sharing from Android after an explicit system prompt.
- As a user, I can see the phone screen in a Mac window.
- As a user, I can stop sharing from either side.
- As a user, I can see a clear warning when Android stops projection or a protected surface is unavailable.
- As a user, I can rotate the phone and have the Mac view adapt.

### Remote input
- As a user, I can use Mac input only if a supported mechanism is proven.
- If full input is unavailable, the app must clearly label the feature as unsupported/experimental rather than pretending clicks are delivered.

## 5. Product behaviour

- Wi-Fi is the primary channel.
- Bluetooth is optional and disabled until explicitly enabled.
- Clipboard and mirroring are independent modules.
- Pairing must be explicit and authenticated.
- No content is retained by default.
- A session indicator must be visible on both platforms.
- A user can disconnect immediately.
- If the network changes, active sessions stop safely and reconnect only after re-authentication or a valid trusted session.

## 6. Initial release scope

The first usable release should prioritize:
1. Secure pairing and local network transport.
2. Reliable Mac-to-Android text transfer.
3. Android-to-Mac manual clipboard send.
4. MediaProjection screen capture.
5. Local screen streaming to Mac.
6. Protected-content handling and stop controls.

Remote input and calling remain experimental until separately approved.
