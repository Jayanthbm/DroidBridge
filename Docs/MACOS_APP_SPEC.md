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
- Observe `changeCount` with a modest polling interval; do not busy-loop.
- **Handle the macOS 15.4+ pasteboard access prompt** (`NSPasteboard.accessBehavior`, R-11): model undetermined/allowed/denied, explain the prompt before it appears, link to System Settings, and always keep a manual "send clipboard now" action that works without background monitoring (ADR-014).
- Track changes made by DroidBridge to prevent echo.
- Do not store clipboard history by default.
- Use a policy filter before sending content.
- Never log clipboard content.
- Provide pause and manual send.

## 4. Networking

- Use Network.framework (`NWBrowser`, `NWListener`, `NWConnection`).
- **The Mac is the advertiser and listener** (`NWListener` + Bonjour); the Android app discovers it through the system NSD picker and dials out (ADR-010).
- Bonjour service types must be declared in `NSBonjourServices` and must match `NETWORK_PROTOCOL.md` exactly.
- Add `NSLocalNetworkUsageDescription` with a clear explanation, and handle the local-network privilege being undetermined or denied (R-10).
- Use TLS 1.3 with authenticated peer identity; do not trust a peer merely because it is on the same Wi-Fi.
- Identity (ADR-024): generate a P-256 key and self-signed certificate on first run using Apple's `swift-certificates` package (the Security framework has no public certificate-creation API), keep them in the app's Keychain, and present them with `sec_protocol_options_set_local_identity`.
- On the listener, explicitly require peer authentication (`sec_protocol_options_set_peer_authentication_required(options, true)`); macOS servers default to **not** requiring a client certificate.
- Verify the peer by pinned SHA-256 of its SubjectPublicKeyInfo inside `sec_protocol_options_set_verify_block`. No hostname matching (the peer is an IP literal), no system trust store, no "continue anyway".
- Handle local network permission denied/revoked.
- Bind only to the intended interfaces where possible; avoid exposing an unauthenticated server on all networks.

## 5. Mirroring

- Receive the Android video stream.
- Decode and display with `AVSampleBufferDisplayLayer` (ADR-028) — it decodes compressed H.264 itself, so no `VTDecompressionSession` and no pixel handling are needed for view-only mirroring.
- Build the format description with `CMVideoFormatDescriptionCreateFromH264ParameterSets` from the SPS and PPS delivered in `mirror.format`: bare NALs, at least two parameter sets, `nalUnitHeaderLength = 4`.
- Wrap each access unit in a `CMSampleBuffer` (`CMSampleBufferCreateReady`) with one timing entry, `decodeTimeStamp = .invalid`, and strictly increasing PTS in a 1/1,000,000 timescale from `ptsUs`. Rebase PTS on stream start and on reconnect.
- Gate enqueues on `isReadyForMoreMediaData`; coalesce or drop non-keyframes under backpressure rather than growing a queue. Never drop the IDR that follows a format change.
- On `.failed` or `requiresFlushToResumeDecoding`: `flush()`, send `mirror.keyframe_request`, and resume only on an IDR. Recovery with P-frames is not recovery.
- Keep all of this inside one adapter type. The enqueue API is deprecated in favour of `AVSampleBufferVideoRenderer.Receiver` (macOS 27+); do not raise the deployment target to silence the warning, and record the actual warnings in P07.
- Set `preventsCapture = true` so the mirrored phone screen cannot leak into a Mac screen recording.
- Handle decode failures (`AVSampleBufferDisplayLayerFailedToDecode`) as a viewer state with redacted logging — never log frame content.
- Display in a resizable window with aspect-fit.
- Handle rotation and dynamic resolution, including a mid-session format change.
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
- Use Keychain for device credentials (see §8).
- The Local Network prompt is triggered by **advertising** (Bonjour), not by accepting the Android connection: TN3179 exempts listening/accepting inbound TCP but requires access for every Bonjour operation. Re-check the privilege before re-advertising instead of assuming it still holds.

## 8. Distribution and sandbox

- **App Sandbox on from the first build**, with exactly `com.apple.security.app-sandbox`, `com.apple.security.network.client` and `com.apple.security.network.server` (ADR-026). Both network entitlements are needed: the server one for accepting the Android TCP connection, the client one because Bonjour advertising is UDP and the client entitlement gates sending.
- Do not add `com.apple.security.cs.*` hardened-runtime exceptions. Nothing in the app needs JIT, unsigned libraries or disabled library validation, and each exception weakens a protection.
- **Identity storage (ADR-025):** data protection keychain (`kSecUseDataProtectionKeychain: true`) under the app's own `keychain-access-groups` entry, authorized by an embedded **Developer ID** provisioning profile. `kSecAttrSynchronizable` stays off. The private key is non-exportable.
- **Signing:** Developer ID Application identity, hardened runtime (`-o runtime`), secure timestamp (`--timestamp`), signed **inside-out**; never `codesign --deep`, never `sudo`. No `get-task-allow` in a release build. Entitlements file is ASCII XML with no BOM or comments.
- **Notarisation:** submit a ZIP or DMG with `xcrun notarytool submit … --wait`, then `xcrun stapler staple` so Gatekeeper can validate offline. Read the notary log for warnings even when the status is `Accepted`.
- The release bundle carries a **distribution** provisioning profile; a development profile in a release build is a bug.
- Distribution is direct (DMG), not the Mac App Store, for now. The sandbox is kept on so a Mac App Store build stays possible without a redesign.
- Never tell users to bypass Gatekeeper. An unnotarised build is not a release candidate.
- Ad-hoc development builds may re-prompt for Local Network access when the signature changes. That is expected; record it, do not "fix" it by weakening the prompt.
