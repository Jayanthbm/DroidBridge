# Prototype Plan and Feasibility Gates

All prototypes are small, isolated and disposable. They should be implemented before production modules. Use USB/ADB only for development installation and diagnostics; every prototype must have a final wireless-only test.

**Research pass 2026-11-08:** the instructions below were expanded for the uncertainties identified in `RESEARCH_AND_FEASIBILITY.md` §3 (U1–U10). Nothing here has been run. A prototype is only "complete" when its report contains real evidence.

## Rules that apply to every prototype

1. Isolation: prototype code lives under `android/experiments/<name>` or `macos/Experiments/<Name>` and is never imported by production modules.
2. Evidence header, recorded *before* the session: app build commit, device model, Android version + API level + One UI build, Mac model + macOS version + Xcode version, USB connected yes/no, USB debugging enabled yes/no, wireless debugging enabled yes/no, Bluetooth on/off, same-Wi-Fi confirmation, router model if relevant.
3. No private data: never copy real passwords, OTPs, bank details or personal clipboard contents into logs, screenshots or the report. Use synthetic strings such as `OTP-TEST-482913`.
4. Negative results are results: record what did not work verbatim (error codes, exception names, permission state).
5. Final run of every prototype must be performed with USB disconnected and USB debugging and wireless debugging disabled, or the report must state clearly that this was not achieved and why.
6. Report template at the bottom of this file is mandatory for each prototype.

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
- Exact device OS/API details recorded (this is what confirms or corrects the owner-reported "Android 17 / One UI 9").
- USB debugging use documented.

Extra step added 2026-11-08: record `Build.VERSION.SDK_INT`, `Build.VERSION.RELEASE`, One UI version, and whether the device exposes the Android 17 local-network prompt wording. Photo-free text capture is enough.

## P01 — Android MediaProjection capture

Goal:
- Capture screen with user consent, no Accessibility, no root, no runtime debugging.

Implementation:
- Minimal Kotlin app.
- Projection consent UI.
- MediaProjection foreground service with `android:foregroundServiceType="mediaProjection"` plus `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_MEDIA_PROJECTION`.
- Surface/VirtualDisplay.
- MediaCodec encoder test or local preview.

Tests:
- home screen, browser, messaging app, rotation, lock, projection stop, protected banking/DRM test apps.
- Record which frames are black/unavailable; never attempt bypass.
- Repeated start/stop with fresh consent.
- Attempt to reuse a consent token and confirm `SecurityException` (documents R-04 rather than discovering it).
- Tap the system screen-sharing chip and confirm `MediaProjection.Callback.onStop()` fires.
- Lock the screen mid-session and confirm the session ends.
- Rotate and confirm `onCapturedContentResize()` values; record letterboxing.
- Compare whole-display vs selected-app capture; record what the system UI offers on One UI 9.

Acceptance:
- Normal screen frames captured.
- System stop is handled.
- No secure content bypass.
- No crash/leak after 20 start/stop cycles.

## P02 — Android 17 local networking: picker vs broad permission

Goal:
- Determine the least-privilege local networking path that still supports a full-duplex TLS session.

Background (2026-11-08 research): advertising a service always requires `ACCESS_LOCAL_NETWORK`; a picker-selected service can be connected to without it. That is why Path A below reverses the usual client/server roles.

### Path A — Mac advertises, Android dials out (primary)

Steps:
1. On the Mac, run a minimal `NWListener` that advertises `_droidbridge-dev._tcp` with a TXT record containing `v=1` and a random `sid`.
2. On Android (targetSdk 37, `ACCESS_LOCAL_NETWORK` **not** declared), call `NsdManager.registerServiceInfoCallback()` with `DiscoveryRequest.Builder("_droidbridge-dev._tcp").setFlags(DiscoveryRequest.FLAG_SHOW_PICKER)`.
3. Confirm the system picker appears and lists the Mac.
4. Select the Mac; connect a TCP socket to `NsdServiceInfo.getHostAddresses()` on `getNetwork()`.
5. Exchange a duplex echo for 60 s (Android → Mac and Mac → Android) to prove both directions work under the grant.
6. Kill and relaunch the Android app; rediscover with `FLAG_USER_APPROVED_ONLY` and confirm no picker is shown and the connection succeeds.
7. Reboot the phone; repeat step 6.
8. Change the Mac's address (toggle Wi-Fi, or move to another access point with the same SSID); confirm re-resolution through `registerServiceInfoCallback` instead of a cached IP.
9. Revoke the grant (System Settings flow or reinstall the Mac service under a new name) and confirm `checkPermissionForService()` reports the loss and the app re-shows the picker.
10. Attempt an **inbound** connection from the Mac to an Android listening socket while only holding the picker grant. Record whether it is blocked. This is U1 and it decides whether Mac-initiated reconnect is possible.

