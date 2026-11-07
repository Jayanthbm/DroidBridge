# Research and Feasibility Register

**Research baseline:** 2026-11-07  
**Target device:** Samsung Galaxy S24 Ultra, India region, Android 17 / One UI 9 (owner-reported)  
**Important:** Re-check every source before implementation because Android 17 and vendor firmware documentation can change.

## 1. Executive feasibility summary

| Capability | Current assessment | Evidence / next step |
|---|---|---|
| Android screen capture | Feasible with explicit user consent | MediaProjection |
| Local Wi-Fi streaming | Feasible, but Android 17 LAN permission must be handled | `ACCESS_LOCAL_NETWORK`, NSD |
| Mac screen viewer | Feasible | VideoToolbox / AVFoundation / SwiftUI |
| Mac clipboard monitoring | Feasible | `NSPasteboard.changeCount` |
| Android clipboard write | API exists; test foreground/background restrictions | `ClipboardManager.setPrimaryClip` |
| Android background clipboard read | Restricted | Android 10+ focus/default IME rules |
| Fully automatic two-way clipboard | Not guaranteed | Prototype foreground/background modes |
| Arbitrary remote touch without Accessibility/ADB/root | Not established; likely unavailable to ordinary app | Input feasibility gate |
| Bluetooth HID Mac → Android | Unproven; platform role/API constraints | Dedicated HID prototype |
| Samsung Knox remote input | Not a viable baseline for ordinary consumer app | Signature permission / managed-device restrictions |
| Secure screen handling | Must respect OS/app protections | MediaProjection + FLAG_SECURE |
| Phone-call audio | Not guaranteed | Telecom/Bluetooth role research |

## 2. Android MediaProjection

Official guide:
https://developer.android.com/media/grow/media-projection

Key facts:
- MediaProjection can capture a device display or a selected app window and provide frames to an app-owned Surface.
- User consent is required for each projection session.
- Android 14+ requires correct foreground-service declarations and one-use handling of projection tokens.
- The media-projection foreground service must be started in the correct order.
- Android 15 QPR1+ displays a prominent screen-sharing chip and stops projection when the screen is locked.
- The app must handle `MediaProjection.Callback.onStop()` and release all capture/encoder/network resources.
- Selected-app sharing is a privacy-preserving alternative to whole-display sharing, but does not provide the exact full-phone experience. Evaluate both.
- MediaProjection is capture only; it does not grant input injection.

Prototype requirements:
- Start/stop capture repeatedly with fresh consent.
- Test rotation, resolution changes, lock/unlock, screen-off, notification shade, app switching and user-initiated stop.
- Verify capture session ends immediately when the system stops it.

## 3. Android 17 local network protection

Official guide:
https://developer.android.com/privacy-and-security/local-network-permission

Important:
- Android 17 is API level 37.
- Apps targeting API 37+ are subject to mandatory local-network protections.
- Direct TCP/UDP sockets, multicast/mDNS and `NsdManager` local-network operations are gated.
- The runtime permission is `ACCESS_LOCAL_NETWORK`, in the Nearby Devices permission group.
- Android documents a system-run NSD service picker (`DiscoveryRequest.FLAG_SHOW_PICKER`) that lets the user select a discovered peer and can allow a selected-service connection without broad local-network permission.
- For general direct LAN access, declare and request the runtime permission and handle denial/revocation gracefully.
- Do not assume `INTERNET` alone grants LAN access on Android 17 target SDK 37+.

Research task:
A. Prototype Android NSD picker discovery and selected peer connection.
B. Prototype broad `ACCESS_LOCAL_NETWORK` permission path.
C. Compare user friction, reliability, inbound/outbound duplex behaviour and Samsung firmware behaviour.
D. Select the least-privilege path that genuinely supports the required full-duplex session.

Android NSD guide:
https://developer.android.com/develop/connectivity/wifi/use-nsd

## 4. Android clipboard restrictions

Official references:
- https://developer.android.com/reference/android/content/ClipboardManager
- https://developer.android.com/develop/ui/views/touch-and-input/copy-paste
- https://source.android.com/docs/whatsnew/android-10-release
- https://developer.android.com/privacy-and-security/risks/secure-clipboard-handling

Key facts:
- `setPrimaryClip(ClipData)` sets Android's primary clipboard.
- `getPrimaryClip()` returns null if the app is not the default IME and does not have input focus.
- Android 10 restricted background clipboard reads to the focused app or current keyboard/IME.
- Android 13+ displays system clipboard UI for copy operations and may clear clipboard content automatically after a period.
- Do not make a custom keyboard/default IME a requirement; it materially changes the user's input method and privacy model.
- A manual “Send clipboard” action while the app is foreground is the safe baseline.
- Test whether receiving a Mac clip and calling `setPrimaryClip` from a foreground UI or an allowed active session behaves as desired.
- Do not promise silent background clipboard writes or continuous monitoring until verified on the actual firmware.

Sensitive clipboard rules:
- No clipboard history by default.
- Do not log contents.
- Do not sync password/OTP/financial content automatically by default.
- Provide pause, manual-send and clear controls.
- Avoid retaining content in crash logs or analytics.
- Use Android's sensitive clip metadata where appropriate when setting sensitive clipboard data.

