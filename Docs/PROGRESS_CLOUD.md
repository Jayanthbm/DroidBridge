# Cloud Research and Review Progress

**Status:** Initial platform research/documentation pass complete. Cloud agent is available for scoped documentation maintenance and later review of pushed local implementation/evidence.
**Last updated:** 2026-11-08
**Active branch:** `development`
**Stable-release branch:** `master`
**Current owner:** Cloud Research Agent (documentation/research/review only)

## Responsibilities and boundaries

- Research current official Android, Apple and Samsung documentation.
- Verify platform API availability, restrictions, permissions, entitlements, privacy and distribution rules.
- Maintain research, architecture, decisions, permission analysis, workflow documentation and this status file.
- Prepare prototype procedures, acceptance matrices and test/evidence templates.
- Review local agent commits and evidence after they are pushed.
- Do not implement product features, edit Android/macOS application code, own local builds, run physical-device tests or mark device-specific prototypes complete.

## Completed

- [x] Initial official-source research pass recorded in `Docs/RESEARCH_AND_FEASIBILITY.md`.
- [x] Technical decisions and permission/entitlement matrix updated.
- [x] Prototype plan expanded with explicit test procedures and evidence requirements.
- [x] Shared workflow clarified: cloud research-only; local implementation and device-test owner.
- [x] Active branch policy clarified: `development` integration, `master` stable release.
- [x] Autonomous phase progression and genuine-blocker stop conditions documented.
- [x] Cloud/local sequential handoff policy documented.

## Current cloud tasks

- [x] Reconcile stale workflow instructions in agent prompts, README, phase plan and progress docs.
- [x] Add cloud-specific progress tracking.
- [x] Add a prioritized cloud research backlog with dependencies on local evidence.
- [ ] Verify all edited files after the final commit and scan for contradictory legacy workflow language.
- [ ] Review Phase 0 commit after local agent pushes it.
- [ ] Review prototype reports and identify missing evidence, source changes or decision updates.
- [ ] Refresh volatile platform/distribution research before release.

## Waiting for local evidence

The following cannot be closed by cloud research alone:
- P00: actual Mac toolchain and S24 Ultra Android/API/One UI build baseline.
- P01: MediaProjection consent, lifecycle, capture and encoder behaviour on the target phone.
- P02: Android 17 NSD picker grant and full-duplex LAN session on target firmware.
- P03/P13: pairing, TLS and pinned-identity implementation/prototype evidence.
- P04–P06: real clipboard foreground and macOS pasteboard permission behaviour.
- P07/P08: decoder, transport, latency, recovery and sustained streaming measurements.
- P09/P10: remote-input NO-GO confirmation and protected-screen behaviour.
- P11: macOS local-network privacy and Bonjour behaviour under the actual signed build.
- P14: background session, Doze, reboot and reconnect evidence.
- P15: sandbox, signing, notarisation and keychain identity behaviour.

No physical-device prototype is marked complete by this file.

## Handoff protocol

1. Sync latest `development` before any cloud change.
2. Confirm local agent has pushed all current implementation/test work before cloud review.
3. Make a scoped documentation/review change only; commit and push.
4. Tell local agent which commit to pull before resuming implementation.
5. Do not overlap pushes or overwrite uncommitted work.

## Latest cloud handoff

Initial cleanup commit series: see Git history on `development`. The final commit hash is recorded in the handoff message; verify the branch head before acting.
