# Prototype Plan and Feasibility Gates

All prototypes are small, isolated and disposable. During development, ADB may install/launch/stop test apps, open Android Settings, automate setup-only actions (including connecting an already-paired test HID peripheral), run tests and collect sanitized diagnostics. ADB must never generate the functional input under test (`input`, `sendevent`, instrumentation or equivalent), capture screen/UI hierarchy, or read/write clipboard content. For HID experiments, Bluetooth HID is the only source of functional test reports. The owner must verify a first-pairing code before confirmation. Record ADB transport/debugging state and keep setup automation separate from HID-input observations. Final acceptance requires USB disconnected and both debugging modes disabled; perform setup and observations without ADB.

**Research pass 2026-11-08:** the instructions below were expanded for the uncertainties identified in `RESEARCH_AND_FEASIBILITY.md` §3 (U1–U17). A prototype is only "complete" when its report contains real evidence.

**Status 2026-11-20:** P16 BLE HOGP is **closed as NO-GO for Android** and P17 Classic HIDP is a **preliminary GO on the OnePlus only**. Android bonds the Mac's BLE advertisement but never creates a HID input device — reproduced with both the project POC and the unmodified upstream BLE app — because the Mac advertises on its public BR/EDR address and Android lists it `[ DUAL ]`, routing HID over Classic. Upstream's Classic mode on the same hardware published an HID SDP record, opened L2CAP `0x0011`/`0x0013`, registered four Android input devices and delivered arrow press/release input to a focused receiver with USB disconnected and wireless debugging off. That used the AGPL upstream app, not DroidBridge code; the S24 Ultra, reconnect/relaunch cycles and a debugging-off acceptance run remain outstanding, and no upstream source may be adopted. Check `PHASE_STATUS.md` and `PROGRESS_LOCAL.md` for current P00–P17 evidence; see [P17](prototypes/P17-classic-hidp.md), [P16](prototypes/P16-ble-hid.md) and ADR-041.

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

Media-pipeline additions (2026-11-08, R-29):
- Add a surface-fed H.264 encoder: `configure(format, null, null, CONFIGURE_FLAG_ENCODE)` → `createInputSurface()` → passed to `createVirtualDisplay()`. Never allocate pixel buffers; log `CodecCapabilities.colorFormats` and the encoder's accepted output format.
- Record which sizes the encoder accepts via `VideoCapabilities.areSizeAndRateSupported()`: test 3120×1440, 2560×1440 and 1920×1080 at 30 fps, and record the exact rejection exception where a size fails (U18).
- Record the profile the encoder selects when `KEY_PROFILE` is unset, then pin `AVCProfileBaseline` and `AVCProfileHigh` and record whether the session is accepted and what the output format reports.
- Record the bitrate actually produced for a text-heavy screen (settings list, web page with small text) in VBR and in CBR at the same requested bitrate, to see the API-31 quality floor in the numbers rather than in theory.
- Set `KEY_MAX_B_FRAMES = 0` and confirm the encoder reports no reordering; record whether `KEY_LATENCY` is honoured in the output format.
- Leave the screen still for 30 s with and without `KEY_REPEAT_PREVIOUS_FRAME_AFTER` and record whether frames keep arriving (a still screen that produces nothing looks like a frozen viewer).
- In async mode, call `flush()` without `start()` and confirm frame production stops — the documented trap — then fix the restart path.

Acceptance:
- Normal screen frames captured.
- System stop is handled.
- No secure content bypass.
- No crash/leak after 20 start/stop cycles.
- The accepted encode size, profile and bitrate mode are recorded as measured facts, not assumptions.

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
- Display a generated test H.264 stream in a native Mac window, and prove the framing and timing rules before any Android code is involved (R-30, R-31).

