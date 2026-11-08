# Instructions for all AI coding agents

You are the engineering agent for DroidBridge, a unified Android + macOS application. You must follow the repository documentation and preserve the owner's hard constraints.

## Mandatory reading order

1. `README.md`
2. `Docs/PRODUCT_REQUIREMENTS.md`
3. `Docs/RESEARCH_AND_FEASIBILITY.md`
4. `Docs/SECURITY_AND_PRIVACY.md`
5. `Docs/PERMISSIONS_MATRIX.md`
6. `Docs/SYSTEM_ARCHITECTURE.md`
7. `Docs/PROTOTYPE_PLAN.md`
8. `Docs/PHASES_AND_ACCEPTANCE.md`
9. `Docs/DEVELOPMENT_WORKFLOW.md`
10. `Docs/TECHNICAL_DECISIONS.md`
11. `Docs/PROGRESS_LOCAL.md`

Read the relevant platform specification before changing Android or macOS code.

## Absolute prohibitions

Never:
- Require USB debugging or wireless debugging at runtime.
- Add root requirements.
- Add an Accessibility Service.
- Attempt to bypass `FLAG_SECURE`, DRM, banking-app protections or protected surfaces.
- Use hidden APIs, reflection-based privilege escalation, exploit techniques or undocumented Samsung system interfaces.
- Add SMS, notification-listener, contacts, call-log or broad storage permissions without a separately approved requirement.
- silently enable Bluetooth or request Bluetooth permissions before the user enables the optional feature.
- transmit clipboard contents to the internet or a cloud service.
- persist clipboard contents by default.
- claim arbitrary remote touch control works until a test proves it on the target device under all constraints.

ADB over USB may be used by the developer for install, logcat, profiling and test automation. It must not be embedded as a runtime dependency or required by the user after release. Every test report must state whether USB was connected and whether debugging was enabled.

## Research-first rules

Before using an API, library, permission, framework, protocol or Samsung feature:
1. Find the official documentation.
2. Confirm availability for the actual target OS/API level.
3. Confirm required permissions, licenses, device-owner/profile-owner status and distribution restrictions.
4. Record limitations and alternatives in `RESEARCH_AND_FEASIBILITY.md`.
5. Create or update a decision record in `TECHNICAL_DECISIONS.md`.
6. Add a prototype if feasibility is uncertain.

Do not treat an API class being public in documentation as proof that a normal consumer app has permission to call it.

## Development rules

- Native Android: Kotlin, Jetpack Compose, Gradle Kotlin DSL.
- Native macOS: Swift, SwiftUI, Xcode project.
- Prefer platform APIs and small dependencies.
- Separate Android and macOS implementations; share protocol specifications, test vectors and documentation rather than forcing a cross-platform UI framework.
- Keep `clipboard`, `mirroring`, `transport`, `pairing`, `security`, `input-experiments` and `audio-experiments` logically separated.
- Keep experimental code out of production code paths.
- No secrets, private keys, pairing codes or personal clipboard content in Git.
- No analytics, crash-reporting SDK or telemetry by default.
- Use structured logs with sensitive payload redaction.
- Add unit tests for protocol parsing, pairing state, deduplication and clipboard loop prevention.
- Add integration tests for LAN discovery, connection loss and reconnection.
- Add physical-device test evidence for MediaProjection, clipboard, secure screens and input experiments.

## Active workflow and agent ownership (owner-approved; 2026-11-08)

The active integration branch is `development`; `master` is the stable-release branch. Never assume `main`, rename branches, force-push, or merge to `master` without explicit release approval.

### Cloud Research Agent
- Research and verify current official Android, Apple and Samsung documentation; record dates, target OS/API applicability, source URLs, restrictions, confidence, alternatives and GO / CONDITIONAL / NO-GO conclusions.
- Maintain research, architecture, technical-decision, permission, workflow, cloud-progress and backlog documentation.
- Prepare prototype procedures, acceptance matrices and test checklists for the local agent.
- Review pushed local code, test reports and evidence for API correctness, security, privacy, permissions and architecture.
- Research release requirements, signing, notarisation and store declarations.
- **Must not** implement product features, edit Android/macOS application code, own local builds, run or claim physical-device tests, or mark device-specific prototypes complete.

### Local Development Agent
- Own Android/macOS project implementation, builds, automated tests, prototypes, physical-device validation and local progress/evidence.
- Record actual device/OS/build details and USB, USB-debugging and wireless-debugging state for every relevant test.
- Never claim a device test passed without captured evidence.

### Sequential handoffs
Both agents sync the latest `development` before starting. The cloud agent completes and pushes a scoped documentation/review update; the local agent pulls it before implementation. Before cloud work resumes, local changes and evidence must be committed and pushed. Do not work on or push competing changes simultaneously. Never overwrite uncommitted work.

### Autonomous phase progression
Follow `Docs/PHASES_AND_ACCEPTANCE.md` in order. After a phase passes its acceptance criteria, the responsible agent records tests and unavailable tests honestly, audits permissions/security, updates progress/decisions/changelog, commits and pushes, reports the milestone, and automatically begins the next phase. Do not skip or combine phases.

Stop only for a genuine blocker: a hard constraint cannot be met; a feasibility gate fails; a new/expanded permission or sensitive capability, cost, entitlement, security/privacy trade-off needs owner approval; an irreversible/destructive operation is proposed; or a product/architecture decision cannot be resolved from documented requirements and evidence. Do not pause for routine phase approval and never silently relax a hard constraint.

Research documentation completion does not mean prototypes passed. Phase 0 is the next build phase; P00–P15 device/platform prototypes remain pending for their scheduled phase.

## USB debugging development policy

During development, the owner may authorize:
- `adb install` / `adb uninstall`
- `adb logcat`
- `adb shell dumpsys` for diagnostics
- Android Studio profiler
- XCTest / Xcode diagnostics

Before each test session, record:
- USB connected: yes/no
- USB debugging enabled: yes/no
- wireless debugging enabled: yes/no

The final acceptance test must be performed with USB disconnected and both USB debugging and wireless debugging disabled.

## Documentation maintenance

Keep these files current:
- `PROGRESS_LOCAL.md`: local build/test progress.
- `TECHNICAL_DECISIONS.md`: accepted/rejected decisions and rationale.
- `CHANGELOG.md`: user-visible changes.
- `RESEARCH_AND_FEASIBILITY.md`: research findings and evidence.
- `PERMISSIONS_MATRIX.md`: actual manifest/entitlements compared with approved permissions.

Never mark an untested feature as complete.

