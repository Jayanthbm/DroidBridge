# Local Development Progress

**Status:** Phase 0 complete. Phase 1 in progress: P00, P01, P02 and P04 verified on the target
device; P03 implemented and verified on loopback plus three physical S24 Ultra runs (pairing, Wi-Fi
drop and reconnect, and the final USB-disconnected acceptance run). All six P03 acceptance criteria
are met.

**Last updated:** 2026-11-09

**Current gate:** Phase 1 (prototypes P00–P15). P00 baseline complete for both devices. **P01 is
validated on the S24 Ultra (Android 17 / API 37)**: consent, foreground service, surface-fed H.264,
rotation, lock/`onStop`, user stop, 20 start/stop cycles, secure-content protection and consent-token
replay were exercised. P04 is verified on both Android 12 and Android 17. **P05 is completed on Android 12 and Android 17:** the Mac→Android
clipboard matrix passes on both, including Android 17's generic "Copied." confirmation and the
`ACCESS_LOCAL_NETWORK` requirement outside the picker; the active-mirroring-FGS integration context
is deferred to P08. **P07 is in progress:** the Annex-B → AVCC → format-description → `AVSampleBufferDisplayLayer`
pipeline decoded and displayed a generated 640×360 fixture (`readyForDisplay=true`,
`status=rendering`) and recovered after a flush; backpressure/latency/preventsCapture are pending.
**P06 is in progress:** macOS 27.0.1 reports
`accessBehavior=alwaysAllow`, no prompt appears, and self-write suppression works; the deny/ask
states need a signed P15 build or another macOS version.

**P02 is validated and accepted on the target device (2026-11-09).** Picker approval, approved-only
rediscovery, 60-second sustained duplex (198/198 messages, 0 mismatches), recovery from a real
airplane-mode outage without re-approval, Android process recreation and Android reboot all passed.
**Mac sleep/wake passed on the third attempt**: `pmset` recorded Sleep at 21:41:50 and Wake at
21:42:15, and the phone reconnected to the same Mac port 64615 at 21:42:14 with no picker
(536/535 messages, 0 mismatches, 1 expected lost reply). The **Mac advertiser/cold restart** is also
measured and exposes one required re-approval when the Mac's service instance changes. Advertising
and inbound need `ACCESS_LOCAL_NETWORK`; the picker, approved-only discovery and outbound TCP do
not. That permission's revocation was not applied to a running process until reboot.

**P03** has matching codes on both screens, duplex data on a confirmed session, authenticated
reconnect with stored pins, and a changed Mac identity correctly refused. A real Wi-Fi drop closed
the session on both ends and the phone reconnected with the stored pin without re-pairing. The
final acceptance run passed with USB disconnected and USB debugging, wireless debugging and
Developer Options all off; that wireless-only run is P03 evidence, not a P01/P02 re-run.

## Device and environment

- Primary target device: Samsung Galaxy S24 Ultra, model **SM-S928B** (device `e3q`), region India.
- Android: **17, API 37** — verified on the device via `getprop`, not owner-reported.
- One UI: **9.0** (`ro.build.version.oneui=90000`) — verified on the device.
- Build: `CP2A.260605.016.S928BXXS6EZJ1` (`S928BXXS6EZJ1`), security patch **2026-10-05**,
  `ro.build.type=user`, `ro.boot.verifiedbootstate=green`.
- `android.permission.ACCESS_LOCAL_NETWORK` is present on the device and the NSD service
  (`android.net.nsd.INsdManager`) is live, so the API 37 discovery path is testable here.
- Mac: MacBook Air (M1, 2020), 8 GB, macOS 27.0.1 (26A434), arm64.
- Xcode 27.2 (27B52), Swift 6.4.2, macOS SDK 27.2, xcodegen 2.46.0.
- JDK: Zulu 17.0.21 on PATH (build daemon runs JDK 27 from Homebrew); Gradle 9.8.2; AGP 9.5.1.
- Android SDK: platforms 34/36/37.0, build-tools 34–37, adb 37.0.2.
- Temporary test device: OnePlus Nord CE (AC2001), Android 12 / API 31, build `AC2001_11_F.24`,
  security patch 2023-12-05, `ro.debuggable=0`, bootloader unlocked (verified boot `orange`).
  Treated as an ordinary non-rooted device throughout.
- USB debugging: development-only allowance; final release must not require it.
- Bluetooth: optional and non-core.

## Repository state

- Branch: `development`.
- Content: `AGENTS.md`, `README.md`, `Docs/*`, `.gitignore`, `shared/protocol/` (pure Kotlin/JVM),
  `android/` (10 modules), `macos/` (xcodegen project), `scripts/`, `.github/workflows/`.
- Build/test tooling: Gradle wrapper 9.8.2 + AGP 9.5.1 for Android; Xcode 27.2 + xcodegen for
  macOS; Gradle wrapper 8.10.2 for `shared/protocol`. Both native toolchains are present on this
  machine, so both shells build here.

## Completed

