# Development Phases and Acceptance Criteria

## Gate A — Research documentation
**Status:** Complete — 2026-11-08

Official-source research and the documentation pass are recorded in `RESEARCH_AND_FEASIBILITY.md`, `TECHNICAL_DECISIONS.md`, and `PERMISSIONS_MATRIX.md` (R-01–R-31 and ADR-010–ADR-028). This closes the documentation research gate only. No physical-device prototype is implied to have passed.

## Phase 0 — Repository and environment
**Status:** Complete — 2026-11-09
Owner: Local Development Agent.
- Inspect repository, toolchains and actual device/host environment.
- Create native Android and macOS project shells.
- Establish shared module/build structure and scripts/CI where practical.
- Verify clean builds from a clean checkout.
- No production feature implementation.

Exit: both native shells build cleanly; actual tool/device versions are recorded; permission and repository audits are documented; focused commit pushed to `development`.

**Result:** met. `android/` (10 modules) and `macos/` (sandboxed SwiftUI shell) both build from a
clean checkout; 31/31 shared protocol tests and 4/4 macOS tests pass; Android lint is clean with
`warningsAsErrors`; the APK installs and launches on the OnePlus Nord CE (Android 12 / API 31) with
no crash or ANR. Audits in `PERMISSIONS_MATRIX.md` §6. Reports in `Docs/prototypes/`.
**Not done, and not claimed:** the S24 Ultra baseline (device not attached) and a device screenshot
(the phone was PIN-locked).

## Phase 1 — Feasibility prototypes (next)
**Status:** In progress — P00, P01, P02, P03 and P04 have device evidence. **P01 is validated on
Android 12 and on Android 17 / S24 Ultra**: consent, foreground service, surface-fed H.264,
rotation, lock/`onStop`, user stop, 20 start/stop cycles, secure-content protection and token
replay (14/14 required items; three plan-only extras remain open). **P05 is completed on Android 12 and Android 17** — the Mac→Android clipboard matrix passes on
both, including the Android 17 "Copied." confirmation and the `ACCESS_LOCAL_NETWORK` requirement
outside the picker; active-mirroring-FGS integration is deferred to P08. **P06 is completed on macOS 27.0.1 with a platform limitation** — the allowed state and
write-suppression pass; the OS never presents a pasteboard prompt, even for an Apple Development-
signed build, and TCC logs contain no pasteboard service. The deny/ask UX must be re-tested on a
macOS version that implements it. **P07 is completed** — parser, conversion
tests, live viewer, flush/recovery, startup latency, resolution changes and `preventsCapture`
(video area black in a screen capture) pass; backpressure false state and glass-to-glass latency are
plan-only measurements. **P15 personal-use identity persistence is implemented and verified locally** — separate Release identity-harness processes retain one login-Keychain pin and macOS persistence/fail-closed tests pass. Peer reconnect without re-pairing is now measured in both directions (2026-11-26: a Mac restart reconnected the phone on the stored pin at 16:40:58 with no code prompt, and a phone app restart reconnects the same way); Keychain ACL behavior after a signing-identity change remains open. Developer ID/notarisation are explicitly deferred while personal-use only. **P13 is completed** — TLS 1.3/mutual auth/pinning/pin-mismatch
and no-certificate rejection pass on the S24 Ultra under API 37 default CT. **P09's 2026-11-10 NO-GO is historical and scoped to its tested mechanisms.** New source review (R-32/ADR-036) reopens Mac-as-Bluetooth-HID feasibility; P16/P17 are required before a final control decision. No Accessibility/ADB control is allowed, and mirroring remains view-only until target-device evidence passes. **P02 is validated and accepted (2026-11-09)**: picker approval, approved-only rediscovery, 60 s
sustained duplex, airplane-mode outage recovery, Android process recreation/reboot, and a real Mac
sleep/wake pass (Sleep 21:41:50, Wake 21:42:15; same-port reconnect with no picker). Mac
advertiser/cold restart is measured: a new Mac service instance requires one picker re-approval;
that is a documented product limitation for Phase 2. **Update (2026-11-26):** a Mac app restart no longer
costs anything visible when the phone app is already running - the phone noticed the peer was gone and
reconnected to the same stored pin about 12 s after the Mac came back (Mac killed 14:36:06, `CONNECTED`
14:36:18), with no picker and no tap. The picker re-appears only when the phone starts a new discovery
round (app start or `Find the Mac`), which is the request-triggered picker described above. Advertising and inbound require
`ACCESS_LOCAL_NETWORK`; the picker, approved-only discovery and outbound TCP do not. **P03** has
all six acceptance criteria met, including the USB-disconnected run. **P04** is verified on
Android 12 and Android 17.
Owner: Local Development Agent. Follow `PROTOTYPE_PLAN.md` P00–P17 and its per-prototype order/dependencies.
- Execute each applicable isolated prototype on the required platform/device.
- Record evidence header, exact steps, positive and negative results, logs and limitations.
- Determine GO / CONDITIONAL / NO-GO from measured evidence.
- Cloud agent may prepare/review plans and source research, but cannot execute or mark device prototypes complete.

