# AI Agent Master Build Prompt — DroidBridge

You are the lead engineer responsible for building DroidBridge, a unified native Android + macOS application.

## First action: research only; do not code yet

Read:
- `AGENTS.md`
- all files in `Docs/`
- `README.md`

Then inspect the repository and report:
1. What the product is.
2. Hard constraints.
3. Confirmed technical capabilities.
4. Unresolved feasibility risks.
5. Required tools and environment.
6. Proposed prototype order.
7. Any contradictions or missing owner decisions.

Do not generate production application code during the research stage. Complete the research checklist, update the documentation, and create a dedicated research commit on `development`. Present the findings, proposed MVP scope and commit hash. Then start Phase 0 unless a specific unresolved issue requires the owner to decide.

After the research documentation is committed, the Local Development Agent begins Phase 0. Follow `PHASES_AND_ACCEPTANCE.md` in order. At each phase gate, update docs, run and record feasible tests, audit permissions/security, commit and push, report, then automatically begin the next phase. Stop only for a genuine blocker requiring owner input.

## Product target

- Android: Samsung Galaxy S24 Ultra, India region, Android 17 / One UI 9 (owner-reported).
- macOS: owner's Mac; inspect actual hardware and OS.
- Android native: Kotlin + Jetpack Compose.
- macOS native: Swift + SwiftUI.
- One Git repository.
- Wi-Fi first, Bluetooth optional.
- No cloud.

## Hard constraints

- No USB debugging or wireless debugging at runtime.
- No root.
- No Accessibility Service.
- No privileged Knox dependency unless owner separately approves after Samsung confirms eligibility; default assumption is unavailable.
- Respect banking/DRM secure content and FLAG_SECURE.
- No hidden API or security bypass.
- Clipboard data stays local and is not persisted by default.
- Bluetooth is optional.
- Same Wi-Fi only for initial release.

## Development USB allowance

The developer may use USB/ADB for installing builds, logcat, profiling and debugging during development. This is not a product feature. Record the state for tests. The final acceptance test must pass with USB disconnected and both debugging modes disabled.

## Research

Use current official docs. Specifically verify:
- Android 17/API 37 local network protection and `ACCESS_LOCAL_NETWORK`.
- NSD system service picker (`FLAG_SHOW_PICKER`).
- MediaProjection consent and foreground-service rules.
- Android clipboard focus/default-IME restrictions.
- FLAG_SECURE and DRM capture rules.
- Samsung Knox remote-control authorization and Android 17 availability.
- Android BluetoothHidDevice directionality.
- macOS CoreBluetooth/CoreHID capabilities.
- NSPasteboard changeCount.
- macOS local network privacy and Bonjour.
- audio playback capture and Telecom limitations.

## Build strategy

P00 environment baseline.
P01 MediaProjection capture.
P02 Android 17 LAN discovery/permission.
P03 secure local pairing.
P04 Android-to-Mac clipboard.
P05 Mac-to-Android clipboard.
P06 Mac pasteboard monitoring.
P07 Mac video receiver.
P08 end-to-end mirroring.
P09 remote input feasibility.
P10 secure screen tests.
P11 Bluetooth optional.
P12 audio/calls research.

Do not skip ahead to production until the required prototypes have sufficient evidence and their documented gates pass. Routine phase approval is not required; stop only for a genuine blocker or owner decision described in `AGENTS.md`.

## Agent behaviour

- Work in small phases.
- Keep prototypes isolated.
- Build and test both apps where required.
- Never claim physical-device success without actual test evidence.
- Ask before adding any permission outside the matrix.
- Stop if a hard requirement is impossible.
- Do not quietly relax constraints.
- Update progress, decisions and changelog.
- Commit the completed research package before build approval.
- Commit each completed build phase separately.
- Automatically progress to the next documented phase after its acceptance gate passes; stop only for genuine blockers.
- Never force-push or merge to `master` without explicit release approval.

## Final deliverable

Produce:
- Android APK for owner testing.
- macOS app build.
- source code.
- complete docs.
- test evidence.
- permission audit.
- known limitations.
- installation and pairing guide.

The released product must not need USB debugging or wireless debugging.


## Agent role reminder

Cloud agent is research/documentation/review-only; it does not edit Android/macOS app code, build, or perform device tests. Local agent owns implementation, builds, automated tests and physical-device evidence. Use sequential handoffs on `development`; `master` is stable release. The authoritative rules are in `AGENTS.md` and `Docs/DEVELOPMENT_WORKFLOW.md`.
