# Development Phases and Acceptance Criteria

## Gate A — Research documentation
**Status:** Complete — 2026-11-08

Official-source research and the documentation pass are recorded in `RESEARCH_AND_FEASIBILITY.md`, `TECHNICAL_DECISIONS.md`, and `PERMISSIONS_MATRIX.md` (R-01–R-31 and ADR-010–ADR-028). This closes the documentation research gate only. No physical-device prototype is implied to have passed.

## Phase 0 — Repository and environment
**Status:** Complete — 2026-11-09
Owner: Local Development Agent.
- Inspect repository, toolchains and actual device/host environment.
- Create native Android and macOS project shells.
- Establish shared module/build structure and scripts/CI where practical.
- Verify clean builds from a clean checkout.
- No production feature implementation.

Exit: both native shells build cleanly; actual tool/device versions are recorded; permission and repository audits are documented; focused commit pushed to `development`.

**Result:** met. `android/` (10 modules) and `macos/` (sandboxed SwiftUI shell) both build from a
clean checkout; 31/31 shared protocol tests and 4/4 macOS tests pass; Android lint is clean with
`warningsAsErrors`; the APK installs and launches on the OnePlus Nord CE (Android 12 / API 31) with
no crash or ANR. Audits in `PERMISSIONS_MATRIX.md` §6. Reports in `Docs/prototypes/`.
**Not done, and not claimed:** the S24 Ultra baseline (device not attached) and a device screenshot
(the phone was PIN-locked).

## Phase 1 — Feasibility prototypes (next)
**Status:** In progress — P00 done; P01 partially verified (encoder measured, capture pending an unlocked device); P02 blocked (needs API 37 hardware, and Bonjour is blocked on this host); P04 implemented, matrix unrun (needs an unlocked device)
Owner: Local Development Agent. Follow `PROTOTYPE_PLAN.md` P00–P15 and its per-prototype order/dependencies.
- Execute each applicable isolated prototype on the required platform/device.
- Record evidence header, exact steps, positive and negative results, logs and limitations.
- Determine GO / CONDITIONAL / NO-GO from measured evidence.
- Cloud agent may prepare/review plans and source research, but cannot execute or mark device prototypes complete.

Exit: every required prototype has an evidence-based report; blocked/untested items are explicitly identified; decisions and permissions are updated; commit pushed.

## Phase 2 — Shared protocol, discovery and pairing
- Android NSD picker/direct LAN strategy.
- macOS Bonjour discovery.
- Secure pairing.
- TLS session.
- reconnect/disconnect.

Exit: trusted Mac and Android exchange authenticated test messages over Wi-Fi with Bluetooth off and USB disconnected.

## Phase 3 — Clipboard MVP
- Mac pasteboard monitoring.
- Android manual/foreground receive.
- Android manual send.
- Mac pasteboard update.
- loop prevention, pause, deduplication, limits.
- no history by default.

Exit: text sync passes tests with documented foreground/background limits.

## Phase 4 — Screen capture and streaming
- MediaProjection lifecycle.
- foreground service.
- surface-fed H.264 encode (ADR-027).
- Annex-B→AVCC framing with SPS/PPS in `mirror.format`.
- local stream.
- native Mac decode/viewer via `AVSampleBufferDisplayLayer` (ADR-028).
- rotation, stop, keyframe recovery and reconnect.

Exit: 30-minute stable same-Wi-Fi session; protected content remains protected; recovery after a decoder flush is demonstrated with a keyframe request rather than resumed P-frames.

## Phase 5 — Remote input decision
- Bluetooth HID and supported APIs experiments.
- no prohibited mechanism.
- document exact support.

Exit: owner accepts GO or NO-GO. If NO-GO, ship view-only mirroring rather than relaxing constraints.

## Phase 6 — Hardening and UX
- permission explanations.
- pairing revocation.
- failure states.
- logs and diagnostics.
- battery/performance improvements.
- accessibility of the app's own UI (not Android Accessibility Service).

Exit: security and regression tests pass.

## Phase 7 — Optional Bluetooth
- only if useful.
- Wi-Fi continues to work independently.
- minimal permissions.

Exit: feature remains optional and tested.

## Phase 8 — Audio/calls research
- separate scope and approval.
- no default dialer role unless owner approves.
- no call recording.

Exit: documented feasibility decision.

## Phase 9 — Release
- clean builds.
- release signing.
- privacy/permission audit.
- user documentation.
- no debugging required.
- release notes and known limitations.

Distribution gates (from R-25–R-28):
- Android `targetSdk` is at or above Play's current floor (API 36 since 2026-08-31, so the chosen 37 satisfies it), and the AAB is signed with the upload key under Play App Signing.
- Play Console declarations are complete for every foreground-service type the manifest declares, each with its description, impact statement, chosen use case and demo video; the `ACCESS_LOCAL_NETWORK` declaration is submitted only if that permission shipped.
- The macOS build is Developer ID-signed with the hardened runtime, a secure timestamp and no `get-task-allow`, has been accepted by notarisation and **stapled**, and launches from a clean Mac with a normal first-run prompt rather than an "Open Anyway" override.
- The shipped macOS entitlements are exactly the audited set (`app-sandbox`, `network.client`, `network.server`, keychain access group) and any embedded provisioning profile is a distribution profile.

Exit: owner approves release.

## Agent ownership and phase progression

- Cloud Research Agent: research, documentation, prototype planning and review of pushed work only. No product implementation, Android/macOS project edits, local builds or physical-device testing.
- Local Development Agent: all implementation, builds, automated tests, prototypes and physical-device evidence.
- Active branch is `development`; stable release branch is `master`. No force-push; no release merge without explicit approval.
- Agents work sequentially and sync the latest branch before starting. Cloud pushes its scoped documentation work before local implementation; local pushes implementation/evidence before cloud review.
- After a phase passes its exit criteria, the responsible agent updates records, commits, pushes, reports and automatically starts the next phase. Do not wait for routine approval, skip phases or combine phases.
- Stop only for a genuine blocker requiring owner input: hard constraint conflict, failed feasibility gate, new/expanded permission or sensitive capability, cost/entitlement/security/privacy trade-off, destructive action, or unresolved product/architecture decision.
- No device-specific prototype is complete without actual device evidence. Every relevant test records USB/debugging state. Final acceptance requires USB disconnected and USB/wireless debugging disabled.