Tests:
- Build `CMVideoFormatDescriptionCreateFromH264ParameterSets` from SPS + PPS as bare NALs (at least 2 parameter sets), then `CMSampleBufferCreateReady` per frame with 4-byte length-prefixed NALs, and enqueue on `AVSampleBufferDisplayLayer`.
- Byte-level conversion test: feed a known Annex-B stream, convert start codes to 4-byte lengths, and assert the resulting samples decode — including a NAL that contains `0x00 0x00 0x03` emulation-prevention bytes, to prove they are preserved.
- Assert the two documented failure modes explicitly: passing parameter sets *with* start codes must fail or produce a broken picture, and a wrong `nalUnitHeaderLength` must fail. Recording these makes the conversion rule unambiguous for the implementer.
- Timing: strictly increasing PTS in a 1/1,000,000 timescale from `presentationTimeUs`; assert that duplicate PTS is rejected by the code path rather than silently enqueued. Include one deliberately non-monotonic PTS to see what the layer does.
- Backpressure: stop draining and confirm `isReadyForMoreMediaData` goes false, then confirm the viewer coalesces frames instead of growing a queue.
- Recovery: post a corrupt sample or background the app to force `.failed` / `requiresFlushToResumeDecoding`, then `flush()`, request a keyframe, and confirm display resumes. Record the exact status transitions observed.
- Deprecation audit (U20): build with warnings-as-notes and record every deprecated member the viewer touches, plus whether `AVSampleBufferVideoRenderer.Receiver` is available on the installed SDK. Do not change the deployment target to silence them.
- Measure added latency: compare capture timestamp to on-screen timestamp (high-speed camera or a burned-in millisecond counter) so the layer's contribution is a number, not a guess.
- rotation/resolution changes, aspect-fit letterboxing, dropped frames, stream stop/restart.

Acceptance:
- stable native viewer, independent of Android capture.
- The conversion and timing rules are proven, not assumed, including both negative cases.
- A recorded latency figure for the display path and a recorded deprecation list.
- `preventsCapture = true` verified: a Mac screen recording does not contain the mirrored content.

## P08 — End-to-end mirroring stream

Goal:
- P01 + P02/P03 + P07 integrated over the ADR-012 transport.

Compare (measurement gate retained from ADR-009):
- TLS/TCP framing with length-prefixed video frames.
- WebRTC with local-only candidates, no STUN/TURN (only if the simple path misses targets, and note that on macOS 26+ `RTCPeerConnection` itself can trigger the local-network prompt).

Measure:
- startup time, end-to-end latency, frame rate, bitrate, CPU/GPU, battery/thermal, reconnect time.

Additional media checks (R-29, R-30):
- Rotate the device mid-session and record whether `onCapturedContentResize()` leads to a re-encode: new `mirror.format`, fresh keyframe, and whether the Mac swaps its format description without a visible stall or a stuck black frame.
- Record whether the encoder emits new SPS/PPS after rotation or resize, and whether they are identical to the stream-start ones (U19). This decides whether `mirror.format` is a stream-start message or a recurring one.
- Kill the Mac viewer's decoder (background it or feed a corrupt sample), then confirm recovery works over the real transport: flush, request keyframe, resume — with no stale P-frames displayed.
- Record bitrate and frame rate for a static screen with `KEY_REPEAT_PREVIOUS_FRAME_AFTER` and without it, so "idle" cost is a measured number.

Acceptance:
- stable 30-minute session on same Wi-Fi with USB disconnected.
- session stops when MediaProjection stops (U10: include a locked-screen and screen-off run).
- no protected content bypass.
- record actual numbers; do not declare targets met without data.
- recovery after a decoder flush is demonstrated end to end, including the keyframe request.

## P09 — Initial remote input feasibility checks (historical; scoped NO-GO)

Goal:
- Record the original tests for public input injection, Android-as-HID-device direction, and BLE HID publication. This is not the final Mac-as-HID feasibility decision; R-32/ADR-036 reopened that question and P16/P17 now test it independently.

Experiments already recorded in the historical report:
1. Attempted to add a macOS `CBPeripheralManager` service `0x1812` (HID), recorded the result as a refusal.
2. Considered companion Device Information (`0x180A`) and Battery (`0x180F`) services.
3. Considered Classic Bluetooth HID transport constraints; the later upstream source review found a distinct HIDP route that P09 did not test.
4. Confirmed Android `BluetoothHidDevice` makes Android the peripheral, not a Mac-driven host.
5. Checked a physical keyboard/mouse ceiling and the Android 17 password masking behavior.
6. No Accessibility, ADB, root or Knox was introduced.

