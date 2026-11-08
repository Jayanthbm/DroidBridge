# Development Phases and Acceptance Criteria

## Gate A — Research completion (before Phase 0 implementation)

Complete the research work first, including the official-source checks and feasibility prototypes listed in `PROTOTYPE_PLAN.md`. Update the research, decisions, permissions and progress documents. Commit the research package to `development` and present the findings to the owner. Then begin Phase 0 unless a specific unresolved decision blocks progress.

**Gate A status, 2026-11-08:** the official-source research and documentation package is complete and committed to `development` (`RESEARCH_AND_FEASIBILITY.md` R-01–R-31, ADR-010–ADR-028). The feasibility **prototypes** are not done — they require the target device and Mac, and they run as Phase 1. No prototype or device evidence exists yet. Gate A therefore splits into A1 (research documentation — done) and A2 (prototype evidence — pending, Phase 1), and Phase 0 may proceed once the owner has the research report.

Execute the phases below one at a time. At the end of every phase, commit, report and wait for owner input. Approval to continue authorizes only the next phase.

## Phase 0 — Repository and environment
- Create repository structure.
- Create native Android and macOS shells.
- Verify clean builds.
- Add CI/build scripts where practical.
- No production feature code.

Exit: both shells build from a clean checkout. Commit the phase, report results, then wait for owner approval before Phase 1.

## Phase 1 — Feasibility prototypes
- P00–P15 as applicable.
- Research and document all hard constraints.
- Decide which features are GO, CONDITIONAL or NO-GO.

Exit: research report and dedicated research commit are complete; proceed to Phase 0 unless a specific unresolved decision blocks progress.

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

## Mandatory stop after every phase

When any phase reaches its exit criteria: update the progress record, create a focused Git commit, push when possible, and report the evidence and next phase. Then stop. Do not start the next phase until the owner explicitly approves it. If the owner requests changes, remain on the current phase until those changes are completed and accepted.


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
