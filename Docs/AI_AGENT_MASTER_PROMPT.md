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

After the research commit, begin Phase 0 and then build exactly one phase at a time. At the end of each phase, update docs, run and record tests, commit and report. Then stop and wait for the owner's input. Never automatically start the next phase. A “continue” response authorizes only the next phase.

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

Do not skip ahead to production until P00–P10 have sufficient evidence and the owner approves scope.

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
- After each phase report, wait for owner input; no automatic phase progression.
- Never force-push or merge to main without permission.

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


## Owner-approved workflow clarification (authoritative; 2026-11-08)

This section supersedes any earlier wording in this file that conflicts with it. The active integration branch is **`development`**. The existing **`master`** branch remains the stable-release branch. Do not rename branches or assume a `main` branch. All ongoing research and implementation commits target `development`; do not merge to `master` without the owner's explicit release approval.

### Two-agent responsibility split

- **Cloud Research Agent:** research current official platform documentation; verify and record technical facts, feasibility, sources, risks and alternatives; maintain research, architecture, decision and permission documentation; prepare prototype instructions and review documentation. It must not implement product features, modify Android/macOS application code, run or claim physical-device tests, or mark device-specific prototypes complete. It commits and pushes its research/documentation work to `development`.
- **Local Development Agent:** owns all implementation, Android/macOS project changes, builds, automated tests, physical-device prototypes and validation, and local progress/test evidence. It pulls the latest `development` before starting and pushes implementation and test evidence to `development`.
- **Coordination:** agents must not work on or push competing changes to `development` simultaneously. Use sequential handoffs: the cloud agent finishes and pushes a scoped research update; the local agent pulls it before implementation. Before a later cloud research update, local work must be committed/pushed and the cloud agent must sync to the latest branch head. Never force-push or overwrite uncommitted work.

### Autonomous phase progression

After completing a phase, the responsible agent must verify its acceptance criteria, run all feasible tests, record unavailable tests honestly, update progress/decisions/changelog/permission records, create a focused commit and push to `development`. It must then **automatically begin the next phase** in the documented sequence without waiting for routine owner approval. Continue phase by phase; do not skip gates or combine phases. Report meaningful milestones and handoffs while continuing.

Stop and request owner input only when a genuine blocker requires it: a hard constraint cannot be met; a prototype fails a feasibility gate; a new or expanded permission, sensitive capability, cost, account entitlement or security/privacy trade-off needs approval; an irreversible/destructive operation is proposed; or a product/architecture choice cannot be resolved from existing requirements and documented evidence. Do not pause merely because a phase ended. Do not silently relax any hard constraint. If blocked, document the evidence and precise decision needed, then wait.

The documented phase sequence and acceptance criteria remain unchanged. Research completion does not imply that physical prototypes have passed. The current next build phase remains Phase 0; physical prototypes remain pending for their scheduled phase. The local agent must not claim device results until it has actually run them on the target phone and recorded USB/debugging state.