Acceptance:
- Preserve P09 only as evidence for the mechanisms tested then.
- Do not use P09 as the final NO-GO for Mac HID; complete P16/P17.
- Typing into a focused field is not arbitrary touch injection and does not set the Android clipboard.

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

## P15 — Sandboxed, signed and notarised macOS build

Goal:
- Prove that the Mac app behaves the same when built the way it will ship — App Sandbox on, Developer ID-signed, hardened runtime, notarised — and record what changes between an ad-hoc build and a signed one (U14, U17, R-27, R-28, ADR-025, ADR-026).

Prerequisites:
- macOS with Xcode, and the P13 identity code.
- An Apple Developer Program membership for the signed and notarised steps. Steps 1–4 run without one; every step that could not be run must be listed as not run, and the release dependency (ADR-026) recorded instead of a pass.
- A test bundle identifier that is not the shipping one.

Setup:
- Entitlements file: `com.apple.security.app-sandbox`, `com.apple.security.network.client`, `com.apple.security.network.server`, and `keychain-access-groups` for the app's own group. Keep a second entitlements file without the keychain group, for the legacy-keychain comparison.
- `Info.plist`: `NSLocalNetworkUsageDescription` and `NSBonjourServices` = `_droidbridge-dev._tcp`.
- Minimal target: `NWListener` advertising on a dynamic port with `_droidbridge-dev._tcp`, `NWBrowser` logging peers, and a one-line echo on each accepted connection. No production code, no protocol work.

Tests:
1. Ad-hoc "Sign to Run Locally" build with the sandbox on: does advertising succeed, does the Local Network prompt appear, and does the app then appear in System Settings → Privacy & Security → Local Network? Record the exact prompt text.
2. Connect from a second machine (`nc`, or the P02 client) and confirm the sandbox does not block accepting: the accepted socket must echo in both directions.
3. Remove `network.client`, keep `network.server`: confirm TCP accept still works while Bonjour stops answering. This is the concrete proof behind R-28's claim that advertising needs both entitlements.
4. Rebuild after a clean checkout so the ad-hoc signature changes: record whether the Local Network privilege is retained or asked again. That is the development re-prompt expected in R-27, and it must be documented so it is not later mistaken for a product bug.
5. Developer ID-signed build with `-o runtime --timestamp`: repeat tests 1–2, and label the result "signed, not notarised".
6. Keychain: store the identity, quit, relaunch and read it back. Re-sign the same bundle with the same certificate and repeat. If a second certificate or team is available, re-sign with it and record whether the item is still readable (U17).
7. Notarisation: `ditto -c -k --keepParent`, `xcrun notarytool submit … --wait`, `xcrun stapler staple`, then `spctl -a -vvv -t install` and a launch from a **quarantined** copy (download it rather than copying locally, or `xattr -w com.apple.quarantine`). Paste the notary log's warnings, not only the `Accepted` status.
8. Code-review evidence: paste `codesign -d --entitlements -` for the shipped bundle; confirm no `get-task-allow`, no `--deep` in the build script, and that the embedded profile (if used) is a distribution profile.

Acceptance:
- A written statement of the exact entitlement set under which advertising, accepting and keychain access all work sandboxed.
- A recorded difference (or none) between ad-hoc and Developer ID builds, including the Local Network privilege behaviour.
- The notarisation path proven end to end with a stapled ticket, or explicitly reported as not run for lack of a membership.
- Definitive answers for U14 and U17.

## P16 — macOS Bluetooth LE HID feasibility on the S24 Ultra