### Path B — Android advertises with the runtime permission (fallback)

Steps:
1. Declare and request `ACCESS_LOCAL_NETWORK`; record the exact prompt text and whether the `NEARBY_DEVICES` group pre-grant suppression appears.
2. `registerService()` the Android service and confirm success with the permission (and confirm `FAILURE_PERMISSION_DENIED` when it is revoked).
3. Repeat the duplex test, reboot test and address-change test.
4. Deny the permission and confirm the app fails closed with a clear explanation, no crash, and no silent retry loop.

### Shared environment tests

- same Wi-Fi, guest Wi-Fi/client isolation, router reboot, Mac sleep/wake, airplane-mode toggle.
- Record discovery latency and connection latency for both paths.

Acceptance:
- Document exactly which path works without broad runtime permission and which requires it.
- Document whether inbound connections work under the picker grant (U1).
- Do not hide permission prompts.
- If Path A passes every step, ADR-010 is confirmed; otherwise escalate to the owner before falling back to Path B.

## P03 — Local transport and pairing

Goal:
- Establish a secure Mac ↔ Android session over the transport chosen in ADR-012.

Prototype:
- service discovery (using the winning P02 path).
- dynamic port.
- TLS 1.3 with app-owned pinned certificates (see P13 for the Android crypto details).
- mutual peer authentication/pinning.
- short code confirmation displayed on both screens.
- disconnect/reconnect.
- length-prefixed framing with a strict maximum message size.

Acceptance:
- unknown peer rejected.
- wrong code rejected.
- tampered message rejected.
- session closes on network loss.
- no cloud connection (verify with a network monitor that no non-LAN address is contacted).
- reconnect after Wi-Fi drop works without re-pairing, or fails with a clear re-pair path.

## P04 — Clipboard Android → Mac

Goal:
- Establish exactly when Android lets the app read the clipboard, and design the honest workflow around it.

Test matrix (record a row per case; U5):

| Case | Expected | Record |
|---|---|---|
| DroidBridge activity visible and focused, user taps "Send clipboard" | works | yes/no + value round-trip |
| DroidBridge activity visible but no text field focused | works or null | observation |
| DroidBridge in foreground service only, another app on screen | null | observation |
| Screen off / locked | null | observation |
| App process recreated, then "Send clipboard" | works | observation |
| Clipboard empty | null / empty clip rejected | observation |
| Clipboard contains an image only | rejected by policy | observation |
| Duplicate content (same text twice) | deduplicated | observation |
| Very long text (e.g. 100 KB) | capped by policy | observation + chosen cap |
| Sensitive clip set by another app with `EXTRA_IS_SENSITIVE` | preview suppressed; check whether readable | observation |

Also record whether the Android 12+ "app accessed clipboard" toast appears on each successful read.

Acceptance:
- Document exactly which cases can read clipboard.
- Manual foreground send works if OS permits.
- No background bypass, no default IME, no Accessibility.
- Produces the definitive wording for the Android → Mac user story, including the cases that will not work.

## P05 — Clipboard Mac → Android

Goal:
- Receive Mac text and update Android clipboard safely.

Test matrix (U6):
- DroidBridge activity in foreground / in background with an active mirroring foreground service / process just recreated.
- Large text, emoji, CJK, newlines, leading/trailing whitespace, zero-width characters.
- Observe whether the Android 13+ copy preview appears and what it shows.
- Observe whether the clipboard is auto-cleared and after how long (leave the device idle for 5, 15, 30, 60 minutes).
- Duplicate echo loop: write the same text back and confirm origin IDs/hashes stop the loop.
- Write while the user is in another app; record whether the paste works there afterwards.

Acceptance:
- Define supported foreground and background behaviour.
- No false promise of always-on background writes.
- No content persistence by default; buffers cleared after delivery.