- [x] Product concept and constraints documented.
- [x] Two-native-app direction documented.
- [x] Wi-Fi-first / optional Bluetooth decision recorded.
- [x] Research baseline added.
- [x] Prototype plan added.
- [x] Security and permission policies added.
- [x] Agent workflow added (2026-11-07).
- [x] **2026-11-08 research pass — official-source verification of every high-risk dependency:**
  - Android 17 local network permission model and the NSD advertising/discovery asymmetry.
  - Android 17 certificate-transparency default and its interaction with pinned custom anchors; ECH default.
  - MediaProjection consent/FGS/stop rules, selected-app capture, resize/visibility callbacks.
  - Android 15+ screenshare protections (notifications, password fields, OTP activities) and `FLAG_SECURE`.
  - Android clipboard focus/IME gate, clipboard toast, sensitive-clip flag.
  - macOS local network privacy (TN3179) and the requirement that the Mac advertise under the new design.
  - macOS 15.4+ pasteboard access prompts — newly identified risk to automatic clipboard sync.
  - macOS Bluetooth HID peripheral refusal; Android `BluetoothHidDevice` reverse direction.
  - Knox `RemoteInjection` permission/authorisation eligibility and DeX API removal on Android 16+.
  - Audio playback capture boundaries; impossibility of call audio; Android 17 background-audio hardening.
  - Transport comparison (TLS/TCP vs WebRTC) and the macOS mDNS/local-network interaction.
  - Background session persistence: `connectedDevice` foreground service type, its zero-prompt prerequisite, the absence of a timeout, boot-start legality, and Doze's device-wide network suspension.
  - Foreground-service notification visibility (`POST_NOTIFICATIONS`) and the Play Console FGS declaration.
  - Identity and TLS mechanics: Keystore-generated self-signed certificate on Android, `swift-certificates` on macOS (Security framework cannot create certificates), SPKI pinning, and the macOS server-side peer-authentication default.
  - Distribution requirements: Play's target-API floor and the restricted `ACCESS_LOCAL_NETWORK` permission declaration, per-type foreground-service declarations with demo videos, macOS Developer ID/hardened runtime/notarisation, and the App Sandbox network and keychain entitlement rules.
  - Media pipeline: surface-fed encoding (no colour handling in app code), the API-31 quality floor applying only to VBR, profile rejection and `KEY_LEVEL` not being a cap, B-frames defaulting to zero, the Annex-B→AVCC mismatch between MediaCodec and CoreMedia, and the deprecation of `AVSampleBufferDisplayLayer`'s enqueue API in favour of a macOS 27 replacement.
- [x] Research register, decisions, permissions matrix and prototype instructions updated from that pass.
- [x] **2026-11-08 shared protocol module — H.264 media framing (`shared/protocol/`):** Annex-B start-code splitting (both start-code lengths, trailing-zero padding, rejection of unframed or empty NAL units), codec-specific-data parsing into bare SPS/PPS, and Annex-B→AVCC conversion with 4-byte big-endian lengths plus IDR detection; `mirror.format` payload with deterministic JSON and Base64 parameter sets. 31 unit tests, all passing on JVM 21 (`./gradlew test`); two mutation checks confirmed the start-code and padding tests fail when the rule they cover is broken. This is the producer side of `NETWORK_PROTOCOL.md` §7 and ADR-027. **Not device-tested** — no phone was involved, and nothing here proves encoder output, only the framing rules.

- [x] **2026-11-09 Phase 0 — repository and environment (complete).**
  - `android/`: Kotlin + Compose shell, Gradle KTS, AGP 9.5.1, `compileSdk`/`targetSdk` 37,
    `minSdk` 31. Modules: `app`, `core:{common,protocol,network,pairing,security}`,
    `feature:{dashboard,clipboard,mirroring,settings}`. SDK levels and lint policy live once in the
    root build file; versions and SDK numbers live once in `gradle/libs.versions.toml`.
  - `shared/protocol` is consumed as a **composite build** (`includeBuild` + dependency
    substitution), not copied. `core/protocol/MediaFraming.kt` is a facade whose only job is to make
    that linkage a compile-time fact.
  - `macos/`: Swift + SwiftUI shell, `project.yml` (xcodegen) as source of truth with the generated
    `.xcodeproj` committed. App Sandbox on from the first build with exactly the three audited
    entitlements; `NSLocalNetworkUsageDescription` and `NSBonjourServices` declared.
  - `scripts/`: `env-baseline.sh` (regenerates the P00 baseline), `verify-all.sh` (Phase 0 gate and
    CI entry point), `android-install.sh` (development install helper).
  - `.github/workflows/verify.yml`: shared protocol + Android assemble/test/lint on a clean runner,
    read-only token, no secrets.
  - **Verified:** 31/31 shared protocol tests; Android `assembleDebug` + `lintDebug` clean (5 real
    lint findings fixed at source); macOS Debug and Release builds; 4/4 macOS unit tests; all three
    rebuilt from a clean checkout with no `local.properties`; APK installed and launched on the
    OnePlus with no crash and no ANR.
  - **Audited:** Android manifest declares no permissions (only the AndroidX-injected app-private
    `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` appears in the APK); macOS Release carries exactly
    `app-sandbox` + `network.client` + `network.server` and **no** `get-task-allow`.
  - **Defect found and fixed:** Xcode injected `get-task-allow` into Release builds, which the
    notary service rejects. Fixed with `CODE_SIGN_INJECT_BASE_ENTITLEMENTS: NO` on Release only.
  - Reports: `Docs/prototypes/P00-environment-report.md`,
    `Docs/prototypes/PHASE0-repository-and-environment.md`.
  - **Not verified:** the dashboard's rendered content on the device — the phone was PIN-locked, so
    `screencap` returned nothing and the UI hierarchy belonged to the keyguard. The app's launch,
    resumed state and absence of crashes are confirmed; the pixels are not.

