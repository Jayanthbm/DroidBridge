# P10 — Secure screen and screenshare-protection behaviour

**Status: in progress (2026-11-17 live-device follow-up).** Synthetic `FLAG_SECURE` and the Slice banking app are withheld from the mirror as required, and the password-field case has captured mirrored pixels showing mask dots. The 2026-11-12 notification-shade freeze remains historical evidence with no confirmed root cause; in one fresh 2026-11-17 run after receiver hardening, the shade was visible on the Mac and the session remained active. The public-version synthetic OTP rendered its redacted public text on the Mac. The private-only synthetic notification was posted but its content was not visible in the captured shade/mirror, so its privacy result remains **Awaiting Local Validation**.

> This report records evidence only. No attempt is made to bypass `FLAG_SECURE`, DRM, banking-app protections or protected surfaces (prohibited by AGENTS.md / security policy).

| Field | Value |
|---|---|
| Prototype | P10 — secure screen/protection in end-to-end mirrored output |
| Depends on | P01/P08 (P01 complete; P08 live mirroring working, P08 acceptance not fully closed) |
| Android device | Samsung Galaxy S24 Ultra, **SM-S928B** (model reported in session; Android 17 / API 37, One UI 9.0 per prior project baseline) |
| Mac | MacBook Air (M1, 2020), macOS 27.0.1, Xcode 27.2; rebuilt Debug `DroidBridge.app` (`--p08-mirror`) with live PTS/timebase fix (`8937f3d`) |
| Mirroring experiment app | `com.droidbridge.experiment.mirroring` debug build including synthetic FLAG_SECURE toggle (`899cc87`) |
| Commits (relevant) | `92bb313` (Android mirroring start off main thread), `accae04`/`8937f3d` (Mac live mirror + PTS rebase), `899cc87` (P10 FLAG_SECURE toggle) |
| ADB/debug state at log capture (2026-11-12) | `adb devices -l` showed `192.168.31.106:5555 device ... transport_id:2` (Wi-Fi ADB) only — **USB connected: no** per adb listing at capture time; **USB debugging: not determined from this host**; **wireless debugging/ADB over Wi-Fi: yes** (device reachable at `192.168.31.106:5555`). Earlier same-day sessions included USB/USB-debugging; record the authoritative state per test run. The sections added later in the day (11:18–11:26) ran in the same state: **USB not connected**, wireless ADB on `192.168.31.106:5555`, no USB debugging possible without a cable. |

## Evidence

### 1) Synthetic `FLAG_SECURE` window (own experiment app) — observed correct

**Goal:** with mirroring active, toggling `WindowManager.LayoutParams.FLAG_SECURE` on the mirroring experiment window should cause the mirrored view to show black/blank/unavailable for that protected window, and clearing it should restore the live image. No protection bypass.

**Implementation (scoped to experiment):** `MirroringActivity` sets/clears `FLAG_SECURE` on its window via a UI toggle and logs `P10 FLAG_SECURE test: ON/OFF` (`899cc87`).

**Session/log evidence (S24, `adb -s 192.168.31.106:5555 logcat -d -s P08Mirroring`, 2026-11-12):**
- Fresh pairing after clearing stale Mac pin: forgot pin for `DroidBridge-P08-Mac` (09:51:32.721), discover/resolve `192.168.31.109:59490`, handshake `TLSv1.3` `authenticated=false` code `609218` (09:51:36.827), pairing confirmed and pin persisted (09:51:45.354), `peerPin=3b3d878a9b77bf4436d139b36a032a07ac3c5bdcd569fd77971e1384f7578fb7`.
- Mirroring started: consent requested/granted (09:51:53.003 / 09:51:56.156), service started (09:51:56.432), pipeline `590x1280` from content `1080x2340` encoder `c2.qti.avc.encoder`, `mirror.format` sent (09:51:56.517). Session remained active with `dropped=0`, `stopReason=active` (sampled frames 186→339, 09:52:01–09:52:26).
- **09:51:59.219** `P10 FLAG_SECURE test: ON (this window should appear black/blank in the mirror)`
- **09:52:01.307** `P10 FLAG_SECURE test: OFF`