## P06 — Mac pasteboard monitoring and the access prompt

Goal:
- Determine whether automatic Mac → Android clipboard sync is viable at all (U4). This prototype gates ADR-014.

Steps:
1. Build a properly bundled, code-signed (development) `.app` — not a bare CLI binary — because the alert may be gated on being a bundled app.
2. Cold launch. Read only `NSPasteboard.general.changeCount` every 500 ms for two minutes while copying text in another app. Record whether any alert appears.
3. Now perform a real read (`string(forType: .string)`) after a changeCount delta. Record whether the system alert appears, its exact wording, and whether it appears once or on every read.
4. Record the app's own `NSPasteboard.accessBehavior` value before and after the first alert (`default` → `ask` expected).
5. Choose "Don't Allow" and confirm subsequent reads return nil/no data, and that the app cannot recover without System Settings.
6. Change the setting to "Allow" in System Settings → Privacy & Security and confirm monitoring resumes without relaunch if possible.
7. Repeat with the app in the background and with the Mac asleep/woken.
8. Confirm the `changeCount` write-suppression loop: after DroidBridge writes to the pasteboard, no echo is sent back to Android.
9. Measure a 10-minute idle run for CPU use (a busy loop is a failure).

Acceptance:
- Definitive statement of which operation triggers the alert on the tested macOS version.
- Documented per-state UX (undetermined/allowed/denied) with concrete screens.
- No busy loop, no sync echo, no clipboard content in logs.
- If a prompt appears on every programmatic read, ADR-014 must fall back to a manual "paste from Mac" button only.

## P07 — Mac video receiver

Goal:
- Display a generated test H.264 stream in a native Mac window.

Tests:
- VideoToolbox decode or `AVSampleBufferDisplayLayer`.
- rotation/resolution changes, aspect-fit letterboxing.
- dropped frames, stream stop/restart, keyframe recovery after a synthetic loss.

Acceptance:
- stable native viewer, independent of Android capture.

## P08 — End-to-end mirroring stream

Goal:
- P01 + P02/P03 + P07 integrated over the ADR-012 transport.

Compare (measurement gate retained from ADR-009):
- TLS/TCP framing with length-prefixed video frames.
- WebRTC with local-only candidates, no STUN/TURN (only if the simple path misses targets, and note that on macOS 26+ `RTCPeerConnection` itself can trigger the local-network prompt).

Measure:
- startup time, end-to-end latency, frame rate, bitrate, CPU/GPU, battery/thermal, reconnect time.

Acceptance:
- stable 30-minute session on same Wi-Fi with USB disconnected.
- session stops when MediaProjection stops (U10: include a locked-screen and screen-off run).
- no protected content bypass.
- record actual numbers; do not declare targets met without data.

## P09 — Remote input feasibility (expected NO-GO confirmation)

Goal:
- Confirm or refute ADR-015/ADR-016 with evidence rather than inference.

Experiments:
1. On macOS, create a `CBPeripheralManager`, build a `CBMutableService` with type `0x1812` (HID), add it, and record the exact error from `peripheralManager(_:didAdd:error:)`. Expected: `CBError.uuidNotAllowed` (code 8). Record the raw error domain/code/message.
2. Repeat with the required companion services (Device Information `0x180A`, Battery `0x180F`) to see whether the refusal is specific to HID.
3. Attempt classic Bluetooth HID: document that no public API exists to own the HID L2CAP PSMs; do not attempt private APIs.
4. On Android, register `BluetoothHidDevice` and confirm it makes the phone the peripheral (i.e. it does not help a Mac drive Android). Record what happens when the app leaves the foreground.
5. Pair a normal physical Bluetooth keyboard and mouse to the S24 Ultra and record what they can do: pointer visibility, click behaviour in ordinary apps, gesture limitations, and password-field masking (Android 17 `show_passwords_physical`, R-19). This documents the ceiling of any HID approach even if it were possible.
6. Do not add Accessibility, ADB, root or Knox anything to make a test pass.

Acceptance:
- Record exact supported inputs and exact refusals.
- If (as expected) no compliant path exists, mark full remote control NO-GO and keep mirroring view-only.
- Report must state that typing into a focused field is not arbitrary touch injection and does not set the Android clipboard.

## P10 — Secure screen and screenshare-protection behaviour