## Not started

- [x] Create the Android project shell (`android/`, Kotlin + Compose, Gradle KTS).
- [x] Create the macOS project shell (`macos/`, Swift + SwiftUI, Xcode project).
- [x] Add `.gitignore` (Gradle, Android, Xcode, IDE, secrets).
- [x] Add CI/build scripts.
- [x] Capture exact host toolchain and temporary-device details (P00 host half).
- [x] Capture S24 Ultra build/API details (P00 device half).
- [x] Run P01 MediaProjection prototype (Android 12 and Android 17).
- [x] Run P02 Android 17 LAN strategy prototype (picker path, sustained duplex, recovery).
- [x] Run P03 pairing/transport prototype.
- [x] Run P04 clipboard prototype (Android 12 + Android 17).
- [ ] Run the remaining P05 clipboard prototype.
- [ ] Run P06 macOS pasteboard prompt prototype.
- [ ] Run P07/P08 video receiver and end-to-end stream prototypes.
- [ ] Run P09 remote-input confirmation experiment.
- [ ] Run P10 secure-screen/screenshare-protection tests.
- [ ] Run P11 macOS local-network permission tests.
- [ ] Run P13 pinned TLS/CT prototype.
- [ ] Run P14 background-session persistence prototype (screen-off, Doze, overnight, reboot).
- [ ] Run P15 sandboxed, signed and notarised distribution prototype (entitlements, TCC, keychain after re-signing).
- [ ] Confirm Apple Developer Program membership and Account Holder access for the Developer ID certificate.
- [x] Verify the owner-reported Android 17 / One UI 9 version on the device.

## Current blockers

- **Full remote input is NO-GO** under the current constraints (research conclusion, not a device test): no public injection API, Knox is signature/managed-device gated, macOS cannot publish a HID peripheral.
- **Automatic background clipboard sync is not a release claim.** Android requires focus/IME for reads; macOS 15.4+ can prompt or deny programmatic pasteboard access.
- **Android 17 LAN path is resolved for the primary design.** P02 proved the picker path on the S24 Ultra: approval, resolve, connect, 60 s duplex and approved-only recovery all work. Discovery, approved-only discovery and outbound TCP work **without** `ACCESS_LOCAL_NETWORK`; advertising and inbound listening require it. Revoking it does not take effect for a running process until reboot. The product must keep the picker as its consent mechanism and must not treat the permission as the sole boundary. The one open P02 item is Mac sleep/wake; the Mac advertiser/cold restart is measured and requires a one-time re-approval for a new service instance.
- **Mac-as-Bluetooth-HID-peripheral is not proven and is expected to fail** (`CBError.uuidNotAllowed`); P09 exists to record the evidence.
- **Background clipboard sync is best-effort, not guaranteed.** The `connectedDevice` foreground service keeps the session alive in normal use, but Doze suspends network access device-wide, so the design is reconnect-on-resume rather than an always-on promise. Whether a socket survives a full night is unproven (U11).
- **The macOS app cannot be released without a paid Apple Developer Program membership.** Notarisation requires a Developer ID certificate, only the Account Holder can create one, and an unnotarised app is blocked by Gatekeeper on current macOS (R-27). This is an external dependency on the owner, not a technical uncertainty, and it blocks Phase 9 rather than Phase 0.

## Tests and evidence

- **2026-11-09 (Phase 0):** 31/31 shared protocol tests (JVM); Android `assembleDebug`,
  `testDebugUnitTest`, `lintDebug` all pass; macOS Debug + Release builds and 4/4 unit tests pass;
  clean-checkout rebuild of all three passes; APK installed and launched on OnePlus Nord CE
  (Android 12 / API 31), `ResumedActivity` held, 0 crash entries, 0 ANRs.
- **Not captured (Phase 0):** a device screenshot. The phone was PIN-locked, so `screencap`
  produced empty output and `uiautomator` dumped the keyguard.
- **2026-11-09 (P01, S24 Ultra):** encoder `c2.qti.avc.encoder`; 3120×1440, 2560×1440, 1920×1080 and
  1280×720 all accepted at 30 fps; output 1280×720 Baseline/level 3.1 VBR with SPS 21 / PPS 8; a
  189 s session produced 12,610 frames / 32,960,629 bytes / 211 keyframes at a 1,392 kbps mean with
  0 crashes; rotation produced `onCapturedContentResize` 1080×2340 ↔ 2340×1080 and `resize()` was
  accepted; lock fired `onStop()`; 20/20 start-stop cycles completed with 2,005 frames and 0
  crashes in the same process; FLAG_SECURE content measured luma 0 versus 159 for normal content.
