# macOS Application Specification

## 1. Target

- Native Swift and SwiftUI.
- Xcode project in `macos/`.
- Apple Silicon first; support Intel only if feasible and tested.
- No Electron or browser shell unless owner explicitly changes scope.
- Native menu-bar presence and a normal main window.
- No cloud account or relay.

## 2. Features

- Dashboard with paired Android device.
- Local network discovery and pairing.
- Clipboard synchronization controls.
- Mirroring viewer window.
- Session status and disconnect.
- Settings for auto-sync, exclusions, quality and optional Bluetooth experiments.
- Diagnostics with redacted logs.

## 3. Clipboard

- Use `NSPasteboard.general`.
- Observe `changeCount` with a modest polling interval or a safe observation strategy.
- Do not busy-loop.
- Track changes made by DroidBridge to prevent echo.
- Do not store clipboard history by default.
- Use a policy filter before sending content.
- Never log clipboard content.
- Provide pause and manual send.

## 4. Networking

- Use Network.framework (`NWBrowser`, `NWListener`, `NWConnection`) where suitable.
- Bonjour service types must be declared in `NSBonjourServices`.
- Add `NSLocalNetworkUsageDescription` with a clear explanation.
- Use TLS with authenticated peer identity; do not trust a peer merely because it is on the same Wi-Fi.
- Handle local network permission denied/revoked.
- Bind only to the intended interfaces where possible; avoid exposing an unauthenticated server on all networks.

## 5. Mirroring

- Receive the Android video stream.
- Decode using VideoToolbox/AVFoundation.
- Display in a resizable window with aspect-fit.
- Handle rotation and dynamic resolution.
- Support pause, disconnect and full-screen.
- Display a persistent session indicator.
- Do not save screenshots or recordings by default.

## 6. Remote input

- Mouse/keyboard capture in the Mac viewer is straightforward on the Mac side, but delivering events to Android is a separate feasibility issue.
- Do not label a cursor click as successful unless Android confirms the corresponding input mechanism is active.
- Keep Bluetooth HID and other input adapters isolated and experimental.

## 7. macOS permissions

- Local Network privacy prompt via Info.plist.
- No Screen Recording permission is needed merely to view Android's stream. Only request macOS Screen Recording if a separately approved Mac screen-capture feature is introduced.
- No Accessibility permission on macOS unless a future, separately approved feature requires it.
- No Input Monitoring permission unless justified by an explicit system-wide shortcut feature; prefer app-scoped keyboard/mouse events.
- Use Keychain for device credentials.
