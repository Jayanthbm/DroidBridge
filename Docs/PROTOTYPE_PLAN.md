# Prototype Plan and Feasibility Gates

All prototypes are small, isolated and disposable. They should be implemented before production modules. Use USB/ADB only for development installation and diagnostics; every prototype must have a final wireless-only test.

## P00 — Environment and device baseline

Goal:
- Verify Mac hardware/OS/Xcode/Swift.
- Verify Android Studio/SDK/JDK/Gradle.
- Record S24 Ultra model/firmware/API/build number.
- Verify ADB install/logcat during development.

Deliverables:
- `environment-report.md`
- `device-baseline.md`
- no production app code.

Acceptance:
- Both native toolchains build a hello-world app.
- Exact device OS/API details recorded.
- USB debugging use documented.

## P01 — Android MediaProjection capture

Goal:
- Capture screen with user consent, no Accessibility, no root, no runtime debugging.

Implementation:
- Minimal Kotlin app.
- Projection consent UI.
- MediaProjection foreground service.
- Surface/VirtualDisplay.
- MediaCodec encoder test or local preview.

Tests:
- home screen, browser, messaging app, rotation, lock, projection stop, protected banking/DRM test apps.
- Record which frames are black/unavailable; never attempt bypass.
- Repeated start/stop with fresh consent.

Acceptance:
- Normal screen frames captured.
- System stop is handled.
- No secure content bypass.
- No crash/leak after 20 start/stop cycles.

## P02 — Android 17 LAN discovery and permission

Goal:
- Determine least-privilege local networking path.

A: NSD system service picker (`FLAG_SHOW_PICKER`).
B: direct NSD/mDNS and `ACCESS_LOCAL_NETWORK`.

Tests:
- same Wi-Fi.
- guest Wi-Fi/client isolation.
- router changes.
- permission denied/revoked.
- duplex TCP connection.
- selected-service address connection.
- Mac Bonjour discovery.

Acceptance:
- Document exactly which path works without broad runtime permission and which requires it.
- Do not hide permission prompts.

## P03 — Local transport and pairing

Goal:
- Establish secure Mac ↔ Android session.

Prototype:
- service discovery.
- dynamic port.
- TLS.
- mutual peer authentication/pinning.
- short code confirmation.
- disconnect/reconnect.

Acceptance:
- unknown peer rejected.
- wrong code rejected.
- tampered message rejected.
- session closes on network loss.
- no cloud connection.

## P04 — Clipboard Android → Mac

Goal:
- Establish safe clipboard read behaviour.

Tests:
- app foreground with focus.
- app background.
- screen off.
- manual Send Clipboard action.
- listener behaviour.
- empty and duplicate clips.
- sensitive clipboard metadata.
- banking/OTP sample strings only as synthetic test data.

Acceptance:
- Document exactly which cases can read clipboard.
- Manual foreground send works if OS permits.
- No background bypass/default IME/Accessibility.

## P05 — Clipboard Mac → Android

Goal:
- Receive Mac text and update Android clipboard safely.

Tests:
- Android app foreground.
- app background.
- mirroring active.
- app process recreated.
- large text.
- emoji/unicode/newlines.
- Android clipboard UI behaviour.
- clipboard auto-clear/expiry.
- duplicate echo loop.

Acceptance:
- Define supported foreground and background behaviour.
- No false promise of always-on background writes.
- No content persistence by default.

## P06 — Mac pasteboard monitoring

Goal:
- Detect Mac clipboard changes and send to Android.

Tests:
- `NSPasteboard.changeCount`.
- app-owned write suppression.
- rapid copy events.
- large payload.
- pause/resume.
- sleep/wake.

Acceptance:
- no busy loop.
- no sync echo.
- no content logs.

## P07 — Mac video receiver

Goal:
- Display a generated test H.264 stream in a native Mac window.

Tests:
- VideoToolbox decode.
- rotation/resolution.
- dropped frames.
- stream stop/restart.

Acceptance:
- stable native viewer, independent of Android capture.

## P08 — End-to-end mirroring stream

Goal:
- P01 + P02/P03 + P07 integrated.

Compare:
- simple local TLS/TCP framing.
- WebRTC with local-only ICE and no STUN/TURN.

Measure:
- startup time.
- end-to-end latency.
- frame rate.
- bitrate.
- CPU/GPU.
- battery/thermal.
- network loss recovery.

Acceptance:
- stable 30-minute session on same Wi-Fi.
- session stops when MediaProjection stops.
- no protected content bypass.

## P09 — Remote input feasibility

Goal:
- Determine whether Mac can control Android without prohibited mechanisms.

Experiments:
1. Bluetooth HID-over-GATT peripheral on macOS, if public APIs permit.
2. Android external keyboard/mouse behaviour.
3. Mouse pointer and click behaviour in ordinary apps.
4. Keyboard typing into focused fields.
5. Shortcut behaviour.
6. Compare against actual touch gesture requirements.

Critical distinctions:
- Typing into a focused field is not arbitrary touch injection.
- A Bluetooth keyboard does not automatically set Android clipboard.
- Android's `BluetoothHidDevice` API represents Android-as-HID-device, the reverse direction.
- macOS CoreHID virtual-device support does not prove Bluetooth peripheral support to a phone.

Acceptance:
- Record exact supported inputs.
- If arbitrary touch is not possible, mark full remote control NO-GO under current constraints.
- Do not add Accessibility/ADB/root/Knox privileges to make the test pass.

## P10 — Secure screen behaviour

Goal:
- Verify `FLAG_SECURE` and protected surfaces remain protected.

Tests:
- synthetic test app with FLAG_SECURE.
- banking apps only for non-invasive observation.
- DRM app playback.
- lock screen.
- app-switcher/notification overlay behaviour.

Acceptance:
- no protected pixels transmitted or cached.
- black/blank/unavailable output accepted.
- no bypass attempt.

## P11 — Bluetooth optional

Goal:
- Test Mac/Android Bluetooth pairing and HID directionality.

Acceptance:
- Wi-Fi features remain usable with Bluetooth off.
- optional permissions requested only after enabling.
- record whether macOS can expose required HID peripheral profile.

## P12 — Audio/calls research

Goal:
- Determine feasibility of call notifications, answer/reject and audio.

Tests:
- Android audio playback capture constraints.
- Bluetooth HFP roles.
- Telecom APIs and default dialer implications.
- permissions and policy review.

Acceptance:
- no default dialer role or call permissions added without owner approval.
- call audio not claimed unless proven.

## Prototype report template

For each prototype, write:
- Objective.
- Device and OS.
- Mac model and macOS version.
- Build commit.
- USB connected? Debugging enabled?
- Permissions granted.
- Steps.
- Expected result.
- Actual result.
- Logs/screenshots (no private content).
- Limitations.
- GO / CONDITIONAL / NO-GO.
- Recommendation.