- **2026-11-09 (P02, S24 Ultra):** 60 s duplex 198/198 messages, 0 mismatches, 0 errors; airplane
  outage recovery 475/474 with approved-only rediscovery and no picker; process recreation and
  reboot both rediscovered and connected; inbound requires `ACCESS_LOCAL_NETWORK` on a clean boot,
  while advertising throws `Missing local network permission` without it.
- **2026-11-09 (verification):** `JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
  ./scripts/verify-all.sh` passed end to end: **78/78 shared-protocol tests**, Android `:app` plus
  the media-projection, discovery and clipboard experiments assembled, unit-tested and linted, and
  **37/37 macOS tests** passed. The JDK 17 override is required because the shared-protocol Gradle
  8.10.2 wrapper cannot run on the default Homebrew JDK 27 (it fails with a bare version string).
  This is an environment fact to document in the reproduction steps, not a code defect.
- P00–P04 now have device evidence. P05 onward remain unrun; no later prototype has been started.

## USB / debugging state

- **Phase 0 device session (2026-11-09):** USB connected **yes**; USB debugging enabled **yes**;
  wireless debugging enabled **no**. Development session only.
- **P01/P02 validation sessions (2026-11-09):** USB connected **yes**; USB debugging enabled
  **yes**; wireless debugging enabled **no**; Developer Options enabled. The APKs were installed
  over ADB and the device UI was driven over ADB for the automated runs. The final wireless-only
  gate was not re-run for P01/P02; the documented USB-disconnected run belongs to P03. This is
  recorded so the P01/P02 evidence is not presented as wireless-only evidence.

## Workflow from here

1. P01 is validated on the S24 Ultra; its remaining items are plan-only extras (screen-sharing chip,
   `KEY_LATENCY`, still-screen repeat-frame comparison, `flush()` without `start()`).
2. **P02 is validated and accepted**, including Mac sleep/wake. The Mac advertiser/cold restart is
   measured: a new Mac service instance requires one picker re-approval before approved-only
   reconnect works. Record that as a product/architecture limitation for Phase 2, not an open test.
3. Update the detailed reports with the final results, review the diff, commit and push to
   `development`.
4. Do not begin P05 or later until the owner agrees. The Android 12 backup device cannot help P02
   because the API is 37-only; P01 already has Android 12 evidence.

## Next task

P02 is accepted, including the Mac sleep/wake pass. The Mac restart finding (one re-approval per new
service instance) is recorded as a product limitation, not as a test failure. Documentation is
committed and pushed. No later prototype is in scope.


## Agent handoff status (2026-11-08)

Local Phase 0 is complete and pushed. The cloud agent may now review the Phase 0 commit — specifically
the build configuration, the manifest/entitlement audit in `PERMISSIONS_MATRIX.md` §6, and the two
Phase 0 reports — before local work continues into P01. Cloud must not modify app code or claim
prototype/device results.

### P02 — Android 17 local networking (blocked, 2026-11-09)

Built the Mac half of Path A step 1: `NWListener` advertising `_droidbridge-dev._tcp` with a
`v`/`sid` TXT record, an echo handler, and a UI showing live connection events.

**Verified:** 10 macOS tests pass; TCP duplex echo over loopback is byte-exact; the listener accepts
an inbound connection from the phone (192.168.31.64) and binds all interfaces; the phone reaches
the Mac (0% ping loss).

**Blocked, two independent reasons:**
1. The picker path needs `android.net.nsd.DiscoveryRequest`, which exists only in the API 37
   platform (confirmed against the installed SDK, not documentation). The OnePlus is API 31, so
   the prototype cannot run on it at all. U1, U2 and U16 are unanswered.
2. Bonjour does not publish from this host. The listener reports `.ready` but `dns-sd -B` never
   lists it, and an inbound connection from the phone times out before completing. A minimal
   non-sandboxed control binary fails the same way, so it is not the app's code or the sandbox.
   The macOS Local Network permission was never requested (no `kTCCServiceLocalNetwork` in `tccd`)
   and no prompt appeared. Little Snitch 6.2.3 and an Urban VPN configuration are present and may
   also interfere. Both are owner decisions.

**Defect found:** SwiftUI builds `TabView` content lazily, so a `@StateObject` inside a tab's view
is not created until the tab is selected — `--advertise-on-launch` silently did nothing. The model
is now owned by the `App`.

Report: `Docs/prototypes/P02-local-networking.md`.

### P04 — Clipboard Android to Mac (matrix unrun, 2026-11-09)

Built `android/experiments/clipboard/`, a separate app declaring no permission at all, with a probe
that runs one matrix row per action and logs a greppable observation line.