## 5. macOS pasteboard

Official reference:
https://developer.apple.com/documentation/appkit/nspasteboard/changecount

`NSPasteboard.changeCount` increments when pasteboard ownership changes. The Mac app can poll it at a modest interval or use a carefully designed observation loop. Do not busy-loop. Detect and suppress changes written by DroidBridge itself to prevent echo loops.

macOS local network privacy:
- `NSLocalNetworkUsageDescription` is required for apps using local network access.
- `NSBonjourServices` lists Bonjour service types.
- Use modern Network.framework APIs such as `NWBrowser` and `NWListener` where appropriate.
Sources:
https://developer.apple.com/documentation/BundleResources/Information-Property-List/NSLocalNetworkUsageDescription
https://developer.apple.com/documentation/BundleResources/Information-Property-List/NSBonjourServices
https://developer.apple.com/documentation/Network/NWBrowser

## 6. Secure screen and DRM

References:
- https://developer.android.com/media/grow/media-projection
- https://developer.android.com/media/platform/av-capture
- https://support.google.com/googleplay/android-developer/answer/16559646

Rules:
- Respect `FLAG_SECURE`.
- Never use screenshots, accessibility scraping, alternate displays, overlays, root, hidden APIs or other workarounds to reveal protected content.
- Protected surfaces may appear black, blank or otherwise unavailable in the capture stream.
- Treat black frames as protected/unavailable or ambiguous; do not try to recover underlying pixels.
- Do not persist frames from protected sessions.
- Include banking, password-manager and DRM app test cases only to confirm protection remains effective.

## 7. Remote input and Samsung Knox

Official Samsung sources:
- https://docs.samsungknox.com/devref/knox-sdk/reference/com/samsung/android/knox/remotecontrol/RemoteInjection.html
- https://docs.samsungknox.com/dev/knox-sdk/remote-support-overview.htm
- https://docs.samsungknox.com/dev/knox-sdk/api-reference/restricted-api-methods/

Findings:
- Knox RemoteInjection supports key/pointer/trackball injection but requires `KNOX_REMOTE_CONTROL`, a signature-protected permission.
- Samsung documents remote-control restrictions for Android 15+ requiring Device Owner/Profile Owner or managed/authorized environments.
- Samsung documents RemoteDesktop deprecation at Knox API level 35.
- The RemoteInjection API reference explicitly marks some DeX injection methods unavailable on Android 16+; do not confuse those DeX-specific method notes with proof that every API is absent. However, Knox's broader authorization restrictions already make it unsuitable as a consumer-app assumption.
- Do not build production functionality on Knox unless Samsung confirms eligibility, licensing, signing and target-device support in writing.

Conclusion:
With no Accessibility, no ADB, no root and no privileged OEM permission, arbitrary touch injection into arbitrary third-party Android apps is not established as possible through public APIs. This must be a hard feasibility gate.

## 8. Bluetooth HID directionality

Android Bluetooth HID Device API:
https://developer.android.com/reference/android/bluetooth/BluetoothHidDevice

- `BluetoothHidDevice` makes Android act as a HID peripheral to a host.
- This is the reverse of the desired Mac-as-keyboard/mouse-to-Android direction.
- Android requires `BLUETOOTH_CONNECT` for relevant operations on modern target SDKs; scanning/advertising may require other Bluetooth permissions.
- Registration is foreground-sensitive and can be automatically unregistered when the app is no longer foreground.

Apple references:
- https://developer.apple.com/documentation/corebluetooth
- https://developer.apple.com/documentation/corehid

Research:
- Determine whether macOS public APIs can advertise a Bluetooth HID-over-GATT peripheral service usable by Android.
- CoreHID's virtual-device facility is not proof that macOS can present a Bluetooth HID peripheral to an external phone.
- CoreBluetooth provides BLE GATT peripheral functionality; investigate whether the HID service/characteristics can be implemented within Apple's platform constraints.
- Classic Bluetooth HID peripheral support must not be assumed.
- Even if HID keyboard works, it types into a focused field; it does not automatically set Android's clipboard.
- HID mouse input may provide pointer events on Android but must be tested for compatibility and cannot be assumed to reproduce touch gestures in all apps.
- Bluetooth HID is optional and must not be a dependency for Wi-Fi clipboard or screen streaming.

## 9. Audio and calls

Android audio playback capture:
https://developer.android.com/media/platform/av-capture

- Audio playback capture requires MediaProjection consent and `RECORD_AUDIO`.
- Capture is limited by playback app policy and audio usage rules.
- This is not equivalent to unrestricted cellular call audio capture.
- Call handling may require Telecom integration, a role, or Bluetooth Hands-Free Profile support.
- Do not request default dialer role, call log, phone state or microphone permissions in the MVP.
- Treat call notifications, answer/reject and two-way call audio as separate research items.

## 10. Research evidence rules

For every new research entry, record:
- Question.
- Official source URL.
- Date checked.
- OS/API level.
- Permission/license/role requirement.
- Supported use case.
- Limitations.
- Prototype and test evidence.
- Decision: GO / CONDITIONAL / NO-GO.
