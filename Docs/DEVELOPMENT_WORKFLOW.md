# AI Agent Development Workflow

## Repository and branch policy
- One repository; active integration branch: `development`.
- Stable-release branch: `master`. Do not assume or create `main`.
- Both agents sync the latest `development` before starting.
- Work sequentially; never push competing changes or overwrite uncommitted work.
- Never force-push or merge to `master` without explicit release approval.
- Keep commits small and scoped; never commit build artifacts, secrets, signing keys, local SDK paths or private logs.

## 1. Cloud Research Agent — documentation and review only
The cloud agent may:
- Verify current official Android, Apple and Samsung platform documentation, API compatibility, restrictions, permissions, entitlements and distribution rules.
- Research unresolved feasibility questions and document positive/negative findings, alternatives, confidence and source URLs.
- Maintain research, architecture, decision, permission, workflow and cloud-progress documents.
- Prepare prototype procedures, test plans, acceptance matrices and evidence templates.
- Review local agent commits, code, test output and evidence for security, privacy, API correctness and architecture.
- Research signing, notarisation, Play declarations and release packaging.

The cloud agent must not:
- Implement product features or modify Android/macOS application source/project code.
- Own local builds or automated implementation work.
- Run or claim physical-device tests, or mark device-specific prototypes complete.
- Change hard constraints, permission policy or product scope without owner approval.

## 2. Local Development Agent — implementation and validation
The local agent owns:
- Android Kotlin/Compose and macOS Swift/SwiftUI implementation.
- Native project shells, builds, automated tests and CI/build scripts.
- All P00–P15 prototypes and physical-device validation.
- ADB/logcat during development where appropriate.
- Actual test evidence and `Docs/PROGRESS_LOCAL.md`.
- Permission/entitlement audits against `Docs/PERMISSIONS_MATRIX.md`.

Every relevant device test report records device/build, USB connected, USB debugging enabled and wireless debugging enabled. Final acceptance must work with USB disconnected and both debugging modes disabled.

## 3. Sequential handoff protocol
1. Before starting, inspect status/branch/latest commit and sync `development`.
2. Cloud completes one scoped research/documentation/review batch, commits and pushes it.
3. Local pulls the latest branch before implementing or testing.
4. Local commits and pushes implementation plus honest test evidence.
5. Cloud reviews only after local changes are pushed; cloud syncs latest branch first.
6. Never have both agents write/push to the shared branch simultaneously.

## 4. Research-first requirements
Before using an API, library, permission, framework or platform capability:
1. Check current official documentation.
2. Verify target OS/API availability, permissions, roles, entitlements and distribution restrictions.
3. Record sources, date checked, evidence, confidence, limitations and alternatives in `RESEARCH_AND_FEASIBILITY.md`.
4. Update `TECHNICAL_DECISIONS.md` and `PERMISSIONS_MATRIX.md` as applicable.
5. Add or refine an isolated prototype when feasibility is uncertain.
6. Never equate documented API availability with consumer-app eligibility.

## 5. Phase execution and autonomous progression
- Follow `Docs/PHASES_AND_ACCEPTANCE.md` in order, one phase at a time.
- Complete the current phase's scope, acceptance criteria, feasible tests, security/permission audit and documentation.
- Record tests not run and why; do not fabricate results.
- Create a focused commit and push to `development`.
- Report the outcome and automatically begin the next phase in sequence. Routine owner approval is not required between phases.
- Stop and ask the owner only for a genuine blocker: hard-constraint conflict, failed feasibility gate, new/expanded permission or sensitive capability, cost/entitlement/security/privacy trade-off, destructive operation, or unresolved product/architecture decision.
- Do not skip/combine phases or silently relax requirements.

## 6. Git and documentation hygiene
Keep these current as applicable:
- `Docs/PROGRESS_LOCAL.md`: local implementation/build/device evidence.
- `Docs/PROGRESS_CLOUD.md`: cloud research/documentation/review status.
- `Docs/CLOUD_RESEARCH_BACKLOG.md`: queued research and dependencies on local evidence.
- `Docs/TECHNICAL_DECISIONS.md`: accepted/rejected decisions and rationale.
- `Docs/CHANGELOG.md`: user-visible changes.
- `Docs/RESEARCH_AND_FEASIBILITY.md`: sourced research findings.
- `Docs/PERMISSIONS_MATRIX.md`: actual manifest/entitlement audit.
- `Docs/PHASES_AND_ACCEPTANCE.md`: phase sequence and exit criteria.

Research and documentation work must be committed. Use descriptive commit messages. Never mark an untested feature complete.

## 7. Current handoff
The research/documentation pass is complete. Phase 0 is the next local build phase. The cloud agent's immediate remaining work is documentation consistency, maintaining its backlog/status, and later reviewing local commits. Physical prototypes remain pending and are owned by the local agent.