**Verified:** 9 unit tests pass over the reporting contract; lint clean with `warningsAsErrors`;
installs and launches with zero crashes and no clipboard-related permission.

**Two findings that change the matrix:**
1. `ClipDescription.EXTRA_IS_SENSITIVE` is API 33, so the sensitive row cannot be exercised on
   API 31 at all. The probe logs `NOT_APPLICABLE` rather than a misleading `false`.
2. The read gate is a focus gate, and the keyguard holds input focus, so no button can be pressed.
   Every matrix row is unrun.

**Defect found:** the log line interpolated clipboard content raw, so a newline would have split
one observation across two lines. Now escaped.

Report: `Docs/prototypes/P04-clipboard-android-to-mac.md`.

### P03 — Transport and pairing (protocol layer, 2026-11-09)

Built the two parts of P03 that can be proven without a live peer, and that carry the real risk:

**`shared/protocol/session/Framing.kt`** — length-prefixed framing per ADR-012. The length prefix is
validated before allocation, so a peer announcing 2 GiB is rejected on the header alone. The prefix
is decoded unsigned, because a signed read turns `0xFFFFFFFF` into `-1` and every bound check then
passes. A reader that hits a framing error latches failed rather than resynchronising.

**`ShortAuthenticationCode`** in both Kotlin and Swift — the pairing code from ADR-011. Inputs are
sorted by content so both sides agree without a role, and length-prefixed so `("ab","c")` and
`("a","bc")` cannot collide.

**Verified:** six fixed vectors are asserted in both languages, so the two implementations cannot
drift silently — which matters because a drift would show two different codes on two screens and
reject a valid device.

**Later superseded:** the TLS session, sequenced control envelope, pin stores and pairing
confirmation were built afterwards and exercised on the device — see "P03 — secure session, device
run 2026-11-09" below. This section is kept as the record of the protocol-only milestone.

Report: `Docs/prototypes/P03-transport-and-pairing.md`.

### P01 capture — first verified on the OnePlus (Android 12)

The OnePlus was unlocked and consent granted, so the full pipeline ran for the first time:

**124 frames, 359,092 bytes, 3 keyframes**, negotiated `video/avc` 1280x720, SPS 21 bytes, PPS 8
bytes, foreground service `isForeground=true`, 0 crashes. Frames kept arriving on a still screen,
which is what `KEY_REPEAT_PREVIOUS_FRAME_AFTER` is for.

**Defect found and fixed:** `configure()` failed with `CodecException 0x80001001`
(`OMX_ErrorUndefined`) and the stack named no key. A device-side bisect over the format keys
(`FormatBisect.kt`) isolated the rule: **this encoder requires `KEY_LEVEL` whenever `KEY_PROFILE` is
set**, and also requires `KEY_I_FRAME_INTERVAL`. Profile alone fails; profile plus level succeeds.
The first bisect pass was misleading — it looked like adding `I_FRAME_INTERVAL` was the fix — so the
identical format was run three times to rule out a first-configure quirk before re-basing the
bisect on the known-good format. Level 3.1 is now set alongside Baseline.

This bug is invisible to unit tests and would have blocked the entire mirroring path.

### P04 — VERIFIED on device (2026-11-09)

The full matrix ran. The decisive row is the negative one: with the launcher in front,
`hasPrimaryClip` returned **false** — the focus gate observed, not assumed. Screen off also returns
false.

Three findings that change the product:
- **`EXTRA_IS_SENSITIVE` does not block a read.** A clip written with the flag set was read in full.
  It is a preview-suppression hint, not an access control. DroidBridge must be cautious with it but
  must not claim the OS protects it.
- **There is no OS-side size cap.** A 100 KB clip came back complete, so the 100 KB figure in the
  plan was an assumption. DroidBridge must impose its own cap.
- **Nothing deduplicates.** Reading the same content twice returns it twice, so loop prevention must
  be DroidBridge's own, keyed on content hash and origin ID.

The Android-to-Mac user story wording is now written from observation and recorded in the report.

## S24 Ultra session — 2026-11-09

The target device was attached. Everything below is measured on the device, not reported.

### Device baseline (P00) — complete

**SM-S928B** (`e3q`), Android **17**, API **37**, One UI **9.0**, build
`CP2A.260605.016.S928BXXS6EZJ1`, security patch 2026-10-05, `ro.build.type=user`,
`ro.boot.verifiedbootstate=green`. USB connected, USB debugging on, wireless debugging off. The
owner-reported specs were all confirmed rather than assumed.

### P02 — first contact verified on the target device (picker path)

This was the prototype that could not run on the OnePlus, because `DiscoveryRequest` only exists from
API 37. This first run is superseded by the completion run at the end of this document; the
sustained-duplex, recovery and permission tests were added there.

- `ACCESS_LOCAL_NETWORK` requested and **granted**.
- `DiscoveryRequest` with `FLAG_SHOW_PICKER` → the **system picker appears**
  (`com.google.android.connectivity.resources/.NsdPickerActivity`), titled "Choose a device to
  connect", listing discovered peers.
