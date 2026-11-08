# Local Development Progress

**Status:** Research stage complete and documented. One platform-independent module exists (`shared/protocol/`, H.264 media framing) and its tests run on a plain JVM; no Android or macOS application code exists yet, no prototype has been run and no physical-device test has been performed.
**Last updated:** 2026-11-08
**Current gate:** Gate A (research) closed on documentation evidence — Phase 0 is the next build step, subject to owner approval.

## Device and environment

- Android device: Samsung Galaxy S24 Ultra.
- Region: India.
- Android: 17 (owner-reported; API level 37 assumed and supported by current Google documentation — **not yet verified on the device**).
- One UI: 9 (owner-reported, not yet verified).
- Mac model / macOS / Xcode: **to be recorded in P00**.
- USB debugging: development-only allowance; final release must not require it.
- Bluetooth: optional and non-core.

## Repository state

- Branch: `development` (created from `master` at the start of this research pass, because the remote had only `master`).
- Content: `AGENTS.md`, `README.md`, `Docs/*`, `.gitignore`, and `shared/protocol/` (a pure Kotlin/JVM library — the only thing allowed in `shared/` right now). No `android/`, no `macos/`.
- Build/test tooling: a Gradle wrapper plus the Kotlin/JVM plugin, for `shared/protocol` only. Nothing Android or macOS can be built in this environment (no Android SDK, no Xcode), so those builds belong on the owner's machines.

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

## Not started

- [ ] Create the Android project shell (`android/`, Kotlin + Compose, Gradle KTS).
- [ ] Create the macOS project shell (`macos/`, Swift + SwiftUI, Xcode project).
- [x] Add `.gitignore` (Gradle, Android, Xcode, IDE, secrets).
- [ ] Add CI/build scripts.
- [ ] Capture exact device build/API details (P00).
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

- None. No build has been produced, no prototype executed, no device attached. Every statement in the research documents is documentation-based.

## USB / debugging state

- Not applicable yet: no device session has occurred in this pass. When one occurs, the report must state: USB connected yes/no, USB debugging enabled yes/no, wireless debugging enabled yes/no.

## Workflow from here

1. Owner reviews the research report and the ADRs (especially ADR-010, the picker-vs-permission choice, and ADR-014, the clipboard degradation).
2. Phase 0 — create the repository structure and both native shells, verify clean builds, add `.gitignore`/CI scripts. No feature code.
3. Then prototypes P00–P13 in the order given in `PROTOTYPE_PLAN.md`, each with a written report and a commit.
4. No production feature work until the prototype gates that affect it have passed.

## Next task

Phase 0 (repository and environment): create `android/`, `macos/`, `shared/` and `scripts/`, add `.gitignore`, and verify both shells build from a clean checkout. This is a build phase and requires the owner's approval after the research report.
