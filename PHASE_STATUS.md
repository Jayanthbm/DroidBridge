# DroidBridge — Phase and Prototype Status

**Last updated:** 2026-11-17
**Current phase:** Phase 1 — feasibility prototypes (P00–P15)
**Current position:** Phase 0 complete; P00–P07, P09 and P13 accepted; P08 and P10 in progress; P15 local identity persistence verified with peer reconnect outstanding; P11, P14 pending; P12 approval-gated

This file is the at-a-glance status dashboard. It is **not** a replacement for the evidence
reports: detailed test evidence, limitations and decisions live in `Docs/PROGRESS_LOCAL.md`,
`Docs/PHASES_AND_ACCEPTANCE.md`, `Docs/PROTOTYPE_PLAN.md` and `Docs/prototypes/`. When a status
changes, update this file **in the same commit** as those documents.

> Update rule: after every prototype completion and every phase-gate decision, update this file,
> re-estimate what remains, run `scripts/verify-all.sh`, and commit/push on `development`.

## Status vocabulary

| Status | Meaning |
|---|---|
| **Completed** | Acceptance evidence exists and is linked from the detailed report |
| **In progress** | Work has started but acceptance is not met |
| **Pending** | Not started; estimate and dependency are known |
| **Approval-gated** | Cannot start without a separate owner decision |
| **Deferred** | Owner has explicitly postponed it; not claimed as passed |
| **Blocked** | A concrete external or platform condition prevents progress |

## Phase 1 — prototype status

Estimates are planning ranges for **one engineer**, including implementation, tests and the
evidence report. They assume the S24 Ultra is available on demand, no new platform blocker appears,
and no Apple membership/notarisation wait. They are estimates, not commitments.

| Prototype | Status | S24 Ultra needed? | OnePlus Android 12? | Estimate | Dependency / notes |
|---|---|---|---|---|---|
| **P00** Environment baseline | **Completed** | Yes — done | Yes — done | 0 | Independent |
| **P01** MediaProjection capture | **Completed** | Yes — done | Yes — Android 12 evidence exists | 0 | 14/14 required items; plan-only extras documented |
| **P02** Android 17 LAN/picker | **Completed** | **Mandatory** — done | **No** — API 37 only | 0 | Mac sleep/wake passed; Mac restart needs one re-approval; environment-limited tests documented |
| **P03** Transport and pairing | **Completed** (acceptance met) | Yes — done | Partial only | 0 | Follow-ups: automatic reconnect, idle deadline, Android reconnect after Mac restart with persisted identity, Keychain ACL behavior after code-signing changes, on-device tamper UI, GUI pairing path |
| **P04** Clipboard Android→Mac | **Completed** | Yes — full matrix done | Partial — no API 33 sensitive row | 0 | macOS side is P05/P06 |
| **P05** Clipboard Mac→Android | **Completed** (Android 12 + 17) | Yes — done | Yes — Android 12 matrix done | 0 | Active-mirroring-FGS integration deferred to P08; 15/30/60-min auto-clear not run. Android 17 needs `ACCESS_LOCAL_NETWORK` outside the picker |
| **P06** macOS pasteboard prompt | **Completed on macOS 27.0.1 with limitation** — alwaysAllow, no pasteboard TCC service; deny/ask not reachable | **No** — Mac-side | Not needed; phone only for round-trip loop test | 0 | Apple Development signing did not change it; re-test prompt UX on a macOS that implements it |
| **P07** Mac video receiver | **Completed** — conversion/tests/viewer/recovery/latency/resolution/preventsCapture verified | **No** — Mac-side generated H.264 | Not needed | 0 | Backpressure false state, glass-to-glass latency, corrupt-failure and real Android input are plan-only |
| **P08** End-to-end mirroring | **In progress** — 2026-11-17 live S24 run mirrored the notification shade and remained active through dismissal; this single non-failure run does not establish the historical freeze's root cause or prove a general fix. Rotation UI device check, final debuggings-off run, glass-to-glass latency and device cost pending | **Yes** — final acceptance | Early stream source only | **1 day** | Depends on P01 + P03 + P07; [P08](Docs/prototypes/P08-live-mirroring.md) |
| **P09** Remote input decision | **Completed — NO-GO** | Yes — checks done | Not representative | 0 | Accessibility/injection/Knox/HID refusals recorded; Phase 5 owner acceptance still needed |
| **P10** Secure screen/protection | **In progress** — historical shade freeze remains unroot-caused; one fresh 2026-11-17 run showed the shade in the mirror and stayed active. Public-version synthetic OTP redaction was visually observed; private-only OTP visibility remains inconclusive / **Awaiting Local Validation** | **Yes** | Android 12 baseline only | **1–2 days** | Depends on P01/P08; [P10](Docs/prototypes/P10-secure-screen-protection.md) |
| **P11** macOS local-network/TCC | **Pending** | **No** for Mac-side | Not needed; S24 for final NSD integration | **1–2 days** | Independent; benefits from P02 integration |
| **P12** Audio/calls research | **Pending — approval-gated** | Yes | Not representative | **2–4 days** if approved | Separate owner approval; can be deferred without blocking the core product |
| **P13** Pinned TLS / CT / ECH on Android 17 | **Completed** — TLS 1.3, mutual auth, pin mismatch, no-cert rejection, CT audit | Yes — done | No — Android 12 is not representative | 0 | Same-key certificate re-issue (plan-only) not run |
| **P14** Background persistence | **Pending** | **Yes** | No — One UI 9/Doze behaviour matters | **2–3 days** | Depends on P03 + P13; feature side P05/P06 or P08 |
| **P15** Sandboxed macOS build | **Personal-use identity persistence verified; reconnect remains** — Release build/signature audit pass; two separate login-Keychain Release harness launches reported the same pin. Disposable-Keychain persistence and fail-closed tests pass; full macOS suite 67/67. Android reconnect without re-pairing and Keychain ACL behavior across code-signing changes remain untested. Developer ID/notarisation/quarantine checks deferred by owner while personal-use only | **No** for local build/signature; optional for Android smoke test | Not needed | Validate reconnect; distribution deferred | Ad-hoc app runs from local build output; Gatekeeper rejects it as expected. Do not claim distribution readiness; see [P15](Docs/prototypes/P15-signed-build.md) and ADR-033 |
Completed prototype evidence:
[P00](Docs/prototypes/P00-environment-report.md) ·
[P01](Docs/prototypes/P01-media-projection.md) ·
[P02](Docs/prototypes/P02-local-networking.md) ·
[P03](Docs/prototypes/P03-transport-and-pairing.md) ·
[P03 live-handshake evidence](Docs/prototypes/P03-live-handshake-evidence.md) ·
[P04](Docs/prototypes/P04-clipboard-android-to-mac.md) ·
[P05](Docs/prototypes/P05-clipboard-mac-to-android.md) ·
[P06](Docs/prototypes/P06-macos-pasteboard.md) ·
[P07](Docs/prototypes/P07-mac-video-receiver.md) ·
[P15](Docs/prototypes/P15-signed-build.md) ·
[P13](Docs/prototypes/P13-pinned-tls-android17.md) ·
[P09](Docs/prototypes/P09-remote-input.md) ·
[P10 (in progress)](Docs/prototypes/P10-secure-screen-protection.md) ·
[P08 (in progress)](Docs/prototypes/P08-live-mirroring.md)

