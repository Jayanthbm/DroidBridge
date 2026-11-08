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