**User-observed Mac result (2026-11-12):** when the toggle was turned **ON**, the Mac mirror showed a **black screen**; when turned **OFF**, the screen/image was visible again. This matches the expected protected-content behavior (FLAG_SECURE honoured in capture/mirror; no bypass).

**Result:** **Pass (synthetic FLAG_SECURE toggle, this run).** Protected window content was not shown in the mirror while secure; normal mirroring restored when cleared.

### 2) Banking app (Slice) — non-invasive observation, protected output observed

**Setup:** live mirroring active to Mac; user opened the Slice banking app on the S24 (non-invasive observation only — no interaction, no bypass attempt).

**User-observed Mac result (2026-11-12):** Mac mirror showed a **black screen** while Slice content was in the foreground.

**Result:** **Consistent with expected protected behavior** (protected/secure surface withheld from capture/mirror). Detailed session timing/log correlation for this specific observation was not captured in the log excerpt above; treat as a user-reported non-invasive observation pending a fully correlated evidence run if needed.

### 3) Historical notification-shade freeze — 2026-11-12 (not reproduced in the 2026-11-17 run)

**Setup:** live session (`590x1280` from `1080x2340` rendering correctly — the same session had just
shown the password case below), then the shade opened with a down-swipe from the status bar.

**Observed on the phone:** the quick-settings panel was open (`mCurrentFocus=Window{…
NotificationShade}`, `isKeyguardShowing=false`, `exec-out screencap` shows the QS panel).

**Observed in the mirrored video:** the Mac kept displaying the frame from *before* the shade opened.
Re-capturing the window region at 11:52, 11:53 and after collapsing the shade all returned the same
frozen frame with the phone's screen obviously changing, so this is not a lag artefact.

**Then the session was closed by the receiver (historical 2026-11-12 runs):** the Mac's guard ended it twice, with

```
CLOSED framing/protocol violation: peer exceeded 120 control frames per second
state=stopped received=2840 enqueued=2840 dropped=0
```

while the phone's last stats line stayed at `frames=1240 … keyframes=21 fps=10.3 dropped=0` and the
final teardown recorded `frames=1551 keyframes=26` (`receive stopped: SessionException: peer closed
the connection`). Frames kept being counted by the receiver while the picture never advanced. The
historical report labels the budget a control-frame budget; later code inspection showed media is
classified separately, so that old diagnosis is not reliable and must not be treated as a confirmed
root cause.

**Verdict (historical run): not a pass and not a P10 security result.** Nothing protected was
leaked (the shield is the opposite problem: the new content never reaches the mirror). The
"shade is ordinary system UI and mirrors normally" statement this document made earlier is
**withdrawn**: it was inferred from `mCurrentFocus` plus a rising `frames=` counter, not from watching
the picture. Watching the picture showed a freeze.

Reproduction on 2026-11-12: session #1 froze at `frames=1240` and closed at 1550; session #2 (fresh
pairing, same app run) froze and closed the same way. Both historical log lines named the same
rate-limit message, but code review found that the logged `control` classification does not match the
current media/control accounting; the old error text is preserved here as evidence, not accepted as a
reliable root-cause diagnosis.

