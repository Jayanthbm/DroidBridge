# DroidBridge — Phase and Prototype Status

**Last updated:** 2026-11-22
**Current phase:** Phase 1 — feasibility prototypes (P00–P17), now including the Phase 2 product track (discovery, pairing, authenticated session)
**Current position:** Phase 0 complete; Phase 2 (discovery, pairing, session) implemented and verified end-to-end; P00–P07 and P13 accepted; P08, P10 and P11 in progress; P09 is historical for tested input routes; **P16 BLE HOGP is NO-GO for Android** (host classifies the dual-mode Mac and routes HID over Classic); **P17 Classic HIDP is GO and accepted on both the OnePlus and the target S24 Ultra (acceptance run with USB unplugged and debugging off)** — SDP + L2CAP control/interrupt deliver arrow keys with USB disconnected, but S24 Ultra evidence and a both-debugging-modes-off pass are outstanding; P15 local identity persistence verified with peer reconnect outstanding; **P14 measured on both devices** — the `connectedDevice` session survives backgrounding, screen-off, Wi-Fi toggling and reboot on the S24 Ultra (Android 17), while the OnePlus freezes a valid FGS (OEM deviation); deep Doze/overnight unmeasured (ADR-043); P12 measured (media GO conditional, call audio NO-GO)

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
| **P03** Transport, pairing and the product session | **Accepted — product implementation now measured (2026-11-22)** — discovery resolves the Mac with `pv=1`; first pairing derived the same code (`537797`) on both screens and bound only after it matched; PING/PONG RTT 32–340 ms; a forced drop reconnected exactly once with the stored pin and no code prompt; a mismatched Mac identity was refused rather than trusted. Three defects fixed (main-thread connect, inverted confirmation flag, self-cancelling reconnect). | No — OnePlus AC2001 (Android 12) | Yes — Wi-Fi | 0.5 | API 37 picker path now measured on the S24 (code `280928`, denied-permission run still connects, Bluetooth-off run passes); only the USB-disconnected, both-debuggings-off sweep remains. |
| **P04** Clipboard Android→Mac | **Completed** | Yes — full matrix done | Partial — no API 33 sensitive row | 0 | macOS side is P05/P06 |
| **P05** Clipboard Mac→Android | **Completed** (Android 12 + 17) | Yes — done | Yes — Android 12 matrix done | 0 | Active-mirroring-FGS integration deferred to P08; 15/30/60-min auto-clear not run. Android 17 needs `ACCESS_LOCAL_NETWORK` outside the picker |
| **P06** macOS pasteboard prompt | **Completed on macOS 27.0.1 with limitation** — alwaysAllow, no pasteboard TCC service; deny/ask not reachable | **No** — Mac-side | Not needed; phone only for round-trip loop test | 0 | Apple Development signing did not change it; re-test prompt UX on a macOS that implements it |
| **P07** Mac video receiver | **Completed** — conversion/tests/viewer/recovery/latency/resolution/preventsCapture verified | **No** — Mac-side generated H.264 | Not needed | 0 | Backpressure false state, glass-to-glass latency, corrupt-failure and real Android input are plan-only |
| **P08** End-to-end mirroring | **Matrix complete — acceptance sweep pending** — rotation now measured in both directions (`590x1280` → `1280x590` → `590x1280`, `dropped=0`); keyframe recovery now measured (injected corrupt sample → flush → keyframe request → `status=rendering`, `dropped=0`) and a 30-minute live session is running. Rotation could not be induced through ADB on the S24 (`user-rotation lock 1` had no effect, `mCurrentOrientation=0`), so that row needs a human hand; final debuggings-off run and glass-to-glass latency also pending | **Yes** — final acceptance | Early stream source only | **0.5 day** | Depends on P01 + P03 + P07; [P08](Docs/prototypes/P08-live-mirroring.md) |
| **P09** Initial remote-input checks | **Historical — scoped NO-GO** | Checks done | Not representative | 0 | Did not test upstream Classic HIDP route. No Accessibility; see P16/P17 and ADR-036 |
| **P16** macOS BLE HID | **Closed — NO-GO for Android** | n/a | **OnePlus: bonded but never bound as HID input; zero GATT traffic** | None — do not pursue for Android | Reproduced with both the project POC and the unmodified upstream BLE app, so it is Android's `[ DUAL ]` host classification, not a GATT layout defect. BLE remains recorded as an iOS-host path only. See ADR-041 |
| **P17** macOS Classic HIDP | **GO and accepted** — OnePlus Nord (Android 12) and target S24 Ultra (Android 17) | **Yes — mandatory** | **S24 Ultra: 48/48 presses delivered** with USB unplugged and both USB and wireless debugging off (`14/14/14/14` → `26/26/26/26`); OnePlus 48-press burst also zero drops | None — route approved | Production input surface, mapping and text entry still unproven; see [P17](Docs/prototypes/P17-classic-hidp.md) |
| **P10** Secure screen/protection | **Core claim measured — acceptance pending** — `FLAG_SECURE` mirrors as black: luma **1.0** across 809 frames while the identical unprotected screen reads **196.4**, measured by recording the stream and decoding offline. Password screen mirrors normally (238.1). OTP private/public identical while unlocked (80.3 / 80.5) because both render in full. Locking stops the projection outright (`system onStop`), so lock-screen content cannot leak and the redaction row is unobservable rather than unresolved | **Yes** | Android 12 baseline only | **0.5 day** — remaining: final debuggings-off run | Depends on P01/P08; [P10](Docs/prototypes/P10-secure-screen-protection.md) |
| **P11** macOS local-network/TCC | **Complete for macOS 27.2.1 — no gate measured** | No — Mac-side | Not needed | 0 | Local Network privacy did not gate browse or outbound connect (no alert, no Settings entry) across 4 launch paths; deniable state unreachable; re-measure on future macOS (ADR-044, R-38) |
| **P12** Audio/calls research | **Measured — media GO (conditional), call audio NO-GO (measured), call control deferred** — on SM-S928B (Android 17): allowed playback (`USAGE_MEDIA`/`GAME`/`UNKNOWN`, `ALLOW_CAPTURE_BY_ALL`) captured **bit-exactly** (`rms=5791.5 peak=8190`), including a real third-party YouTube client; refused (`rms=0.0` while frames kept advancing) for `USAGE_VOICE_COMMUNICATION` and `USAGE_NOTIFICATION`, and for any usage under `ALLOW_CAPTURE_BY_NONE`. Call audio is closed by the platform's capture policy, not by app choice. | No — device | Not needed | 0.5 | `RECORD_AUDIO` is experiment-scoped in `:experiments:audio-capture` only; audio is measured locally and never transmitted. Open: whether audio capture can share the mirror's single `MediaProjection` instance on Android 17. |
| **P13** Pinned TLS / CT / ECH on Android 17 | **Completed** — TLS 1.3, mutual auth, pin mismatch, no-cert rejection, CT audit | Yes — done | No — Android 12 is not representative | 0 | Same-key certificate re-issue (plan-only) not run |
| **P14** Background persistence | **Complete — deep Doze and overnight measured 2026-11-23** | Yes | **S24 Ultra (Android 17): session survived backgrounding, screen-off, Wi-Fi toggle and reboot.** OnePlus (Android 12) freezes a valid FGS | night measured: 21 automatic reconnects, 80/45/30-min Doze windows, 100→75 % battery | ADR-042/ADR-043; app detects a stalled session and degrades to foreground-only |
| **P15** Sandboxed macOS build | **Personal-use identity verified; reconnect verified; ACL question answered** — Android reconnected on its stored pin across an app restart and a rebuild without re-pairing. Keychain ACL behaviour across a signing change is now measured: an ad-hoc rebuild invalidated the ACL and blocked startup silently (`securityd` showed stale cdhashes), and signing with the stable Apple Development identity fixed it. Developer ID/notarisation/quarantine checks remain deferred by owner while personal-use only | **No** for local build/signature | Not needed | Distribution deferred | Re-validation under a Developer ID identity belongs to the deferred Phase 9 work; see [P15](Docs/prototypes/P15-signed-build.md) |
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
[P08 (in progress)](Docs/prototypes/P08-live-mirroring.md) ·
[P11 (in progress)](Docs/prototypes/P11-local-network-permission.md)

