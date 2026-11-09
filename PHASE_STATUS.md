# DroidBridge — Phase and Prototype Status

**Last updated:** 2026-11-09
**Current phase:** Phase 1 — feasibility prototypes (P00–P15)
**Current position:** Phase 0 complete; P00–P04 accepted; P05 in progress (Android 12 matrix done, S24 rows pending); P06–P15 pending

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
| **P03** Transport and pairing | **Completed** (acceptance met) | Yes — done | Partial only | 0 | Follow-ups: automatic reconnect, idle deadline, persistent Mac identity, on-device tamper UI, GUI pairing path |
| **P04** Clipboard Android→Mac | **Completed** | Yes — full matrix done | Partial — no API 33 sensitive row | 0 | macOS side is P05/P06 |
| **P05** Clipboard Mac→Android | **In progress** — Android 12 matrix pass; S24 rows pending | **Yes for remaining rows** | Partial — basic text works on Android 12; no Android 13+ preview/sensitive behaviour | **0.5–1 day remaining** | Background/cross-app/Unicode/100 KiB/duplicate/oversize pass on Android 12; preview, sensitive and active-mirroring-FGS rows need S24 |
| **P06** macOS pasteboard prompt | **Pending** | **No** — Mac-side | Not needed; phone only for round-trip loop test | **1–2 days** | Independent; gates ADR-014; can start immediately |
| **P07** Mac video receiver | **Pending** | **No** — Mac-side generated H.264 | Not needed | **2–3 days** | Independent of Android runtime; uses P01 framing; can start immediately |
| **P08** End-to-end mirroring | **Pending** | **Yes** — final acceptance | Early stream source only | **3–5 days** | Depends on P01 + P03 + P07; critical path |
| **P09** Remote input decision | **Pending** | **Yes** | Second data point only | **1–2 days** | Depends on a live mirror session (P08); expected NO-GO confirmation |
| **P10** Secure screen/protection | **Pending** | **Yes** | Android 12 baseline only | **1–2 days** | Depends on P01/P08; capture-level work can start after P01 |
| **P11** macOS local-network/TCC | **Pending** | **No** for Mac-side | Not needed; S24 for final NSD integration | **1–2 days** | Independent; benefits from P02 integration |
| **P12** Audio/calls research | **Pending — approval-gated** | Yes | Not representative | **2–4 days** if approved | Separate owner approval; can be deferred without blocking the core product |
| **P13** Pinned TLS / CT / ECH on Android 17 | **Pending** | **Mandatory** | **No** — Android 12 is not representative | **2–3 days** | Depends on P03/P02; can run parallel with P05/P07 |
| **P14** Background persistence | **Pending** | **Yes** | No — One UI 9/Doze behaviour matters | **2–3 days** | Depends on P03 + P13; feature side P05/P06 or P08 |
| **P15** Sandboxed/signed/notarised macOS build | **Pending** | **No** for signing; S24 for final smoke | Not needed | **2–4 days + external wait** | Depends on P11, P13, P07; Apple Developer Program membership is external |

Completed prototype evidence:
[P00](Docs/prototypes/P00-environment-report.md) ·
[P01](Docs/prototypes/P01-media-projection.md) ·
[P02](Docs/prototypes/P02-local-networking.md) ·
[P03](Docs/prototypes/P03-transport-and-pairing.md) ·
[P03 live-handshake evidence](Docs/prototypes/P03-live-handshake-evidence.md) ·
[P04](Docs/prototypes/P04-clipboard-android-to-mac.md) ·
[P05](Docs/prototypes/P05-clipboard-mac-to-android.md)

### Phase 1 totals

- **Accepted:** P00, P01, P02, P03, P04 (5 of 16).
- **In progress:** P05 — Android 12 matrix complete; S24 rows pending.
- **Pending:** P06–P15 (10 prototypes; P12 approval-gated).
- **Remaining effort excluding P12:** roughly **15–27 engineer-days**, including P05's remaining S24 rows.
- **Including P12 if approved:** roughly **18–32 engineer-days**.
- **Calendar, one engineer:** roughly **5–7 weeks**, assuming S24 availability and no Apple wait.
- **Critical path:** P07 → P08 → P09/P10/P14; P13 runs in parallel once P03 is available; P15 waits on P11/P13/P07 and Apple membership.

### What can start without the S24 Ultra

- **P06** macOS pasteboard prompt/monitoring.
- **P07** Mac H.264 receiver with a generated stream.
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
| **Phase 1** Feasibility prototypes | **In progress** | P05–P15 remain; P12 approval-gated |
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