- Selecting a peer → `onServiceFound` with `host=null port=0`, then `resolveService` →
  `host=192.168.31.109 port=47999`.
- TCP connect from the phone → the Mac app logged `connection ready`.
- **Duplex echo verified**: 26 bytes sent, echoed verbatim, received back.
- `FLAG_USER_APPROVED_ONLY` → found the same peer **with no picker**, after a single approval. That
  is the flag that makes silent reconnect possible.

**Correction to a previous conclusion:** the earlier session recorded that Bonjour and inbound
connections were blocked at the host level by Little Snitch. That was wrong. With no change to Little
Snitch, the firewall, or any VPN, the phone connected to a plain Python listener, to the real
`DroidBridge.app` listener, and completed a duplex echo. The Mac was never blocked. The previous
diagnosis was an inference from a failing test rather than a measurement of the host, and it sent
that session chasing a security control that was not in the way.

**Open question, recorded honestly:** after `pm revoke`, `ACCESS_LOCAL_NETWORK` reads `granted=false`
while discovery, the picker, resolve and approved-only all still work. A fresh install that had never
been approved found nothing. So the picker grant appears to be the real gate and the permission's
enforcement could not be demonstrated on this build. DroidBridge must keep the picker as its consent
mechanism regardless, and must not treat the permission as a security boundary until this is settled.

### P04 — re-run on Android 17

The full matrix re-ran and is **unchanged from Android 12**: focused read works, empty/image/long/
duplicate behave identically, and the unfocused read still returns `hasPrimaryClip=false`. The
sensitive row is now the stronger result it could not be on API 31 — the flag is a real API-33+ extra
here, and the clip was still read in full with `sensitive=true`. **The flag is a preview hint, not an
access control.**

The unfocused row was measured with a new **delayed read** (schedule a read, switch away, read fires
with another app in front) instead of the exported receiver, which is both a better test of the real
product path and does not require weakening the receiver's signature guard.

## P03 — secure session, device run 2026-11-09

Run on SM-S928B (Android 17 / API 37, `CP2A.260605.016`) against MacBookAir10,1 / macOS 27.0.1 over
Wi-Fi, USB connected, USB debugging on, wireless debugging enabled (but not used as the transport).
Full record: `Docs/prototypes/P03-live-handshake-evidence.md` §7 Run P1.

**What passed on the device**

- First pairing showed the same code on both screens (`642 575`, then `614 068` after the fix below),
  with data locked until confirmation; confirming persisted the pin (`pin persisted=true`) and only
  then enabled data.
- Data crossed both ways on the confirmed session (`session.ping` → `FRAME type=ping payload-bytes=12`;
  Mac ping → `received ping:1:hello-from-mac`).
- Stored-pin reconnect authenticated inside the handshake, with no code prompt.
- The 30 s idle deadline closed sessions during the run, observed rather than inferred.
- A restarted Mac with a new identity was refused by the phone: `peer pin mismatch: expected 95c71d5e…,
  got ce3724ac…`.

**Two defects found by doing this on hardware**

1. The Android identity key permitted only SHA-256 digests, so Keystore refused Conscrypt's
   `NONEwithECDSA` CertificateVerify (`InvalidKeyException: Keystore operation failed`) and no client
   certificate was ever sent. Fixed by also permitting `DIGEST_NONE`; the app had to be reinstalled to
   generate a compliant identity. No build or unit test could have caught this.
2. The macOS verify block captured the expected pin when the listener started, so a listener that
   began unpinned kept accepting "first pairings" after a confirmation and then granted data access
   because a pin existed. Fixed by reading the pin at handshake time and by refusing a mismatched pin
   in `connectionReady`. Regression is recorded with two loopback identities.

**Still open for P03**

- Wi-Fi drop/reconnect is **manual**: the drop closes the session on both ends and reconnecting with
  the stored pin needs no re-pairing, but nothing re-establishes the session automatically.
- The listening side learns of a drop only by its idle deadline, and a 30 s deadline drops a session
  a user pauses in. A production session needs a keepalive or a longer deadline.
- Reconnect after a DHCP address change was not tested (the phone returned on the same IP).
- Mac identity is ephemeral (ADR-025), so a Mac restart forces re-pairing; cross-launch known-peer
  sessions cannot be claimed yet.
- On-device tamper/replay/oversize input has no UI; those paths remain loopback/unit-test evidence.
- The acceptance run used `P03Harness --pair` for the Mac side; the GUI app's pairing path is not
  exercised yet.

## P03 — final acceptance run, USB disconnected 2026-11-09

The acceptance run `PHASES_AND_ACCEPTANCE.md` requires, and the first one with no development
transport available.

- USB disconnected, USB debugging off, wireless debugging off, Developer Options off. Verified from
  the Mac: `adb devices` listed nothing and no adb-over-TCP device existed for the whole run.
- Phone UI driven by the owner on the device; Mac side by `P03Harness --pair`. First run where the
  phone was operated by a person rather than by `adb shell input tap`.
- Both screens showed **`996 498`**, compared by the owner. Mac log:
  `verify: pin matched e0369bd5…`, `authenticated=true code=996 498`, then
  `FRAME type=ping seq=1 payload-bytes=12`.
