# Local Development Progress

**Status:** Phase 0 complete. Phase 1 in progress: P00, P01, P02 and P04 verified; P03 implemented and
verified on loopback plus one physical S24 Ultra pairing run, with final USB-disconnected acceptance
outstanding.
**Last updated:** 2026-11-09
**Current gate:** Phase 1 (prototypes P00–P15). P00 baseline complete for both devices. P01 and P04
verified on both the OnePlus (Android 12) and the S24 Ultra (Android 17). **P02 is verified on the
target device** — the picker path works. **P03 now has a physical pairing run on the S24 Ultra**:
matching codes on both screens, duplex data on a confirmed session, authenticated reconnect with
stored pins, and a changed Mac identity correctly refused.

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
- [ ] Capture S24 Ultra build/API details (P00 device half) — needs the target device attached.
- [ ] Run P01 MediaProjection prototype.
- [ ] Run P02 Android 17 LAN strategy prototype (picker vs runtime permission).
- [ ] Run P03 pairing/transport prototype.
- [ ] Run P04/P05 clipboard prototypes.
- [ ] Run P06 macOS pasteboard prompt prototype.
- [ ] Run P07/P08 video receiver and end-to-end stream prototypes.
- [ ] Run P09 remote-input confirmation experiment.
- [ ] Run P10 secure-screen/screenshare-protection tests.
- [ ] Run P11 macOS local-network permission tests.
- [ ] Run P13 pinned TLS/CT prototype.
- [ ] Run P14 background-session persistence prototype (screen-off, Doze, overnight, reboot).
- [ ] Run P15 sandboxed, signed and notarised distribution prototype (entitlements, TCC, keychain after re-signing).
- [ ] Confirm Apple Developer Program membership and Account Holder access for the Developer ID certificate.
- [ ] Verify the owner-reported Android 17 / One UI 9 version on the device.

## Current blockers

- **Full remote input is NO-GO** under the current constraints (research conclusion, not a device test): no public injection API, Knox is signature/managed-device gated, macOS cannot publish a HID peripheral.
- **Automatic background clipboard sync is not a release claim.** Android requires focus/IME for reads; macOS 15.4+ can prompt or deny programmatic pasteboard access.
- **Android 17 LAN path must be proven on the target firmware.** The design depends on the NSD picker granting a usable duplex session with the Mac as the listener (U1). If P02 disproves it, `ACCESS_LOCAL_NETWORK` becomes mandatory and the owner must accept that permission.
- **Mac-as-Bluetooth-HID-peripheral is not proven and is expected to fail** (`CBError.uuidNotAllowed`); P09 exists to record the evidence.
- **Background clipboard sync is best-effort, not guaranteed.** The `connectedDevice` foreground service keeps the session alive in normal use, but Doze suspends network access device-wide, so the design is reconnect-on-resume rather than an always-on promise. Whether a socket survives a full night is unproven (U11).
- **The macOS app cannot be released without a paid Apple Developer Program membership.** Notarisation requires a Developer ID certificate, only the Account Holder can create one, and an unnotarised app is blocked by Gatekeeper on current macOS (R-27). This is an external dependency on the owner, not a technical uncertainty, and it blocks Phase 9 rather than Phase 0.

## Tests and evidence

- **2026-11-09 (Phase 0):** 31/31 shared protocol tests (JVM); Android `assembleDebug`,
  `testDebugUnitTest`, `lintDebug` all pass; macOS Debug + Release builds and 4/4 unit tests pass;
  clean-checkout rebuild of all three passes; APK installed and launched on OnePlus Nord CE
  (Android 12 / API 31), `ResumedActivity` held, 0 crash entries, 0 ANRs.
- **Not captured:** a device screenshot. The phone was PIN-locked, so `screencap` produced empty
  output and `uiautomator` dumped the keyguard. Unlocking needs the owner's PIN.
- No feasibility prototype has been executed. Every statement in the research documents remains
  documentation-based.

## USB / debugging state

- **Phase 0 device session (2026-11-09):** USB connected **yes**; USB debugging enabled **yes**;
  wireless debugging enabled **no**. Development session only — nothing in the product requires
  USB or debugging at runtime, and no runtime behaviour was tested with USB disconnected because no
  runtime behaviour exists yet.

## Workflow from here

1. Phase 0 is complete and pushed.
2. Phase 1 — begin with P01 (Android MediaProjection capture) on the OnePlus. P00's device half
   (S24 Ultra baseline) stays pending until that device is attached.
3. Each prototype gets a written evidence report under `Docs/prototypes/` and a focused commit.
4. Do not begin dependent production features until their prototype gates pass. Stop only for
   genuine blockers or owner decisions listed in `AGENTS.md`.

## Next task

Phase 1, prototype **P01 — Android MediaProjection capture** on the OnePlus Nord CE: consent UI,
a `mediaProjection` foreground service, a `VirtualDisplay`, and a surface-fed `MediaCodec` H.264
encoder, with the framing converted by `shared/protocol` and verified against the real encoder
output. Isolated experiment module, no production feature code.

Two things the owner can unblock that are not blockers for P01:
- Unlock the phone during a session so a device screenshot can be captured.
- Attach the S24 Ultra when available, to complete the P00 device baseline.


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

### P01 capture — VERIFIED on device (2026-11-09)

The device was unlocked and consent granted, so the full pipeline ran for the first time:

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

### P02 — VERIFIED on the target device

This was the prototype that could not run on the OnePlus, because `DiscoveryRequest` only exists from
API 37.

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

- Wi-Fi drop/reconnect mid-session (reconnection was only a close-and-redial).
- Mac identity is ephemeral (ADR-025), so a Mac restart forces re-pairing; cross-launch known-peer
  sessions cannot be claimed yet.
- Human code comparison was simulated by reading both UIs; no person compared two screens.
- On-device tamper/replay/oversize input has no UI; those paths remain loopback/unit-test evidence.
- Final acceptance must repeat the run with USB disconnected and both debugging modes disabled.
- The UI still allows "reject" after a session is confirmed; it closes the socket but leaves the pin.