**Implementation/result (2026-11-20):** P16 is **closed as NO-GO for Android**. Android bonds the Mac's BLE advertisement but never binds it as a HID input device (`HidHostService.mInputDevices` stays empty, `mTargetDevice: null`) and the Mac records zero GATT reads, writes or subscriptions. The identical outcome from the unmodified upstream BLE app shows this is Android host classification, not a GATT layout defect: the Mac advertises on public address `60:3E:5F:31:D0:6D`, which is also its BR/EDR address, so Android lists it `[ DUAL ]` and routes HID over Classic. Canonical UUIDs, the required Battery/Device Information/PnP ID services and the Boot-only Report Map stay in the record but cannot change routing, and public `CBMutableDescriptor` still cannot express Report Reference `0x2908`. BLE HOGP remains recorded as an iOS-host path only. The Mac→Android route moved to [P17 Classic HIDP](prototypes/P17-classic-hidp.md). See R-33/R-34 and ADR-038/ADR-039/ADR-041.

Goal:
- Test whether an independently implemented, standard Bluetooth HID over GATT peripheral from macOS can pair with and provide useful input to the target Android phone. This is a feasibility POC only, not production code and not a full-control claim.

Reference:
- `jqssun/darwin-bt-remote` public repository, reviewed at commit `e64cae983b8816e7e2f067e79025ffc49faf7e2d` (2026-11-04), describes a `CBPeripheralManager` HID-over-GATT implementation. Its optional Direct Input mode uses macOS Accessibility and is excluded. Its AGPL-3.0-only license means no copying/vendor integration without a separate licensing decision.
- Samsung compatibility report: https://github.com/jqssun/darwin-bt-remote/issues/13 (open when checked 2026-11-17; iPhone 16 + Galaxy S9 / Android 10 reports unreliable pairing and ineffective media controls). This does not predict the S24 Ultra result.

Setup and guardrails:
- Separate macOS experiment target and isolated Bluetooth entitlement if required; no production-module dependency, no Accessibility prompt/permission, no event tap, no global input capture.
- For development setup only, ADB may open Android Bluetooth Settings and connect an already-paired test peripheral. At first pairing, the owner must visually compare and confirm the displayed code; never auto-accept an unverified code. DroidBridge sends test reports only after explicit POC UI action; ADB must not generate the functional input under evaluation.
- Use a purpose-built Android test screen/app with visible counters for received keyboard keys, pointer movement, button actions and scroll events. Do not use banking, payment, password-manager, OTP, DRM or other sensitive/protected apps. Do not change Developer Options or screen-capture protections.
- Bluetooth activation/advertising must follow explicit user action and be visibly stoppable.
- Do not import upstream source; implement the minimum standard descriptor/transport independently if the separate POC proceeds.

Tests:
1. Record Mac hardware, macOS, Bluetooth chipset, Android model/API/One UI/build, Bluetooth state, and USB/debugging states.
2. Add the HID service on macOS and record exact CoreBluetooth state/error; start/stop advertising and observe from Android Bluetooth Settings. ADB may open Settings for development setup but must not accept an unverified code.
3. Pair from Android Settings with the owner verifying the pairing code; after successful bonding, ADB may automate connection/reconnection setup. Record the actual UX and do not clear unrelated pairings.
4. From the POC UI, send only a harmless test key (e.g. arrow key) while the visible test screen has focus. Then test normal text in a non-sensitive test field using synthetic text; never use real clipboard content.
5. Evaluate BLE pointer reports separately: movement, click, drag, scroll, Android navigation and touch-oriented UI. Record input source and what is actually delivered; do not infer touch semantics from pointer motion.
6. Test disconnect, Android Bluetooth off/on, Mac app stop/relaunch, and stale bond recovery. No private APIs, reflection, shell commands or undocumented device interfaces.
7. Run a USB-disconnected, USB-debugging-off and wireless-debugging-off manual acceptance round.

Acceptance:
- First pass the Mac service-publication gate using canonical registered UUIDs plus Battery/Device Information/PnP ID; if publication still fails, record NSError domain/code and stop rather than substituting a custom UUID. P16 is Boot Keyboard only because Apple's public local-descriptor API cannot create Report Reference `0x2908`. Pair/connect only after publication. The Android host must select Boot Protocol and subscribe before any arrow report is allowed. ADB may support development setup/diagnostics only; the owner verifies any first-pairing code, and ADB never synthesizes test keys. If Android requires Report Protocol or remains disconnected, stop and record incompatibility. Final S24 acceptance uses USB disconnected and debugging disabled. P17 is independent. See [P16 procedure](prototypes/P16-ble-hid.md).
- No Accessibility, ADB-generated functional input/data path, protected-screen interaction, or broad Android permission is introduced. Setup-only ADB automation is development-only and not part of the product/runtime path.
- Do not claim generic touch injection, banking-app support or complete control from HID keyboard/pointer success.