### Phase 1 totals

- **Accepted:** P00, P01, P02, P03, P04, P05, P06, P07, P09, P13 (10 of 16).
- **In progress:** P08 — stream, rotation, recovery, teardown, screen-off and keyguard stops, idle-cost comparison and an earlier 30-minute stability run measured; latest unplugged supervised attempt ended when the phone locked, and no new 30-minute awake run completed. Historical shade-freeze cause, final debugging-off acceptance, and latency/device-cost instrumentation remain open; P10 — protected surfaces withheld, password case captured, public-version redaction observed in an earlier run, current private-only notification test inconclusive because no notification appeared in the shade, and the latest unlocked/unplugged shade observation ended after mirroring stopped. P15's personal-use Release build/signature audit and login-Keychain identity persistence pass; isolated persistence/fail-closed tests pass and the full macOS suite is 67/67. Android reconnect without re-pairing remains untested. Developer ID/notarisation are distribution-only work explicitly deferred by the owner.
- **Pending:** P11, P12, P14 (3 prototypes; P12 approval-gated).
- **Remaining effort excluding P12:** roughly **7–16 engineer-days** for the current personal-use scope; Developer ID/notarisation are excluded as deferred distribution work.
- **Including P12 if approved:** roughly **18–32 engineer-days**.
- **Calendar, one engineer:** roughly **5–7 weeks**, assuming S24 availability and no Apple wait.
- **Critical path:** P07 → P08 → P09/P10/P14; P13 runs in parallel once P03 is available; P15 local checks can proceed without membership, while its deferred distribution checks await an explicit distribution goal and Apple membership.

