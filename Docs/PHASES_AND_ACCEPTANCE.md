# Development Phases and Acceptance Criteria

## Gate A — Research completion (before Phase 0 implementation)

Complete the research work first, including the official-source checks and feasibility prototypes listed in `PROTOTYPE_PLAN.md`. Update the research, decisions, permissions and progress documents. Commit the research package to `development` and present the findings to the owner. Then begin Phase 0 unless a specific unresolved decision blocks progress.

Execute the phases below one at a time. At the end of every phase, commit, report and wait for owner input. Approval to continue authorizes only the next phase.

## Phase 0 — Repository and environment
- Create repository structure.
- Create native Android and macOS shells.
- Verify clean builds.
- Add CI/build scripts where practical.
- No production feature code.

Exit: both shells build from a clean checkout. Commit the phase, report results, then wait for owner approval before Phase 1.

## Phase 1 — Feasibility prototypes
- P00–P12 as applicable.
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
- H.264 encode.
- local stream.
- native Mac decode/viewer.
- rotation, stop and reconnect.

Exit: 30-minute stable same-Wi-Fi session; protected content remains protected.

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

Exit: owner approves release.

## Mandatory stop after every phase

When any phase reaches its exit criteria: update the progress record, create a focused Git commit, push when possible, and report the evidence and next phase. Then stop. Do not start the next phase until the owner explicitly approves it. If the owner requests changes, remain on the current phase until those changes are completed and accepted.