Goal:
- Verify `FLAG_SECURE`, DRM and Android 15+ screenshare protections remain effective in the mirrored stream.

Tests:
- synthetic test app with `FLAG_SECURE` (own app) → expect blank region.
- banking/DRM apps: **non-invasive observation only**, no interaction, no bypass attempt.
- lock screen and notification shade while mirroring.
- post a notification with and without `setPublicVersion()` and record what the mirror shows (U8).
- focus a password field in a test app and confirm the mirror hides the input.
- trigger an OTP-bearing test notification (synthetic text) and confirm the related activity is hidden.
- confirm no frames from protected regions are cached to disk or memory beyond the encode buffer.

Acceptance:
- no protected pixels transmitted or cached.
- black/blank/unavailable output accepted and displayed as such.
- no bypass attempt, no Developer-Options escape hatch used for acceptance evidence.

## P11 — macOS local network permission and Bonjour

Goal:
- Verify the macOS side of the P02 design.

Tests:
- first launch: confirm the local-network alert appears and record its wording plus the `NSLocalNetworkUsageDescription` text shown.
- deny, then confirm the app fails gracefully and offers a route to System Settings → Privacy & Security → Local Network.
- allow, then confirm the Mac advertises `_droidbridge-dev._tcp` and Android can see it in the picker.
- confirm `NSBonjourServices` matches the actual service type (a mismatch is a common silent failure).
- Mac sleep/wake, Wi-Fi change, and second user account (local-network state is per user).

Acceptance:
- documented allowed/denied UX.
- discovery works after grant and fails clearly without it.

## P12 — Audio/calls research (only on separate owner approval)

Goal:
- Determine feasibility of call notifications, answer/reject and audio.

Tests:
- Android audio playback capture constraints, including the same-user-profile rule and capture-policy opt-out of common players.
- Confirm cellular/voice-communication audio is not capturable.
- Bluetooth HFP roles and Telecom requirements review.
- permission and Play-policy review.

Acceptance:
- no default dialer role, `RECORD_AUDIO`, call-log or phone-state permission added without owner approval.
- call audio not claimed unless proven (research says it cannot be).

## P13 — Pinned TLS identity on Android 17 with CT/ECH defaults

Goal:
- Prove ADR-011 and ADR-024 on real hardware: per-install identity generation, mutual TLS, SPKI pinning, and the Android 17 CT/ECH interaction (U3, U13).

Android side:
1. Generate the identity with `KeyPairGenerator` over `AndroidKeyStore`: `KeyGenParameterSpec.Builder(alias, PURPOSE_SIGN or PURPOSE_VERIFY)`, `setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))`, `setCertificateSubject(X500Principal("CN=DroidBridge-Android-<random>")`, a random serial and a validity window. Read the certificate back with `KeyStore.getCertificate(alias)`.
2. Confirm the certificate's signature verifies (that is the check that `PURPOSE_SIGN` was set correctly, per R-22). A certificate that fails to verify is still returned by the Keystore — this is the trap.
3. Build an `SSLContext` with a `KeyManager` bound to the alias and a pinning `X509TrustManager` that accepts only the stored peer SPKI digest. Do **not** add a static `<trust-anchors>` entry: the peer certificate is unknown at build time.
4. Connect to the Mac listener with TLS 1.3 only, with targetSdk 37 and CT/ECH at their defaults. Record whether the handshake succeeds (the CT inference in R-24 is unconfirmed).
5. Record whether ECH/ECH GREASE appears in a packet/handshake trace, or is simply irrelevant to a raw `SSLSocket` to a literal IP address.

macOS side:
6. Generate a P-256 key, build a self-signed v3 certificate with `swift-certificates` (its documentation states the same key may be passed as `issuerPrivateKey`), and persist key + certificate in the app's Keychain.
7. Rebuild the identity with `SecIdentityCreate(cert, key)` and present it via `sec_protocol_options_set_local_identity`. Set the minimum TLS version to 1.3.
8. **Explicitly** call `sec_protocol_options_set_peer_authentication_required(options, true)` on the listener and prove that a client without a certificate is rejected — servers default to *not* requiring peer authentication, so this must be asserted, not assumed.
9. Verify with `sec_protocol_options_set_verify_block`: accept the pinned SPKI, reject anything else and fail closed.