Update note (2026-11-17): The owner confirmed personal-use-only scope and deferred Apple Developer ID distribution. P15 Release build and local launch passed on the Mac; the exact embedded entitlements, hardened-runtime flag, and absent `get-task-allow` were audited. Gatekeeper rejected the ad-hoc build as expected. A corrected login-Keychain path produced the same pin in two separate Release identity-harness processes. Disposable-Keychain persistence and orphan-key fail-closed tests pass; full macOS suite 67/67. Android reconnect without re-pairing and Keychain ACL behavior across a signing identity change remain untested. The earlier generated Keychain key item remains untouched and is not adopted by the new versioned tag. Notarization and quarantined distribution acceptance are deferred, not passed. See [ADR-033](Docs/TECHNICAL_DECISIONS.md#adr-033--defer-macos-developer-id-distribution-while-droidbridge-is-personal-use-only), [ADR-034](Docs/TECHNICAL_DECISIONS.md#adr-034--persist-personal-use-mac-identity-in-the-login-keychain-until-a-profile-is-available) and [P15](Docs/prototypes/P15-signed-build.md). Mac/Android mirroring source hardening and automated tests were added for the historical P10 shade freeze investigation. The implementation uses a monotonic bounded-burst token bucket, limits per-frame UI publication/logging, validates AVCC/IDR and monotonic PTS per format epoch, requires an IDR for startup/format/decoder recovery, and reattaches the activity UI to the process-held session after recreation. macOS 65/65 unit tests and Android mirroring 22/22 unit tests plus Debug APK assembly passed. A separate live S24 Ultra run later that day showed the notification shade in the Mac mirror and the stream remained active through dismissal; this one run does not identify the historical root cause or prove a general fix. A synthetic notification with a public version showed its redacted text on phone and Mac; the private-only notification was posted but its visual outcome is inconclusive. USB was connected, USB and wireless debugging were enabled, and ADB used USB. Rotation UI restoration, P08 final debugging-disabled acceptance, and device-cost/latency remain pending. Details: [P08](Docs/prototypes/P08-live-mirroring.md), [P10](Docs/prototypes/P10-secure-screen-protection.md).

### What can start without the S24 Ultra

- **P06** remaining deny/ask-state UX work (blocked on a signed build or another macOS version).
- **P07** remaining backpressure/latency/preventsCapture measurements (Mac-only).
- **P11** macOS local-network permission/TCC.
- **P15 preparation** — sandbox/signing steps that do not need the Apple membership.
- Documentation and automated tests.

### What must wait for the S24 Ultra

- **P05** full Android 13+ clipboard matrix.
- **P08/P09/P10/P14** Android 17 / One UI 9 target behaviour.
- **P13** Android 17 CT/ECH and pinned TLS.
- **P12** if approved.

The backup OnePlus Nord CE (Android 12 / API 31) is useful for early/regression work and basic text
clipboard, but it cannot substitute for P02 (API 37), P13 (Android 17 TLS defaults), or the One UI 9
behaviour in P09/P10/P12/P14.

## Phase-level status

| Phase | Status | Gate / remaining work |
|---|---|---|
| **Phase 0** Repository and environment | **Complete** | — |
| **Phase 1** Feasibility prototypes | **In progress** | P08 and P10 remain in progress; P05–P15 include completed, active and pending work; P12 approval-gated |
| **Phase 2** Shared protocol, discovery, pairing | **Partially evidenced** | P02/P03 prove the path; not formally closed. Needs the Mac identity/re-approval decision, P11/P13 outcomes, and Bluetooth-off confirmation |
| **Phase 3** Clipboard MVP | **Not started** | P05/P06 plus loop prevention, deduplication, size limits, no-history default |
| **Phase 4** Screen capture and streaming | **Partially evidenced** | P01 capture done; P07/P08 receiver, 30-minute session, keyframe recovery pending |
| **Phase 5** Remote input decision | **Not started** | P09; expected NO-GO, then owner accepts view-only |
| **Phase 6** Hardening and UX | **Not started** | Permission UX, pairing revocation, failure states, accessibility, security/regression tests |
| **Phase 7** Optional Bluetooth | **Not started** | Only if useful; Wi-Fi must remain independent |
| **Phase 8** Audio/calls research | **Not started — approval-gated** | Separate scope and owner approval; no call recording |
| **Phase 9** Release | **Not started** | P15 signing/notarisation, privacy/permission audit, Play declarations, user docs, Apple membership |

## Current product/engineering limitations

- **Mac restart re-approval:** a new Mac service instance (new ephemeral port/session id) requires
  one fresh picker approval. Decide between a stable Mac identity/port and an explicit re-approval UX.
- **Permission revocation:** `ACCESS_LOCAL_NETWORK` revocation did not apply to a running process
  until reboot. Do not rely on it as an immediate security control.
- **Background clipboard:** not a release claim; Android focus/IME limits and macOS pasteboard
  prompts still govern the design.
- **Remote input:** expected NO-GO under the current constraints; P09 must confirm with evidence.
- **Release dependency:** macOS notarisation requires a paid Apple Developer Program membership;
  this is an external dependency, not a technical uncertainty.
- **Wireless-only acceptance:** the existing USB-disconnected run is P03 evidence. P01/P02 device
  sessions were USB-attached; their reports state this rather than borrowing that claim.

## Update protocol after each phase/prototype

1. Change the prototype/phase status in this file and add the evidence link.
2. Update the detailed report, `Docs/PROGRESS_LOCAL.md` and, when a gate changes,
   `Docs/PHASES_AND_ACCEPTANCE.md`.
3. Record device/OS, USB and debugging state, and whether the result is S24 or OnePlus evidence.
4. Re-estimate the remaining ranges and the calendar impact.
5. Run the relevant tests, lint and `scripts/verify-all.sh`.
6. Commit with a scoped message (for example `docs(status): complete P05 clipboard Mac to Android`)
   and push to `development`. Do not mark something completed without linked evidence.