- A TLS 1.3 mutually authenticated session carried application data with no cable and no debugging.

Two things this run exposed, both recorded rather than papered over:

1. The first attempt authenticated and was then closed by the 30 s idle deadline before the ping was
   sent. A user who pauses half a minute loses the session; a production session needs a keepalive or
   a longer deadline.
2. Restarting the harness to get a longer deadline minted a new Mac identity (ADR-025), forcing a
   second re-pair. Persistent Mac identity is a P15 prerequisite, not a P03 defect.

Full record: `Docs/prototypes/P03-live-handshake-evidence.md` §7 Run P3.


## P01/P02 completion run — 2026-11-09 (S24 Ultra)

A dedicated validation pass closed the gap between the earlier first-contact evidence and the
P01/P02 acceptance lists. All sessions below are **SM-S928B, Android 17 / API 37, One UI 9.0,
build `CP2A.260605.016.S928BXXS6EZJ1`**, with USB connected, USB debugging on, wireless debugging
off, Developer Options on. Full reports: `P01-media-projection.md`, `P02-local-networking.md`.

### P01 — validated

- Encoder `c2.qti.avc.encoder`; hardware accelerated; 3120×1440, 2560×1440, 1920×1080 and
  1280×720 all accepted at 30 fps; 2-pixel alignment.
- Output 1280×720, Baseline, level 3.1, VBR; SPS 21 bytes / PPS 8 bytes; 4 Mbit/s requested is a
  ceiling (measured mean 1,392 kbps over a mixed-content 189 s session).
- 189 s session: **12,610 frames / 32,960,629 bytes / 211 keyframes**, 0 crashes, FGS
  `types=0x00000020` and notification id 4101. Capture survives backgrounding.
- Rotation: `onCapturedContentResize` reported 1080×2340 ↔ 2340×1080; `VirtualDisplay.resize()`
  accepted both; frame production continued. Android 17 **forbids a second
  `createVirtualDisplay`** on one `MediaProjection`, so the pipeline rebuild was replaced by
  `resize()`.
- Screen lock fired `MediaProjection.Callback.onStop()` and tore the service down; user stop also
  clean.
- **20/20 start/stop cycles**, 2,005 frames, same PID, 0 crash-buffer entries, service gone after
  the loop. Harness: `scripts/p01-capture-cycles.py`.
- **FLAG_SECURE:** normal white test screen mean luma 159; same screen protected mean luma 0; back
  to normal 159. No bypass attempted.
- **Consent-token replay:** on this build `getMediaProjection` with a used token returns another
  projection bound to the live session instead of throwing; stopping it ends the session. The
  one-shot rule is enforced at `createVirtualDisplay` (second call throws). Product code must never
  replay a token, and must not depend on retrieval throwing.
- Selected-app capture produced 64 frames and fired `onCapturedContentVisibilityChanged(false)` when
  the experiment returned to the foreground.
- Open plan-only items: direct screen-sharing-chip tap, `KEY_LATENCY`, still-screen repeat-frame
  on/off, `flush()` without `start()`. None blocks the P01 acceptance list.

### P02 — validated and accepted, including Mac sleep/wake

- **Picker without permission:** after a reinstall reset `ACCESS_LOCAL_NETWORK` to NOT GRANTED,
  the system picker still appeared and listed `DroidBridge`; selection produced
  `onServiceFound host=null hostAddresses=[] port=0`, and resolve returned
  `192.168.31.109:61169`.
- **60 s sustained duplex:** 198/198 messages, 0 mismatches, 0 errors, 1 connection; RTT
  8.0–327 ms, average 51.6 ms.
- **Real outage:** airplane mode for ~30 s during a 180 s run; 475/474 messages, 0 mismatches,
  1 error, 2 connections; approved-only rediscovery timed out during the outage, retried, found the
  Mac after restore and reconnected with **no picker**.
- **Android process recreation:** new process, approved-only found and connected (10.3 ms first
  RTT). **Android reboot:** new process, found, resolved, connected; 20/20 messages.
- **Inbound:** clean boot without the permission, Mac connect timed out; after grant, bidirectional
  line echo passed. `pm revoke` did not take effect for the running process (3/3 connects
  succeeded); after reboot, inbound was blocked again. `AdvertisingRequest` without the permission
  throws `SecurityException: Missing local network permission`; with it, registration succeeds.
- **`checkPermissionForService()`** returned `SERVICE_PERMISSION_DENIED` both before and after the
  runtime grant, so it is not a reliable grant indicator on this build.
- **Mac sleep/wake — passed (third attempt):** real `pmset` Sleep at 21:41:50 and Wake at 21:42:15;
  the phone disconnected at 21:41:55, rediscovered the approved Mac and reconnected to the same port
  64615 at 21:42:14 with no picker. Final run: 536/535 messages, 0 mismatches, 1 expected lost
  reply, 2 connections; RTT 5.8–430 ms, average 44 ms.