Negative tests:
10. Untrusted peer (no pin, self-signed): handshake must fail; record the exact exception/`OSStatus` on each platform.
11. Pin mismatch (same peer, regenerated key): must fail as an identity change, with a distinct UI state — not a generic network error.
12. Certificate re-issue with the **same** key: must still pass (validates the SPKI-not-certificate pinning choice).
13. Code review evidence: no global CT disable, no trust-all `TrustManager`, no `SSLParameters` endpoint-identification workaround, no `kSecTrustSettingsResult` override.

Acceptance:
- documented working configuration and exact failure mode for an untrusted peer on both platforms.
- mutual authentication proven in both directions, including the case where the client presents no certificate.
- no global security weakening in the final manifest/config.
- the local-IP/no-SNI case is handled without relying on hostname verification, and this is stated explicitly in the report.
- definitive answer recorded for U3/U13: does pinned TLS work on API 37 with CT on by default?

## P14 — Background session persistence (clipboard while mirroring is off)

Goal:
- Prove or disprove ADR-023: a `connectedDevice` foreground service keeps a TLS session usable in normal use, and characterise exactly where it stops working (U11, U12).

Setup:
- Manifest: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `POST_NOTIFICATIONS`, and exactly one prerequisite permission — `CHANGE_NETWORK_STATE`. Deliberately **no** Bluetooth permissions, so the test proves the type is satisfied without a runtime prompt.
- Service declared with `android:foregroundServiceType="connectedDevice"` and an `exported="false"` attribute.
- Minimal TLS echo client that connects to the Mac listener from P03/P02 and sends a heartbeat every 15 s with a monotonically increasing counter.

Tests:
1. Start the service from a visible activity. Record: does `startForeground()` succeed with no runtime permission dialog? Does the Play Console prerequisite appear in any log?
2. Start the same service from a background context (e.g. a timer with the app backgrounded). Expected: `ForegroundServiceStartNotAllowedException`. Record the exact exception.
3. Start it from `BOOT_COMPLETED` (`adb shell am broadcast -a android.intent.action.BOOT_COMPLETED <pkg>` during development, then a real reboot). Expected: allowed for `connectedDevice`, unlike `dataSync`/`mediaProjection`. Record it.
4. Session survival matrix — for each, record whether heartbeats arrive on the Mac, and the reconnect time after the condition ends:
   - app backgrounded, screen on, 10 min;
   - screen off, unplugged, 10 min;
   - screen off, idle and stationary, 60 min (Doze);
   - overnight, unplugged, 8 h;
   - Wi-Fi toggle off/on;
   - Mac sleep/wake;
   - phone reboot with background sync previously enabled.
5. Doze forcing (development only): `adb shell dumpsys deviceidle force-idle` then observe whether the socket stalls and whether it recovers on `unforce`. Record USB/debugging state for this step and repeat the key cases without USB for the final result.
6. Notification behaviour: with `POST_NOTIFICATIONS` granted, confirm the notification appears with connection state and a disconnect action; deny it and confirm the service still runs and appears in the FGS Task Manager; dismiss the notification and confirm the service continues while the in-app indicator stays truthful.
7. Battery: 60 min idle with a 15 s heartbeat vs no session; record percentage drain and any thermal notes, then propose the keepalive interval and backoff schedule.

Acceptance:
- A written statement of exactly which conditions keep the session alive and which end it.
- Reconnect implemented and measured (time to reconnect after each condition).
- No use of `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, no wake-lock workaround, no silent retry loop.
- If the session cannot survive an ordinary overnight idle, ADR-023's degradation path is taken: background sync becomes foreground-only and the product copy changes.

## Prototype report template

For each prototype, write:
- Objective.
- Device and OS (Android version, API level, One UI build).
- Mac model and macOS version.
- Build commit.
- USB connected? Debugging enabled? Wireless debugging enabled?
- Bluetooth state, Wi-Fi network type (and router notes if relevant).
- Permissions granted, and the exact prompt text observed.
- Steps.
- Expected result.
- Actual result (verbatim error codes where applicable).
- Logs/screenshots (no private content).
- Metrics, if any were measured (record numbers, not adjectives).
- Limitations and what this test does **not** prove.
- GO / CONDITIONAL / NO-GO for the capability it gates.
- Recommendation and next step.