### Phase 1 totals

- **Accepted:** P00, P01, P02, P03, P04, P05, P06, P07, P13 (9 prototypes; P09 remains a historical scoped NO-GO, not the final HID decision).
- **In progress:** P08 — stream, rotation, recovery, teardown, screen-off and keyguard stops, idle-cost comparison and an earlier 30-minute stability run measured; latest unplugged supervised attempt ended when the phone locked, and no new 30-minute awake run completed. Historical shade-freeze cause, final debugging-off acceptance, and latency/device-cost instrumentation remain open; P10 — protected surfaces withheld, password case captured, public-version redaction observed in an earlier run, current private-only notification test inconclusive because no notification appeared in the shade, and the latest unlocked/unplugged shade observation ended after mirroring stopped; P11 — current-state Bonjour browse/resolve passed on macOS 27.2.1 and denied-state recovery UI added, but first-launch prompt, denied-state behavior and Android picker integration remain untested. P15's personal-use Release build/signature audit and login-Keychain identity persistence pass; isolated persistence/fail-closed tests pass and the full macOS suite is 67/67. The macOS bundle ID is `com.droidbridge.app`; the canonical app copy was verified and resolved Bonjour by itself. Android reconnect without re-pairing remains untested. Developer ID/notarisation are distribution-only work explicitly deferred by the owner.
- **P16:** closed as NO-GO for Android. The phone bonds the Mac but never creates a HID input device (`HidHostService.mInputDevices` empty) and the Mac receives no GATT traffic; the unmodified upstream BLE app behaves identically. Because the Mac advertises on its public BR/EDR address, Android lists it `[ DUAL ]` and routes HID over Classic. Canonical UUIDs, required Battery/Device Information services and the Boot-only Report Map remain implemented but cannot change host routing. No key was sent over BLE.
- **P17:** GO on OnePlus Nord AC2001 / Android 12 only, demonstrated with DroidBridge's own `P17ClassicHIDP`; a 48-press session delivered `+12` per direction with no drops, and a quit-while-connected now reconnects immediately after the shutdown fix. SDP HID record published, L2CAP `0x0011`/`0x0013` opened from the Mac, and arrow press/release reports counted by a focused receiver (`UP 2, DOWN 1, LEFT 1, RIGHT 1` from a zeroed baseline) with USB disconnected and wireless debugging off. USB debugging was enabled, so this is not final acceptance; S24 Ultra, sustained typing and the app's own programmatic pairing remain outstanding; a force-quit still needs a phone Bluetooth cycle. macOS requires a Bluetooth privacy grant and a peer-initiated bond (ADR-042).
- **Pending:** acceptance sweeps with both debugging modes disabled (P14 deep Doze/overnight is now measured). The P08 rotation row is now measured (ADB rotation via `fixed-to-user-rotation` + `user-rotation lock`), so no item is blocked on a human hand for rotation.
- **Remaining effort excluding P12:** roughly **9–20 engineer-days** for the current personal-use scope, including P16/P17 feasibility; Developer ID/notarisation are excluded as deferred distribution work.
- **Including P12 if approved:** roughly **20–34 engineer-days**.
- **Calendar, one engineer:** roughly **6–8 weeks**, assuming S24 availability and no Apple wait.
- **Critical path:** P07 → P08 → P10/P14; P16/P17 gate the Bluetooth-HID control feasibility decision. P13 runs in parallel once P03 is available; P15 local checks can proceed without membership, while its deferred distribution checks await an explicit distribution goal and Apple membership.