- **Mac restart finding:** both the advertiser restart (new ports 61172/61173) and the cold restart
  (new port 64615) required a fresh picker approval. Approved-only discovery did **not** accept the
  old approval for the new service instance; after one re-approval, approved-only reconnect worked
  again. The product must either use a stable service identity/port that survives a Mac restart or
  present the re-approval as expected UX.
- Router restart, Mac IP change, guest-Wi-Fi isolation and Mac interface change were not available
  in this environment.

### Verification

`JAVA_HOME` set to Zulu 17, `./scripts/verify-all.sh`: 78/78 shared-protocol tests, Android
`:app` + media-projection + discovery + clipboard builds/tests/lint, 37/37 macOS tests, exit 0.
P01 adds 13 unit tests (8 framing + 5 format policy); P02 adds 7 echo-payload tests.


## P05 — Clipboard Mac → Android (completed Android 12 + 17, 2026-11-09)

The OnePlus matrix ran against a real Mac→Android framed TCP harness
(`scripts/p05-clipboard-sender.py`); this is not TLS evidence — P03 owns TLS, and P05 measures the
Android clipboard write path. A separate installable app,
`android/experiments/clipboard-receive/`, receives length-prefixed UTF-8 text in a `dataSync`
foreground service and writes it without logging content.

| Case | Result |
|---|---|
| Foreground 21 B | written, readback hash matched |
| Background 21 B | written while activity gone, readback matched |
| Process recreation | new process wrote and read back |
| Write while another app foreground | P04 read the newly written text (21 B) |
| Unicode | 55 UTF-8 bytes → 47 UTF-16 units, hash matched |
| 100 KiB | 102,400 bytes written and read back |
| Duplicate ×2 | 1 write, 1 ignored duplicate |
| Empty / 102,401 B | rejected on the length header; clipboard unchanged |
| Auto-clear at 5 min 50 s | still present; 15/30/60 min and S24 comparison pending |

Findings: background clipboard **writes** are allowed while reads remain focus-gated (P04);
DroidBridge must enforce the 100 KiB cap and hash over UTF-8 bytes, not `String.length`; duplicate
suppression works at the receiver but end-to-end loop prevention still needs P06/origin IDs; no
content is logged or persisted. P05 is now **completed** on Android 12 and Android 17. The S24 run also showed that a raw local TCP
socket outside the picker path is blocked until `ACCESS_LOCAL_NETWORK` is granted, that Android 17
shows a generic "Copied." confirmation with no content preview, and that the clip was still present
after ~6 minutes. The active-mirroring-FGS integration context is deferred to P08.


## P06 — macOS pasteboard monitoring (partial, 2026-11-09)

The P06 console ran on macOS 27.0.1 (26A434) with the bundled, ad-hoc signed, sandboxed app. It polls
`NSPasteboard.general.changeCount` at 500 ms and reads only on a change.

- Every run started with `accessBehavior=alwaysAllow`; **no access prompt appeared** for polling or
  a real `string(forType:)` read, foreground or background.
- `tccutil reset Pasteboard com.droidbridge.macos` and `tccutil reset All` both reported success,
  but the behavior stayed `alwaysAllow`.
- External `pbcopy` was detected and read; the app's own write was suppressed
  (`selfWrite=true suppressed=true`) so it cannot echo back.
- Logs contain length and SHA-256 prefix only — no clipboard content.
- A configuration defect was found and fixed: `macos/project.yml` had duplicate `settings:` keys, so
  the target's bundle ID/product/Info.plist settings were discarded and the app built as
  `com.droidbridge.DroidBridge`. The blocks are merged; the app now builds as
  `com.droidbridge.macos`.
- P06 is partial: the required deny/ask UX has no evidence because those states could not be
  induced on this OS/signing combination. The core question — can DroidBridge monitor the Mac
  pasteboard automatically? — is answered **yes on this macOS 27.0.1/ad-hoc configuration**.


## P07 — Mac H.264 receiver (partial, 2026-11-09)

A Mac-only P07 path now parses Annex-B, builds `CMVideoFormatDescription` from bare SPS/PPS, turns
access units into 4-byte-length AVCC `CMSampleBuffer`s and enqueues them on
`AVSampleBufferDisplayLayer.sampleBufferRenderer`.

- Fixture: 640×360, 48,029 bytes, 97 NALs, 90 access units; generated by ffmpeg.
- 7 new XCTest cases pass: fixture parse, AVCC lengths, emulation-prevention preservation,
  parameter-set start-code rejection, wrong `nalUnitHeaderLength` rejection, format/sample build,
  duplicate/non-monotonic PTS rejection. macOS test total is now 44/44.
- Live viewer: `LOADED sps=25 pps=5 units=90 preventsCapture=true`, `READY_FOR_DISPLAY true`,
  90 frames at `status=rendering`.
- Flush/recover: `RECOVER flush`, ready false → true, playback restarts at the keyframe, frames
  continue without error.
- Pending: forced backpressure, display latency measurement, screen-recording verification of
  `preventsCapture`, rotation/resolution changes, corrupt-sample failure induction.
