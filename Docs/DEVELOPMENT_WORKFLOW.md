# AI Agent Development Workflow

## 1. Repository strategy

- One repository.
- One primary development branch named `development`.
- `main` reserved for stable releases.
- Do not run local and cloud agents in parallel on the same branch.
- Prefer small, reviewable commits.
- Never commit build artifacts, signing keys, local SDK paths or private logs.

## 2. Mandatory execution order: research, then build with owner checkpoints

This is a gated workflow. The agent must not jump directly into coding.

### Stage A — Research only

1. Read `AGENTS.md`, `README.md`, and all relevant documents in `Docs/`.
2. Inspect Git status, branch, repository structure and the available development environment.
3. Complete the project-wide research checklist before production implementation. Cover Android 17/API 37 LAN permission and NSD, MediaProjection, clipboard access, `FLAG_SECURE`/DRM, Samsung/Knox remote input eligibility, Bluetooth HID, macOS Bonjour/local-network privacy, pasteboard APIs, and optional audio/call constraints.
4. Confirm volatile facts using current official documentation. Record official source URLs and the date checked. Clearly distinguish official facts, secondary reports, assumptions and hands-on prototype evidence.
5. Update `RESEARCH_AND_FEASIBILITY.md`, `TECHNICAL_DECISIONS.md`, `PERMISSIONS_MATRIX.md`, and `PROGRESS_LOCAL.md`. Record GO / CONDITIONAL / NO-GO for each feature.
6. Do not implement production app features during research. Isolated proof-of-concept probes are allowed only when needed to settle a feasibility question; document their exact setup and results.
7. When the research is complete, commit all research/documentation changes to `development` using a dedicated `docs(research): ...` commit. Push to `origin/development` when available. Do not include secrets, personal clipboard contents, signing material or build artifacts.
8. Give the owner the research summary, risks, proposed MVP scope, commit hash and the exact build plan. Then begin Phase 0 unless a genuine blocker requires owner input.

### Stage B — Phase-by-phase implementation

1. Begin Phase 0 after the research package is committed and reported, unless a genuine blocker requires owner input.
2. Execute the next single phase in `PHASES_AND_ACCEPTANCE.md`; do not skip ahead or work on multiple phases together.
3. Complete implementation, tests/builds, device validation where required, permission/security review and documentation updates.
4. Commit the completed phase separately with a descriptive message, then push when possible.
5. Report what was completed, commit hash, files changed, test evidence, tests not run, USB/debugging state, permission changes, known limitations and the next phase.
6. **After the report, stop and wait for the owner's input.** Never start the next phase automatically. Continue only after the owner explicitly responds with approval or instructions.
7. If acceptance criteria fail, the work is blocked, or a decision/permission is needed, stop and ask the owner. Do not mark the phase complete or move forward.

## 2A. Git policy for all work

- Commit all completed research, sources, decisions, prototypes, test evidence, code, progress and changelog changes.
- Research is not exempt from Git. A research-only session must still produce a documentation commit.
- Commit the research package before beginning Phase 0.
- Create one focused commit per completed implementation phase; avoid bundling multiple phases.
- Push to `origin/development` when configured and available. Never force-push or merge to `main` without owner approval.

## 3. Local agent workflow

The local agent is responsible for:
- Android builds and physical-device tests.
- macOS builds and local tests.
- ADB/logcat during development if owner authorizes it.
- Updating `PROGRESS_LOCAL.md`.
- Updating research and decisions.
- Recording actual test results.
- Creating commits after completed phases.

The local agent must stop and request owner feedback when:
- a permission prompt is introduced,
- a hard constraint would be relaxed,
- a prototype proves a feature impossible,
- a product/architecture decision is needed,
- a destructive Git or data operation is proposed.

## 4. Cloud agent workflow

Cloud agent may:
- research official docs,
- review code,
- implement platform-independent protocol/documentation,
- improve tests,
- inspect CI build results,
- continue only tasks that do not require physical-device validation.

Cloud agent must not:
- claim physical S24 Ultra tests passed without evidence,
- change permission policy,
- mark device-specific prototypes complete,
- merge or overwrite local work without owner authorization.

## 5. Phase completion

For each approved phase:
1. Implement only scoped work.
2. Build both apps if shared protocol changes affect both.
3. Run unit/integration tests.
4. Perform physical device tests when required.
5. Record USB/debugging state.
6. Update progress, decisions and changelog.
7. Review permission diff.
8. Commit and push when possible.
9. Report the outcome and **wait for owner input before starting another phase**.

Suggested commit format:
- `docs: define local network permission strategy`
- `feat(android): add media projection prototype`
- `feat(macos): add local peer discovery`
- `test: verify clipboard foreground restrictions`
- `fix(network): handle peer reconnect`

## 6. Git hygiene

- Keep `.gitignore` current.
- Do not commit `.apk`, `.aab`, `.ipa`, `.dSYM`, DerivedData, `.gradle`, build/, local.properties, secrets or provisioning profiles.
- Never rewrite shared history unless explicitly authorized.
- Do not force-push.
- Do not merge to main until owner approves.
- Tag release candidates.

## 7. Handoff

At the end of every session, report:
- current branch and commit,
- files changed,
- completed tests,
- tests not run,
- USB/debugging status,
- permissions changed,
- blockers,
- next exact task.

## 8. No autonomous phase progression

Completing a phase does not authorize the next phase. The agent must stop after each phase report and wait for an explicit owner response. A response such as “continue” or “start the next phase” authorizes only the next phase, not the entire remaining roadmap.


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