Update note (2026-11-17): The owner confirmed personal-use-only scope and deferred Apple Developer ID distribution. P15 Release build and local launch passed on the Mac; the exact embedded entitlements, hardened-runtime flag, and absent `get-task-allow` were audited. Gatekeeper rejected the ad-hoc build as expected. A corrected login-Keychain path produced the same pin in two separate Release identity-harness processes. Disposable-Keychain persistence and orphan-key fail-closed tests pass; full macOS suite 67/67. Android reconnect without re-pairing and Keychain ACL behavior across a signing identity change remain untested. The earlier generated Keychain key item remains untouched and is not adopted by the new versioned tag. Notarization and quarantined distribution acceptance are deferred, not passed. See [ADR-033](Docs/TECHNICAL_DECISIONS.md#adr-033--defer-macos-developer-id-distribution-while-droidbridge-is-personal-use-only), [ADR-034](Docs/TECHNICAL_DECISIONS.md#adr-034--persist-personal-use-mac-identity-in-the-login-keychain-until-a-profile-is-available) and [P15](Docs/prototypes/P15-signed-build.md). Mac/Android mirroring source hardening and automated tests were added for the historical P10 shade freeze investigation. The implementation uses a monotonic bounded-burst token bucket, limits per-frame UI publication/logging, validates AVCC/IDR and monotonic PTS per format epoch, requires an IDR for startup/format/decoder recovery, and reattaches the activity UI to the process-held session after recreation. macOS 65/65 unit tests and Android mirroring 22/22 unit tests plus Debug APK assembly passed. A separate live S24 Ultra run later that day showed the notification shade in the Mac mirror and the stream remained active through dismissal; this one run does not identify the historical root cause or prove a general fix. A synthetic notification with a public version showed its redacted text on phone and Mac; the private-only notification was posted but its visual outcome is inconclusive. USB was connected, USB and wireless debugging were enabled, and ADB used USB. Rotation UI restoration, P08 final debugging-disabled acceptance, and device-cost/latency remain pending. Details: [P08](Docs/prototypes/P08-live-mirroring.md), [P10](Docs/prototypes/P10-secure-screen-protection.md).

### What can start without the S24 Ultra

- **P06** remaining deny/ask-state UX work (blocked on a signed build or another macOS version).
- **P07** remaining backpressure/latency/preventsCapture measurements (Mac-only).
- **P11 follow-up** first-launch/denied-state permission UI and Android picker integration.
- **P15 preparation** — sandbox/signing steps that do not need the Apple membership.
- Documentation and automated tests.

### What must wait for the S24 Ultra

- **P05** full Android 13+ clipboard matrix.
- **P08/P09/P10/P14/P16/P17** Android 17 / One UI 9 target behaviour.
- **P13** Android 17 CT/ECH and pinned TLS.
- **P12** if approved.

The backup OnePlus Nord CE (Android 12 / API 31) is useful for early/regression work and basic text
clipboard, but it cannot substitute for P02 (API 37), P13 (Android 17 TLS defaults), or the One UI 9
behaviour in P09/P10/P12/P14.

## Phase-level status

| Phase | Status | Gate / remaining work |
|---|---|---|
| **Phase 0** Repository and environment | **Complete** | — |
| **Phase 1** Feasibility prototypes | **Measured; acceptance sweeps outstanding** | P14 deep Doze/overnight now measured (2026-11-23) and P17 Classic HIDP accepted; P08/P10 are measured but their **USB-disconnected, debugging-off** acceptance sweeps still need a human to start a mirroring session, so the phase is not closed. P16 BLE HOGP is a recorded NO-GO. |
| **Phase 2** Shared protocol, discovery, pairing | **Partially evidenced** | P02/P03 prove the path; not formally closed. Needs the Mac identity/re-approval decision, P11/P13 outcomes, and Bluetooth-off confirmation |
| **Phase 3** Clipboard MVP | **Implemented and measured (USB attached)** | Text sync works both ways with hash+origin loop prevention, pause, 64 KiB limit, sensitive-clip refusal and no persistence — see [PHASE3-clipboard-sync.md](Docs/prototypes/PHASE3-clipboard-sync.md). Remaining for formal close: one cable-out sweep with both debuggings disabled. |
| **Phase 4** Screen capture and streaming | **Implemented and measured (USB attached)** | Product pipeline streams, rotates, and recovers from a decoder fault on a keyframe request — see PROGRESS_LOCAL 2026-11-22 Phase 4 entries. Reconnect recovery measured 2026-11-23 (format re-announced, frames resumed, dropped=0); open: 30-minute endurance through the product path and the stop path | |
| **Phase 5** Remote input decision | **Not started** | Route is now Classic HIDP (BLE HOGP is NO-GO for Android); needs independent DroidBridge implementation, S24 Ultra reproduction, reconnect/relaunch coverage and a USB-disconnected, debugging-off acceptance run before any keyboard claim |
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
- **Remote input:** no production implementation or claim. BLE HOGP from macOS to Android is a recorded NO-GO (host routes the `[ DUAL ]` Mac over Classic; zero GATT traffic, reproduced with the unmodified upstream BLE app). The working direction is P17 Classic HIDP, which on the OnePlus delivered arrow press/release input through Android's normal input pipeline with USB disconnected and wireless debugging off — but that used the AGPL upstream app, is untested on the S24 Ultra, and has no DroidBridge implementation. Accessibility and ADB functional paths remain prohibited; HID keyboard success would not establish pointer or touch-equivalent control.
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