Exit: every required prototype has an evidence-based report; blocked/untested items are explicitly identified; decisions and permissions are updated; commit pushed.

## Phase 2 — Shared protocol, discovery and pairing

**Status (2026-11-26): closed.** The path was verified end-to-end on the OnePlus Nord (AC2001,
Android 12) on 2026-11-22, and the last exit criterion - the same flow with USB physically
disconnected and both debugging modes off - was measured on the S24 Ultra (Android 17) on 2026-11-26
(see "Phase 2 exit" below). What was measured: discovery found the Mac at
`192.168.31.109:59649` with `pv=1` from the TXT record; a first pairing displayed the same code
(`537797`) on both screens and bound `PAIRING_CONFIRMED`/`AUTHENTICATED` only after it matched; the
authenticated session carried `~37` PING/PONG round trips with RTT 32–340 ms; a forced drop
reconnected exactly once (`RECONNECTING attempt=1 → CONNECTING → CONNECTED`) using the stored pin
with no second code prompt; and the Mac **refused** an unknown peer identity
(`PIN MISMATCH expected 4ba5… got 7eded5…`) rather than trusting it. Three real defects were found and
fixed by this run — a main-thread socket connect (`NetworkOnMainThreadException`), an inverted
"requires confirmation" flag that reported an unconfirmed peer as connected, and a reconnect path that
cancelled its own coroutine and doubled every retry. The Mac also published no TXT record at all until
this phase (ADR-046).

ADR-021 requires the `ACCESS_LOCAL_NETWORK` handling, picker UX and denied-state UX to exist before
Phase 2 closes, so all three are now implemented: a contextual runtime request on API 37 (never at
launch), the `DiscoveryRequest`/`FLAG_SHOW_PICKER` path that does not need the broad permission, and
a distinct "local network access is off" state so a refusal can never look like "no Mac nearby".
**All three are now measured at API 37** on the S24 Ultra (Android 17) on 2026-11-22: the permission
dialog was shown and allowed, the `FLAG_SHOW_PICKER` path (`flags=2`) presented the system picker,
approving `DroidBridge-P08-Mac` resolved it at `pv=1`, and a first pairing derived **`280928`** on both
screens and bound only after it matched (`PAIRING_CONFIRMED` / `AUTHENTICATED`). A forced drop
reconnected once on the stored pin with no code prompt. A new Mac service instance required one fresh
picker approval, which is the documented P02 limitation.

The run also overturned the permission design: with `ACCESS_LOCAL_NETWORK` explicitly **denied**
(`granted=false`), the picker still resolved the Mac and the session still connected with heartbeats.
The picker is therefore the real consent path and the broad permission is not required for it, so the
app no longer requests it at launch — it starts picker discovery directly and offers the runtime
request only as a user-initiated fallback. Finally, the session survived **Bluetooth switched off**
(`bluetooth_on=0`) with heartbeats continuing (n=5…7, RTT 80–294 ms), which is the "Bluetooth off" half
of the exit criterion.

**Phase 2 exit measured (2026-11-26).** S24 Ultra (`RZCY81TKBSV`, SM-S928B, Android 17) against the
Debug Mac app with **USB disconnected, USB debugging off, wireless debugging off and Bluetooth off** -
stated at each row, verified with `adb devices` (only the OnePlus was listed) and with Developer
options switched off on the phone. A first pairing derived `836 347` on both screens and bound only
after it matched; `authenticated=true` followed with heartbeats every 15 s. The Mac app was then killed
mid-session: the phone showed `reconnecting (attempt 1..7)` on its own and reconnected on the stored
pins once the Mac was relaunched and listening - `authenticated=true` at 16:40:58, no code prompt, no
user action on the phone. A first pairing with the phone confirming before the Mac also completed once
the receiver stopped refusing pre-confirmation frames (ADR-060: `replaying 1 frame(s) held before
confirmation` -> `AUTHENTICATED`). Evidence: the 2026-11-26 sweep entry in
`Docs/PROGRESS_LOCAL.md`.