**Lock, for contrast:** locking the device revokes the projection outright (`KEYCODE_SLEEP`,
`isKeyguardShowing=true` → `system onStop`, Mac `MIRROR_STOP`), so a lock screen leaves nothing to
mirror at all — recorded in full in the [P08 report](P08-live-mirroring.md#5-session-stops-when-mediaprojection-stops-u10).

### 4) Synthetic OTP notifications, with and without `setPublicVersion()` — partial visual result

Both experiment controls were exercised on the live 2026-11-17 session using only the experiment's
fixed synthetic test token; no real OTP was used. `dumpsys notification --noredact` confirmed both
records:

- **With public version** (`id=102`): `publicVersion` is present. Its public title/text are
  `P10 notification` / `Synthetic notification (no code shown)`; the private payload contains the
  synthetic test string. The phone shade and Mac mirror visibly showed the redacted public text and
  not the synthetic code in the captured view.
- **Without public version** (`id=101`): the record has no `publicVersion` field and its private
  payload contains the same synthetic test string. The notification was posted again for an
  isolated observation, but the captured phone shade did not show its content and the Mac capture
  showed the experiment controls rather than that notification. This is **inconclusive**: it does
  not establish that the private text was hidden by Android or by DroidBridge, nor that the two
  displays were showing the same frame at capture time.
- App logs confirm `P10 synthetic OTP notification posted publicVersion=true/false`; notification
  records, captures and per-run observations are summarized below. No real notification was
  dismissed or cleared, and no settings/app data were changed.

Therefore the public-version redaction is visually observed for this run. The private-only case
remains **Awaiting Local Validation**; do not generalize the public-version result to notifications
without a public version.

### 5) Password field — **pass, with the mirrored pixels captured**

The password test screen is a real `EditText` with `password="true"` (`uiautomator` dump); the field
was focused and `PELICAN-7731` typed through the input system while the session was live
(`state=mirroring 590x1280 … dropped=0`). The mirrored frame was then captured from the Mac window:

![Mirrored password field showing mask dots](evidence/p10/password-mirrored.png)

The mirrored field shows eight mask dots, not `PELICAN-7731`, so password input is not readable in the
mirror. This is the captured counter-example to the earlier "verdict pending" note: it needed
`--p08-allow-capture` first, because `AVSampleBufferDisplayLayer.preventsCapture` is on by default and
made every `screencapture` of the video area black — the black frames were the capture being blocked,
not the stream being empty.

**Caveat that matters for honest reading:** this proves what the *decoder output* looks like, which is
what a viewer sees on this Mac. It does not prove anything about other capture sinks.

### 6) No protected frames cached in app storage

`run-as com.droidbridge.experiment.mirroring` after the runs: `files/` contains only
`profileInstalled` (24 bytes) and `cache/` is empty. No frame, screenshot or buffer file exists in
the app's sandbox, so nothing from the capture path is persisted by this app. (This covers the app's
own storage; it does not audit other processes or the system's own buffers.)

## 7) 2026-11-17 live-device follow-up and test state

- **Device:** Samsung Galaxy S24 Ultra, `SM-S928B` / `RZCY81TKBSV`, Android 17 / API 37,
  build `CP2A.260605.016.S928BXXS6EZJ1`; MacBook Air M1, macOS 27.2.1. Both apps were Debug
  builds on the same Wi-Fi LAN. Android log confirms the prior saved peer pin was forgotten before
  first pairing, then the owner compared/confirmed the new pairing code and granted MediaProjection
  consent before the capture service started. I did not invoke the forget action or confirm pairing.
- **USB connected:** yes. **USB debugging:** enabled (`adb_enabled=1`). **Wireless debugging:**
  enabled (`adb_wifi_enabled=1`); ADB transport used in this run was USB.
- Shade opened with an ordinary swipe; the phone showed Quick Settings/notifications, and the Mac
  mirror showed the same shade. The mirroring foreground service and MediaProjection remained
  active; sampled Android stats had `dropped=0`, `stopReason=active`, and the receiver was streaming.
  Dismissing the shade returned the phone to `MirroringActivity`; the service/projection remained
  active and the Mac image advanced. **No freeze or session closure was observed in this run.**
  This does not erase the 2026-11-12 historical failure or establish its root cause.
- The synthetic public-version notification's redacted text was visible on the phone and Mac as
  described in section 4. The private-only notification was posted and exists in Android's
  notification service, but its content was not visible in the captured view; that case is
  inconclusive and remains open.
- The session was left running. The phone stayed USB-connected with both debugging settings enabled.
  No rotation, debugging-disabled acceptance run, glass-to-glass latency, CPU/GPU, battery, or
  thermal measurement was performed. No device settings or app data were cleared. The pairing flow
  logged that the prior saved peer pin was forgotten and the new pin persisted after owner-confirmed
  pairing; no unrelated notification data was changed.

## 8) Later owner-reported run (2026-11-17; inconclusive)

In a later unplugged attempt with Developer options disabled, the owner could not see the synthetic
notification in the shade for a direct phone/Mac comparison. They subsequently observed the
notification contents hidden on the phone as well. The private-only notification's mirrored privacy
outcome therefore remains inconclusive; do not interpret the absence of visible content in that
attempt as proof of either redaction or a mirroring failure. The same later session's shade/mirror
attempt ended after mirroring stopped, but the stop cause was not diagnosed. This is separate from
the earlier controlled shade run in which the shade appeared on both displays and mirroring remained
active through dismissal.

## Not yet tested (P10 remaining)

- **Private-only synthetic OTP visual outcome:** AWAITING LOCAL VALIDATION. The record exists, but
  its content was not visible in the captured shade/mirror; no protection conclusion is made.
- Additional banking/DRM apps by **non-invasive observation only** (no interaction, no bypass).
- Rotation UI restoration on the target device, and the separate P08 final debugging-disabled
  acceptance/performance measurements remain open in the [P08 report](P08-live-mirroring.md).

## Running the cases

Mirroring must be live (Mac `P08 Mirror` listening, phone connected and capturing) before each case.
The steps below are the reproduction procedure. The shade and public-version case were observed on
2026-11-17; the private-only notification remains **Awaiting Local Validation**. **Capture the Mac
window with `--p08-allow-capture`**, otherwise the video area photographs as black (the layer sets
`preventsCapture`):

1. **Password field** — tap *P10: Open password-field test screen*, focus the field, type throwaway
   text, watch the Mac. Back to the controls with *Back to mirroring controls*.
2. **OTP notification, no public version** — tap *P10: Post OTP notification (no public version)* and
   read the Mac (shade open and closed).
3. **OTP notification, with public version** — tap *P10: Post OTP notification (with public version)*
   and compare. The public version carries no code, so if the mirror shows a redacted form that is
   the expected result; if it shows the code that is a finding to record.
4. **Lock screen / shade** — lock the device and pull the shade down mid-session; record whether the
   stream stops (P01 showed lock fires `onStop()`), goes black, or shows unprotected content.

Record the Mac observation, `adb logcat -s P08Mirroring P08Encoder` around each step, and the
USB/debugging state for the run.

### Capturing the mirrored video as evidence

The Mac app sets `layer.preventsCapture = true`, so `screencapture` photographs the video area as solid
black even while frames decode — that is what made the first P10 attempts look like "the mirror shows
nothing". Start the app with `--p08-allow-capture` for evidence runs:

```
open -n macos/build/Build/Products/Debug/DroidBridge.app \
  --args --p08-mirror --p08-allow-capture
screencapture -x -R1076,140,590,330 out.png   # the window's video region
```

The flag only flips `preventsCapture`; nothing else in the render or capture path changes, and it is
off unless the argument is passed.

## Limitations

- Mac-side visual outcome for the synthetic toggle is user-confirmed; not independently screenshot-captured by this tooling.
- ADB state recorded here reflects the host view at capture time (Wi-Fi ADB reachable); per-run USB/debugging state must still be recorded explicitly for each acceptance-relevant test.
- Banking-app observation is non-invasive and user-reported; no protected content was inspected, extracted, or bypassed.

## Measurement harness (built 2026-11-21, live matrix not yet run)

Judging "is protected content withheld?" from encoder byte counts alone is unreliable: a protected
surface compresses to ~650–1000 B/frame, but so does a static, sparse screen such as the password
screen (measured 1119 B/frame unprotected). The number only distinguishes the two when the screen has
motion or high entropy. Two additions remove that ambiguity:

1. **Per-frame mean luma.** With `P08_DUMP_DIR` set, the Mac receiver runs a second
   `VTDecompressionSession` over the same samples, reports `LUMA f=<n> l=<value>` per decoded frame and
   writes periodic PNGs. A withheld surface reads luma ≈ 0–5; live content reads tens to hundreds, on
   any content including static screens. PNGs land in the dump directory as inspectable artefacts.
2. **Intent-driven matrix.** The mirroring experiment accepts `p10Secure`, `p10PasswordScreen` and
   `p10Notification` (`otp-private` / `otp-public` / `cancel`) extras, so phases can be driven by
   launching the activity. ADB may launch test apps; it may not inject touch, and the screen-capture
   consent dialog is answered by the user, not generated by the agent.
   `am start -f 0x20000000` (`FLAG_ACTIVITY_SINGLE_TOP`) is required or the extras never reach a
   running activity.

Also found while setting this up: the Mac receiver was being ended by AppKit automatic termination
(`_kLSApplicationWouldBeTerminatedByTALKey`) between phases, which is indistinguishable from a protocol
failure. `--p08-mirror`, `--advertise-on-launch` and `--listen` now hold automatic and sudden
termination for the life of the process.

Preliminary numeric result from the aborted run, retained only as a pointer for the live matrix: with
the synthetic `FLAG_SECURE` window visible, per-frame bytes fell from **7894 B/frame to 638–1017
B/frame** and stayed suppressed for the whole protected period (`dropped=0` throughout). That is
consistent with the surface being withheld, but it is byte-count evidence with the confound above, so
it must be re-measured with luma before it is cited as the P10 result.

### Mac-side startup hazards found while driving the run through ADB (2026-11-21)

Three non-protocol failures each presented as a broken mirroring session:

1. **The receiver advertises but does not accept.** The P02 advertiser publishes the name; the P08
   tab's listener only starts when its button is pressed. A phone connecting to the advertised name
   with no listener gets `SSLHandshakeException: UNEXPECTED_MESSAGE`. Fixed for unattended runs with
   `--p08-listen`.
2. **The listener drops a quiet session after 120 s**, which is shorter than a consent prompt plus a
   screen-off check, so a measured pause looks like `receive failed: SessionException` on the phone.
   `--p08-idle-timeout <seconds>` now overrides it.
3. **A per-build Keychain ACL prompt blocks startup silently.** `securityd` logs
   `displaying keychain prompt ... ACL ... cdhash H"..."`, listing the cdhashes of earlier builds; the
   app waits with no window and no output. Ad-hoc signing makes this happen on every rebuild. Signing
   the bundle with the stable Apple Development identity, after clearing the stale `com.droidbridge.identity`
   and `com.droidbridge.p08.peer-pins` items, keeps the ACL valid across rebuilds.

Also: an open notification shade makes `uiautomator` report `ERROR: could not get idle state` and
silently swallows taps; `cmd statusbar collapse` resolves it. The charged-phone state meant
`uiautomator dump` returned only on-screen nodes, so a control below the fold needs a scroll first.

### Luma sink status (2026-11-21, end of run)

The evidence sink now starts and decodes: `DUMP enabled` → `DUMP session created` →
`LUMA f=1 l=160.1`. It then stops producing frames, and three config changes did not fix it
(keyframe request on session creation, logging the synchronous submit status, IOSurface-backed
output attributes). Nothing reports an error: submission returns `noErr`, the failure callback never
fires, and only the first frame is ever measured.

Two things were learned that matter beyond the sink:

- **The encoder only sends frames when the screen changes.** A static screen produces no samples at
  all, so "no LUMA" can mean "nothing moved", not "the sink is broken". Any phase must include motion.
- **Phone-side bytes/frame is now measured under motion:** 16,398 B/frame with continuous scrolling.
  That is the number a FLAG_SECURE phase has to be compared against; the earlier 6,500–7,900 B/frame
  static-screen figures are not comparable.

Until the sink decodes continuously, the FLAG_SECURE row has no decisive number and P10 stays
in progress. The measurement must come from luma, not byte counts: a blacked-out protected surface
and an unchanging harmless screen are indistinguishable by size.

### Four defects behind the silent sink (2026-11-21, resolved except the last)

Each one hid the next, and every one presented as "no evidence" rather than an error:

1. **os_log throttling.** Per-frame `log.info` lines are dropped at stream rates, so a working sink
   looked dead. Luma now reports every 15th frame (~2/s).
2. **The renderer gate.** Evidence was recorded after the display-layer readiness check, so when the
   layer stopped draining the measurement silently ended. Evidence now runs first.
3. **The validator stall — a real product bug.** `streamValidator` returning `.waitForRecoveryIDR`
   dropped every frame *and nothing asked for the keyframe it was waiting for*, so one PTS anomaly
   stalled the session for good. `requestKeyframeIfNeeded(reason: "validator-recovery")` now recovers
   it. This would have affected a user-visible mirror, not just measurement.
4. **Still open: the main-actor dispatch stops after ~15–20 media messages.** The listener keeps
   accepting frames (699 → 733 observed) while the receiver's handler stops being invoked, so submits
   end around n=15. Media handling is delivered as one `Task { @MainActor }` per frame; the likely fix
   is to handle media on a dedicated serial queue rather than the main actor. Until then the sink
   samples only the first moments of a session.

Practical consequence: the FLAG_SECURE row still has no decisive number, and P10 remains in progress.

## Measured evidence — 2026-11-21, S24 Ultra (Android 17 / API 37)

Method: the receiver records every received AVCC frame plus a wall-clock timeline
(`P08_RECORD_DIR`), the phases are tagged with wall-clock windows, and the recording is decoded
**offline** afterwards (`ffmpeg` → 64×128 gray → mean luma per frame). Offline decoding is deliberate:
a second live `VTDecompressionSession` deadlocks (`RemoteVideoDecoder_DecodeFrameWithOptions` blocks in
a semaphore wait) and, when run inline, blocked the main thread and silenced the stream.

| Phase | Frames | Mean luma | Min | Max |
|---|---|---|---|---|
| Baseline, same screen, same motion | 645 | 196.4 | 195.2 | 197.9 |
| **`FLAG_SECURE` ON** | **809** | **1.0** | **0.9** | **1.1** |
| `FLAG_SECURE` OFF | 646 | 196.4 | 195.2 | 197.8 |
| Password-field screen (unprotected) | 46 | 238.1 | 238.1 | 238.1 |
| OTP notification, private version only | 493 | 80.3 | 66.7 | 87.4 |
| OTP notification, with public version | 510 | 80.5 | 73.9 | 87.2 |
| Notification shade open | 819 | 64.2 | 60.9 | 68.1 |
| Shade with private-only OTP | 838 | 114.4 | 112.5 | 115.5 |

What this establishes:

- **Protected surfaces are withheld, and withheld as black.** 809 frames were still delivered while
  the mirrored surface read luma 1.0 — the stream was live and the pixels were black, which is the
  distinction byte counts could never make (a static harmless screen also compresses to ~1 kB).
- **The control is exact.** The same screen with the same motion reads 196.4 before and after, so the
  collapse is attributable to `FLAG_SECURE` alone, not to content, motion or encoding.
- **The unprotected password screen mirrors normally** (238.1), consistent with it being an app-level
  protection of the field rather than a secure surface.
- **Private-vs-public notification redaction is not settled by this run**: 80.3 vs 80.5 while the shade
  is open on an unlocked device, where both variants render in full. The distinction only exists on the
  lock screen, so that row still needs a locked-device run.

Privacy note: the recording is a local artefact of a synthetic test surface and a throwaway OTP string;
no personal content was captured, and no device-side screen capture was used.

### Lock screen: no stream at all (measured, 2026-11-21)

The lock-screen redaction row resolves differently than expected. Tagging a locked-device window
produced zero frames, and the cause is recorded on the device:

```
MIRROR state=stopped: system onStop (lock / screen-sharing chip / user stop)
```

Locking **ends the MediaProjection session**. There is no mirror while the device is locked, so
lock-screen notification content — redacted or not — cannot be disclosed through this path. That
matches the earlier P01 finding and means the private-vs-public OTP distinction is unobservable here
rather than unresolved: with the shade open on an unlocked device both variants render in full
(80.3 vs 80.5 luma), and while locked there is nothing to render.

### Measurement build caveat

The recording harness needs to write outside the sandbox, so measurement runs sign with
`macos/App/DroidBridge.measurement.entitlements`, which adds one scoped
`temporary-exception.files.absolute-path.read-write` for `/private/tmp/p10-rec/`. Shipped and
acceptance builds keep using `DroidBridge.entitlements` unchanged (three keys, ADR-026). The recording
is a local artefact of a synthetic test surface and a throwaway OTP string.

## Current assessment

**In progress — core claim now measured.** Protected surfaces are withheld as required in every case
that has actually been observed, and the `FLAG_SECURE` case is quantified above (luma 1.0 with 809
frames flowing, against 196.4 for the identical unprotected screen) (synthetic `FLAG_SECURE`, the Slice banking app, the password field with captured pixels).
The notification shade mirrored and remained live in one fresh run; public-version notification
redaction was also observed. The private-only notification outcome is inconclusive and remains
**Awaiting Local Validation**. The historical shade freeze remains valid historical evidence but was
not reproduced in the 2026-11-17 run; its root cause and whether the receiver changes address it are
unknown. Do not generalize a single shade run or the public-version notification result. The lock
screen cannot leak in the tested P01 path because locking stops capture.