## P17 — macOS Bluetooth Classic HIDP feasibility on the S24 Ultra

Goal:
- Independently test the Bluetooth Classic HIDP route described by the reference project, because the earlier P09 assessment did not test this upstream route. This remains an isolated POC only.

Reference and licensing: same as P16. Upstream describes SDP HID records and L2CAP HID control/interrupt channels with a Mac-initiated connection; do not copy its implementation. Apple's public IOBluetooth APIs and entitlement requirements must be confirmed for the current macOS before use. If feasibility depends on undocumented behavior, privilege escalation, or unsupported private interfaces, stop and record NO-GO.

Tests:
1. Use the same device/environment evidence header and USB/debugging guardrails as P16.
2. Independently publish a minimal HID SDP record and attempt Android-initiated discovery/pairing as supported by the system UX. Record what Android lists and exact connection behavior.
3. If paired, connect via the documented/public POC path and send synthetic key reports to the visible test screen; separately measure pointer support and UI behavior.
4. Test disconnect, app relaunch, phone Bluetooth toggle and bond recovery. No private APIs, reflection, ADB-driven UI, shell input, screen capture commands, or sensitive apps.
5. Run a USB-disconnected, USB-debugging-off and wireless-debugging-off manual acceptance round.

Acceptance:
- Exact discovery/pairing/connect result and supported input classes recorded on the S24. A success for keyboard does not establish pointer, swipe, touch-equivalent or clipboard support.
- If Classic requires unsupported/private interfaces, stop the path; do not bypass OS policy.
- No new product permission/entitlement is added until separately reviewed against official docs and the permission matrix.

**Implementation/result (2026-11-20, OnePlus preliminary):** Upstream's Classic mode on a MacBook Air M1 (macOS 27.2.1) published an HID SDP record (`handle=1330188580`), opened L2CAP `0x0011` control and `0x0013` interrupt channels to OnePlus Nord AC2001 / Android 12 / API 31, made Android register `MacBook Air Keyboard`, `Mouse`, `Consumer Control` and `System Control` with `STATE_CONNECTED`, and delivered arrow press/release pairs counted by the focused P16 receiver (`UP 8, DOWN 3, LEFT 3, RIGHT 1`; machine-captured `0/0/0/0` → `UP 2, DOWN 2, LEFT 1`). USB was disconnected and wireless debugging off; USB debugging was enabled, so this is not final acceptance. Public `IOBluetooth` only — the upstream `_privateBool` KVC helper is dead code. This used the AGPL upstream app as a probe, not DroidBridge code, and covers keyboard arrows only. macOS automatic termination dropped the session once and must be handled. Remaining: independent implementation, S24 Ultra, reconnect/relaunch/toggle cycles, debugging-off acceptance. See [P17](prototypes/P17-classic-hidp.md) and ADR-041.

## Independent feature POC sequence and guardrails

- **Control:** P16 then P17 compare BLE and Classic HID independently. Accessibility stays prohibited. HID is treated as a standard physical keyboard/pointer, not an input-injection API.
- **Screen mirroring:** use existing P01/P07/P08 and protected-screen P10 procedures only. Require MediaProjection consent; never use ADB `screencap`/`screenrecord`, alternate capture, or Developer Options protection bypass. P08's wireless/debugging-off acceptance remains outstanding.
- **Clipboard:** use existing P04/P05/P06 foreground/manual API paths and synthetic content only. No ADB clipboard commands, background-reading workaround, content logs or history.
- A USB cable or enabled debugging can be present for installation/sanitized diagnostics in early development, but functional evidence is invalid if the control/mirror/clipboard behavior depends on ADB. Report USB, USB debugging and wireless debugging state for every session and repeat all accepted behavior with all three disabled.

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
