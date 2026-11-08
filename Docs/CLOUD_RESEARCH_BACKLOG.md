# Cloud Research Backlog

**Purpose:** Track research/documentation work that can be completed without the owner's Mac or Android device, and distinguish it from work that requires local prototype evidence.
**Owner:** Cloud Research Agent
**Branch:** `development`
**Last reviewed:** 2026-11-08

## Priority 1 — Documentation and workflow integrity

- [x] Remove stale instructions that conflict with cloud research-only ownership.
- [x] Make `development` the integration branch and `master` the stable-release branch throughout core workflow docs.
- [x] Replace routine owner approval after every phase with autonomous progression and explicit genuine-blocker stop conditions.
- [x] Add `PROGRESS_CLOUD.md` and this backlog.
- [ ] Scan the full repository for remaining stale `main` references, owner-approval gates or cloud implementation permissions; distinguish historical notes from active instructions.
- [ ] Verify mandatory reading lists include the cloud progress/backlog files where relevant.

## Priority 2 — Ongoing official-source refresh

Re-check volatile facts close to implementation/release; record source URL, date checked, applicable OS/API, and any change in `RESEARCH_AND_FEASIBILITY.md`.

- [ ] Android 17/API 37 local-network permission, NSD picker and service-grant lifecycle.
- [ ] Android MediaProjection and foreground-service changes for the actual target SDK.
- [ ] Android clipboard/background execution and Doze restrictions.
- [ ] macOS local-network privacy, Bonjour and pasteboard access prompts.
- [ ] Apple signing, hardened runtime, provisioning profile, notarisation and Gatekeeper requirements.
- [ ] Google Play target API, foreground-service declarations and restricted permission policies.

## Priority 3 — Architecture/security review (after local implementation is pushed)

- [ ] Review Android and macOS TLS 1.3 mutual-authentication implementation against ADR-024 and ADR-025.
- [ ] Review SPKI pinning, certificate rotation, identity-change UX and trust failure handling.
- [ ] Review pairing-code entropy, rate limiting, replay resistance and session lifecycle.
- [ ] Review H.264 framing integration against the shared protocol module and ADR-027/ADR-028.
- [ ] Review LAN discovery, address re-resolution, revocation and reconnect paths.
- [ ] Review log redaction, clipboard privacy, sensitive payload handling and local-only data flow.
- [ ] Review actual Android manifest and macOS entitlements against `PERMISSIONS_MATRIX.md`.

## Priority 4 — Prototype planning and evidence review

Cloud can prepare instructions and review evidence, but the local agent owns execution.

- [ ] For each P00–P15 report, verify required environment header, exact steps, raw result, negative findings, USB/debugging state and acceptance conclusion.
- [ ] Identify any missing test cases or ambiguous claims and request a local rerun only when evidence is insufficient.
- [ ] Update research/ADR status only from credible documentation or actual local evidence.
- [ ] Keep all device-specific prototype statuses pending until local reports prove completion.

## Priority 5 — Release readiness research

- [ ] Confirm current Google Play target API and declaration requirements immediately before submission.
- [ ] Confirm current Apple Developer Program / Developer ID / notarisation process and account-role prerequisites.
- [ ] Review privacy disclosures and data-safety statements against the actual implementation.
- [ ] Review clean-install, update, uninstall, pairing reset and recovery documentation.
- [ ] Review release checklist and known-limitations wording.

## Explicitly not cloud-owned

- Creating or changing Android/macOS app shells or feature code.
- Running Gradle/Xcode builds or claiming they pass.
- Running ADB, MediaProjection, LAN, clipboard or other physical-device prototypes.
- Capturing screenshots/logs from the owner's devices.
- Marking a prototype or device-specific acceptance criterion complete without local evidence.

## Handoff rule

Cloud work starts only after local implementation and test evidence are committed/pushed. Cloud syncs latest `development`, makes a scoped documentation/review commit, pushes it, and hands back to local. Never run competing branch writes.
