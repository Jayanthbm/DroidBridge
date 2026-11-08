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

## Mandatory research-first, commit, and phase-by-phase owner-checkpoint workflow

The project must follow this exact sequence. Do not combine or reorder the stages.

### Stage A — Complete research before implementation

1. Start with a repository and environment inspection; do not create feature code.
2. Research every relevant feasibility question and every high-risk platform/API dependency in the project docs. Prioritise current official Android, Apple and Samsung sources. Use secondary sources only to identify pitfalls, clearly labelled as secondary.
3. Research both positive and negative findings. Verify API availability for the actual target OS/API levels, permission prompts, foreground/background restrictions, entitlements, distribution rules, device-role requirements and Samsung-specific limitations.
4. Run only small, isolated research probes if needed; do not build production features during this stage.
5. Update `RESEARCH_AND_FEASIBILITY.md`, `TECHNICAL_DECISIONS.md`, `PERMISSIONS_MATRIX.md`, and `PROGRESS_LOCAL.md`. Record source URLs, date checked, target OS/API, evidence, confidence, limitations, and GO / CONDITIONAL / NO-GO status.
6. When the research checklist is complete, create a dedicated research commit (or a small set of clearly scoped research commits) on `development`. Research-only work must be committed even when no application code changed. Push when a remote is configured and access is available.
7. Present a concise research report, the commit hash, confirmed capabilities, unresolved risks, and recommended build scope to the owner. Then begin the first build phase (Phase 0) unless a specific unresolved issue requires an owner decision. Do not treat this as approval to bypass any later phase gate.

### Stage B — Build one phase at a time

1. After research is committed, execute only the next phase listed in `PHASES_AND_ACCEPTANCE.md` (starting with Phase 0), unless a blocker requires owner input.
2. Finish that phase completely: implement its scoped work, run relevant builds/tests, inspect permission/security impact, update docs/progress/changelog, and create a descriptive Git commit.
3. Report the phase result, commit hash, changed files, tests and evidence, permission changes, limitations, and the exact next phase.
4. **Stop and wait for the owner's input after every completed phase.** Do not automatically start, partially implement, or pre-emptively prepare the next phase while waiting.
5. Resume only after the owner responds. Their response may approve the next phase, request changes, or change scope. Apply that instruction before proceeding.
6. If a phase is blocked, fails acceptance, or requires a product/permission/security decision, stop and ask the owner instead of moving on.

### Git documentation policy

- All completed project work belongs in Git: research, source links, feasibility findings, architecture decisions, permission/security analysis, prototype evidence, test results, implementation, bug fixes, progress and changelog updates.
- Never leave completed research or decisions only in chat, local notes, or an untracked file.
- Commit research separately from implementation. Commit the completed research package before beginning Phase 0. Owner approval is required at the end of each completed phase before the next phase begins.
- Commit each completed build phase separately. Do not accumulate several phases into one large commit.
- Use descriptive messages such as `docs(research): verify Android 17 LAN permission model`, `docs(research): record Samsung remote input feasibility`, `test(android): validate MediaProjection consent flow`, or `feat(macos): add Bonjour discovery`.
- Push commits to `origin/development` when configured and available. Never force-push, commit secrets/private user data, or merge to `main` without explicit owner approval.

## Phase gates

A phase is complete only when:
- Its acceptance criteria pass.
- Tests and build results are recorded.
- Permission and security impact is documented.
- Known limitations are recorded.
- The progress file is updated.
- A Git commit is created with a descriptive message.
- The owner has received the phase report and the agent is waiting for explicit approval before beginning the next phase.

If a hard requirement is technically impossible with supported APIs, stop and report the evidence. Do not silently relax the requirement. Offer choices to the owner.

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
