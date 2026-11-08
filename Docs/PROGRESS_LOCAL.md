# Local Development Progress

**Status:** Phase 0 complete. Both native shells exist and build from a clean checkout: `android/` (Kotlin + Compose, 10 Gradle modules) and `macos/` (Swift + SwiftUI, sandboxed, xcodegen-generated). The Android shell installs, launches and stays resumed on the OnePlus Nord CE (Android 12 / API 31) with no crash and no ANR. No feature code exists and no feasibility prototype has been run yet.
**Last updated:** 2026-11-09
**Current gate:** Phase 0 exit criteria met (see `Docs/prototypes/PHASE0-repository-and-environment.md`). Phase 1 (prototypes P00–P15) is next; P00's host/device baseline is done, P01 is the first prototype proper.

## Device and environment

- Android device: Samsung Galaxy S24 Ultra.
- Region: India.
- Android: 17 (owner-reported; API level 37 assumed and supported by current Google documentation — **not yet verified on the device**).
- One UI: 9 (owner-reported, not yet verified).
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
