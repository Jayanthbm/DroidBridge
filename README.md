# DroidBridge

**Working name:** DroidBridge  
**Product:** A local-network Android companion for macOS  
**Platforms:** Android + macOS  
**Initial reference device:** Samsung Galaxy S24 Ultra, India region, Android 17 / One UI 9 (as reported by the project owner)  
**Connectivity:** Wi-Fi first; Bluetooth optional  
**Cloud:** None

DroidBridge combines two independently useful features in one product:

1. **Clipboard Sync** — transfer text between Android and macOS.
2. **Screen Mirroring** — stream the Android display to macOS, respecting Android's screen-capture protections.

Remote input, audio and phone-call integration are research tracks. They must not be advertised as supported until a prototype proves that they work with public, supported APIs and without the prohibited privileges.

## Non-negotiable constraints

- No USB debugging or wireless debugging as a runtime requirement.
- No root.
- No Accessibility Service.
- No cloud relay or cloud storage.
- Same local Wi-Fi network for the initial release.
- Bluetooth is optional and must never be required for core Wi-Fi functionality.
- Respect `FLAG_SECURE`, DRM restrictions, protected surfaces and user screen-capture consent.
- No bypasses for banking apps, password managers, payment apps or media protections.
- The app must not require default-dialer status or SMS access for the initial release.
- USB/ADB may be used by the developer/agent for installation, logcat, debugging and test automation during development only. The final app must work after USB is disconnected and debugging is disabled.

## Important feasibility warning

A normal third-party Android app can request screen capture with MediaProjection, but MediaProjection does not grant arbitrary touch injection into other apps. With the project's prohibition on Accessibility, ADB, root and privileged Samsung APIs, full arbitrary remote touch control is **NO-GO as of the 2026-11-08 research pass**: no public injection API exists, Knox `RemoteInjection` requires a signature permission plus managed-device or MDM-issued authorisation, and macOS cannot publish a Bluetooth HID peripheral. Mirroring therefore ships view-only. See `Docs/RESEARCH_AND_FEASIBILITY.md` §2.

Clipboard behaviour also has limits on both sides: Android 10+ restricts clipboard reads from background apps (focus or default-IME required), and macOS 15.4+ can prompt for or deny programmatic pasteboard access. Two-way automatic background sync is not a release claim; a foreground/manual workflow is the safe baseline.

## Repository layout

```text
DroidBridge/
├── README.md
├── AGENTS.md
├── Docs/
│   ├── PRODUCT_REQUIREMENTS.md
│   ├── RESEARCH_AND_FEASIBILITY.md
│   ├── SYSTEM_ARCHITECTURE.md
│   ├── ANDROID_APP_SPEC.md
│   ├── MACOS_APP_SPEC.md
│   ├── NETWORK_PROTOCOL.md
│   ├── SECURITY_AND_PRIVACY.md
│   ├── PERMISSIONS_MATRIX.md
│   ├── PROTOTYPE_PLAN.md
│   ├── TEST_PLAN.md
│   ├── DEVELOPMENT_WORKFLOW.md
│   ├── PHASES_AND_ACCEPTANCE.md
│   ├── TECHNICAL_DECISIONS.md
│   ├── PROGRESS_LOCAL.md
│   ├── CHANGELOG.md
│   ├── RELEASE_CHECKLIST.md
│   └── AI_AGENT_MASTER_PROMPT.md
└── .gitignore
```

## Getting started and mandatory agent workflow

1. Read `AGENTS.md` and the core documents in `Docs/`.
2. Complete the full research/feasibility stage first; do not start production coding.
3. Record official sources, positive and negative findings, decisions, permission impacts and prototype evidence in Git-tracked docs.
4. Commit the completed research package and present the report, then begin Phase 0 unless a specific blocker requires owner input.
5. Build one phase at a time. Commit and report each completed phase, then stop and wait for the owner's input before starting the next phase.
6. Use `Docs/AI_AGENT_RESUME_START_PROMPT.md` to start or resume an agent session.
7. Keep prototype code isolated from production modules and update progress/decision records after every phase.

## Research source policy

Use official Android Developers, Apple Developer and Samsung Knox documentation first. Use issue trackers and reputable engineering discussions for implementation pitfalls, but label them as secondary evidence. Record the URL, date checked, API level, relevant limitation and the conclusion. Re-check volatile documentation before implementation.


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