- Android NSD picker/direct LAN strategy.
- macOS Bonjour discovery.
- Secure pairing.
- TLS session.
- reconnect/disconnect.

Exit: trusted Mac and Android exchange authenticated test messages over Wi-Fi with Bluetooth off and USB disconnected.

## Phase 3 — Clipboard MVP

**Status (2026-11-26): closed.** Text sync works in both directions, the rules that only work when both
sides implement them (origin, content hash, sensitive/concealed refusal, no persistence, refuse rather
than pretend) are measured, and the foreground/background limits are documented in
`Docs/prototypes/PHASE3-clipboard-sync.md`. The last open condition - a cable-out sweep with both
debugging modes disabled - was performed on 2026-11-26 as part of the v1 acceptance sweep.

- Mac pasteboard monitoring.
- Android manual/foreground receive.
- Android manual send.
- Mac pasteboard update.
- loop prevention, pause, deduplication, limits.
- no history by default.

Exit: text sync passes tests with documented foreground/background limits.

**Phase 3 exit measured (2026-11-26).** S24 Ultra (`RZCY81TKBSV`, SM-S928B, Android 17) against the
Debug Mac app with **USB disconnected, USB debugging off, wireless debugging off and Bluetooth off**
(verified with `adb devices`: only the OnePlus was listed; Developer options off on the phone). Both
directions were driven by hand - the owner copied and pasted, no ADB, no `pbcopy`/`pbpaste`, no screen
capture and no tool access to either clipboard. Phone -> Mac: `droidbridge-clip-test1` copied on the
phone, `Send phone clipboard`, the Mac's offer applied and pasted back as that exact text. Mac -> phone:
`droidbridge-clip-test-2` copied on the Mac, sent, `Put it on the clipboard` on the phone, pasted back as
that exact text. Evidence: the 2026-11-26 sweep entry in `Docs/PROGRESS_LOCAL.md` and the new section in
`Docs/prototypes/PHASE3-clipboard-sync.md`.


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
- Complete P16 BLE HID and P17 Classic HIDP experiments, using the S24 Ultra and independent implementations.
- No Accessibility, ADB input/data path, root, hidden/privileged APIs, or protected-surface bypass.
- Document exact supported keyboard/pointer behavior separately from arbitrary touch, clipboard, and mirroring.

Exit: evidence-based GO/CONDITIONAL/NO-GO for the exact supported input scope. Until then, mirroring remains view-only; do not relax constraints to obtain a passing result.

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

Status: **media audio shipped** (owner-approved 2026-11-26, ADR-057: AAC-LC over `AUDIO`/`AUDIO_FORMAT`,
measured 2026-11-26 on the S24 Ultra with matching phone/Mac RMS and measured silence for opted-out apps
and for `ALLOW_CAPTURE_BY_NONE`). **Call audio stays NO-GO**: cellular voice is not capturable and
`USAGE_VOICE_COMMUNICATION` measures as digital silence (ADR-045); no dialer role was requested and no
call recording exists. Open for this phase: the A/V offset is unmeasured and the OnePlus path is
untested. The `RECORD_AUDIO` denial path was measured on the S24 (prompt shown, `granted=false`, switch
turns itself off, screen-only share continues), and switching the audio toggle off sends no audio at all.

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

## Agent ownership and phase progression

- Cloud Research Agent: research, documentation, prototype planning and review of pushed work only. No product implementation, Android/macOS project edits, local builds or physical-device testing.
- Local Development Agent: all implementation, builds, automated tests, prototypes and physical-device evidence.
- Active branch is `development`; stable release branch is `master`. No force-push; no release merge without explicit approval.
- Agents work sequentially and sync the latest branch before starting. Cloud pushes its scoped documentation work before local implementation; local pushes implementation/evidence before cloud review.
- After a phase passes its exit criteria, the responsible agent updates records, commits, pushes, reports and automatically starts the next phase. Do not wait for routine approval, skip phases or combine phases.
- Stop only for a genuine blocker requiring owner input: hard constraint conflict, failed feasibility gate, new/expanded permission or sensitive capability, cost/entitlement/security/privacy trade-off, destructive action, or unresolved product/architecture decision.
- No device-specific prototype is complete without actual device evidence. Every relevant test records USB/debugging state. Final acceptance requires USB disconnected and USB/wireless debugging disabled.
