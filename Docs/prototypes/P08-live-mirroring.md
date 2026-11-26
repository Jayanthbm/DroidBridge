# P08 — End-to-end mirroring stream (evidence)

**Status: acceptance run performed 2026-11-12 on the S24 Ultra against a rebuilt Debug Mac app.**
Rotation/re-encode, keyframe-request recovery, clean stop/teardown, screen-off and locked-screen
stops, the `KEY_REPEAT_PREVIOUS_FRAME_AFTER` idle-cost comparison and a 30-minute continuous session
are recorded with logs below. Two gaps remain and are stated explicitly at the end: the final
"all debuggings disabled" run, and glass-to-glass latency/device cost.

| Field | Value |
|---|---|
| Prototype | P08 — end-to-end mirroring over the ADR-012 transport (pinned TLS + length-prefixed H.264) |
| Android device | Samsung Galaxy S24 Ultra, **SM-S928B** (`e3q`/`e3qxins`), Android **17 / API 37**, build `CP2A.260605.016.S928BXXS6EZJ1`, One UI `90000` |
| Mac | macOS **27.2.1** (`26B131`), Apple silicon; Debug `DroidBridge.app` launched with `--p08-mirror` |
| Network | Both ends on the same Wi-Fi LAN; Mac `192.168.31.109`, phone `192.168.31.106` |
| Capture | `MediaProjection` whole-display, `1080x2340` content → `590x1280` (then `1280x590` after rotation), encoder `c2.qti.avc.encoder`, 4 Mbit/s, `KEY_I_FRAME_INTERVAL` + `KEY_REPEAT_PREVIOUS_FRAME_AFTER` |
| **USB connected** | **No** — `adb devices -l` listed only `192.168.31.106:5555` (transport_id 2, TCP); no USB transport was present for any run in this report |
| **USB debugging** | Not applicable for these runs (no cable attached); cannot be asserted off from this host |
| **Wireless debugging / ADB over Wi-Fi** | **Yes** for the 2026-11-12 scripted runs (required to drive `adb install`, `logcat`, `dumpsys` and `input`). **Off** for the final acceptance run (2026-11-26) - see "Final no-debugging acceptance" below |
| Commits landed during this run | `bd3f4b5`, `3246422` (P10 controls/docs), `29892b4` (receiver identity held per app run + scripted fault hook) |
| Earlier relevant commit | `8937f3d` (live PTS rebased onto a host clock `CMTimebase`) |

Pairing for every session in this report used the documented flow: the six-digit code was
compared on both screens by the owner and confirmed on both devices. No pin check was relaxed or
bypassed at any point.

## 1) Session start and steady state

Log: `adb -s 192.168.31.106:5555 logcat -d -s P08Mirroring`, Mac: `LiveMirrorReceiver` event list.

- Handshake after pairing: `handshake ok: protocol=TLSv1.3 peerPin=b8cae483… authenticated=false code=945476`, then `pairing confirmed: … pin persisted=true` (10:20:18).
- `requesting screen-capture consent` → `screen-capture consent granted` → `pipeline 590x1280 from content 1080x2340 encoder=c2.qti.avc.encoder` → `mirror.format MirrorFormat(590x1280, rotation=0, nalLengthSize=4) sps=18 pps=4` (10:21:25).
- Mac state went to `streaming` with `received == enqueued` and `dropped=0`.
- **Startup:** `mirroring started` 10:55:50.688 → Mac `FigVideoQueue` reported 43 frames enqueued in the window ending 10:55:51, i.e. the first frames reached the renderer within roughly a second of capture starting. A precise glass-to-glass figure is **not** claimed; it needs high-speed camera capture, which was not done.

## 2) Rotation mid-session (`onCapturedContentResize` → re-encode)

Forced with `settings put system user_rotation 1` then `0` (accelerometer rotation pinned off so the
rotation is deterministic).

- 10:12:53.936 `re-encode 590x1280 -> 1280x590`; 10:12:54.060 new `mirror.format MirrorFormat(1280x590 …)`; `captured content resized to 2340x1080`; stats then show `encode=1280x590 content=2340x1080 formats=2 reEncodes=1` and the session stays `stopReason=active`.
- Rotating back at 10:13:18.128: `re-encode 1280x590 -> 590x1280`, new `mirror.format`, `formats=3 reEncodes=2`, still `active`.
- **Mac side:** `MIRROR_FORMAT 1280x590` replaced the old description, `formats=2`, and `FigVideoQueueGMStats` kept reporting 27–75 frames enqueued per 6 s with `sbuf queue contains 0 frames`. No stall, no stuck black frame.
- **SPS/PPS:** the sender emitted a new `mirror.format` on each change, so `mirror.format` is a recurring message, not stream-start only (answers the U19 question in that direction). Both post-rotation parameter sets log as `sps=18 pps=4`, i.e. the same lengths as at stream start; byte equality was not dumped, so "identical bytes" is not claimed.
- **Observed rotation UI gap (2026-11-12):** rotation recreated `MirroringActivity` (`identity pin=…` re-logged) while `MirroringService` kept streaming. The earlier device run found the recreated UI lost its session handle and displayed idle. A process-owned `MirrorSessionHolder` reattachment fix is now implemented and covered by build/unit validation; it has **not yet been verified on the S24 Ultra**, so the rotation UI behavior remains Awaiting Local Validation.

## 3) Decoder flush → keyframe request → resume

The acceptance criterion asks for recovery to be demonstrated over the real transport, including
the keyframe request. Two paths were tried against the running receiver:

1. **Corrupt sample (planned path).** The Debug receiver takes `--p08-fault-after <seconds>` and enqueues one 4 KiB random AVCC payload. It fired at 10:22:05 (`FAULT_INJECTED method=corrupt-sample bytes=4096 afterSeconds=40.0`) but `AVSampleBufferDisplayLayer.status` stayed `rendering` — CoreMedia discarded the payload without reporting failure. This is recorded as a **limitation of the injection**, not as a pass.
2. **Scripted flush fallback.** Because the renderer stayed healthy, the same receiver drove the identical recovery branch one second later: `FAULT_FALLBACK scripted-flush status=rendering` → `renderer.flush()` → `KEYFRAME_REQUEST reason=scripted-fault count=1`.
   - Phone: `keyframe requested by the Mac; requesting an IDR` (10:22:15 area), `keyframeRequests=1`, `keyframes` 5 → 6, and the session continued with `dropped=0`, `stopReason=active`.
   - Mac: `state=streaming received=302 enqueued=302 dropped=0`.

So the **request/IDR/resume round trip is proven end to end**; the "renderer reaches `.failed` from
a corrupt sample" precondition is not reproduced by this injection and remains unproven.

Separately, the explicit **Request keyframe** control was exercised on a healthy stream at
10:13:26: `keyframe requested by the Mac; requesting an IDR`, `keyframeRequests=1`, `keyframes`
13 → 14, stream uninterrupted.

## 4) Stop and teardown

- Phone **Stop mirroring** at 10:13:45: `MIRROR state=stopped: user stop … dropped=0`, then `teardown complete reason=user stop`; `dumpsys media_projection` → `null`; `dumpsys activity services com.droidbridge.experiment.mirroring` → *nothing*.
- Mac: `MIRROR_STOP reason=user stop`, `state=connected` (transport kept), frames stop arriving.
- Repeat at 10:56:35 (`stopReason=user stop`, service gone, projection `null`).
- **Observed lifecycle behaviour (documented, not a defect to fix here):** the receiver is one session per listener; after a stop it stops accepting and closes the transport after `idleTimeout` 120 s (`event: CLOSED idle timeout after 120s`). Restarting therefore needs a fresh **Listen** on the Mac. Before `29892b4` that also invalidated the phone's stored pin, because each Listen minted a new Mac identity.

## 5) Session stops when MediaProjection stops (U10)

- Screen off at 10:04:44 (screen timeout during an unattended run): window manager showed `isSleeping=true` and projection `onStop` followed immediately — `MIRROR state=stopped: system onStop (lock / screen-sharing chip / user stop)`.
- Same stop path at the end of the stability run, 10:52:08, after 30 m 43 s.
- **Locked-screen (keyguard) run, 11:14:48:** with a live session, `input keyevent KEYCODE_SLEEP` locked the device — `dumpsys window` then reported `isKeyguardShowing=true`, `mDreamingLockscreen=true` — and the projection was revoked: `projection onStop`, `MIRROR state=stopped: system onStop (lock / screen-sharing chip / user stop)` at `durationMs=139182`. The Mac received `MIRROR_STOP reason=system onStop (lock / screen-sharing chip / user stop)` and went to `state=stopped`.
- Teardown for that run was confirmed from state rather than from the log line: `dumpsys media_projection` → `Media Projection:` (empty), `dumpsys activity services` → `(nothing)`, and the encoder released (`CCodec: [c2.qti.avc.encoder] state->set(STOPPING)`, `GraphicBufferSource: released unpopulated slots`). The app process was then killed (`Process: Sending signal. PID … SIG: 9`), which is why no `teardown complete reason=system onStop` line reached the buffer — that line is only emitted when the stop path finishes before the process is reaped.

## 6) 30-minute stability

Continuous session 10:21:25 → 10:52:08 (**30 m 43 s**), static screen, same Wi-Fi, whole-display capture.
Sampled every ~60 s (22 samples, `/tmp/p08-stability.log` during the run):

| Metric | Result |
|---|---|
| Samples with `dropped != 0` | 0 (every sample `dropped=0`) |
| Mac `state` | `streaming` on 20/21 live samples, `stopped` on the final one |
| Mac `received` vs `enqueued` | equal at every sample (e.g. 9118/9118 at the end) |
| Mac render queue depth | `sbuf queue contains 0 frames` on all 14 queue probes |
| Frame rate | 4.6–5.1 fps on a static screen |
| Bitrate | 244–321 kbit/s on a static screen, 4 Mbit/s configured cap |
| Keyframes | 1 → 153 over the run (`I_FRAME_INTERVAL`) |
| Final counters | `frames=9118 bytes=72477748 dropped=0 maxSendMs=7 durationMs=1804992` |

The low frame rate and sub-300 kbit/s bitrate on an idle screen are the measured effect of
`KEY_REPEAT_PREVIOUS_FRAME_AFTER` (repeat last frame instead of a free-running 30 fps).

### 6a) Idle cost with and without `KEY_REPEAT_PREVIOUS_FRAME_AFTER`

Both runs used the same static screen (the launcher home screen reached with `KEYCODE_HOME`, so the
experiment app's own status text was not repainting) and were measured by differencing consecutive
stats lines, not by reading the running average.

| Configuration | Window | Frames | Bytes | Frame rate | Bitrate | Keyframes |
|---|---|---|---|---|---|---|
| `KEY_REPEAT_PREVIOUS_FRAME_AFTER=250 ms` | 105.0 s (`durationMs` 20 129 → 125 154) | 392 | 4 484 408 | **3.7 fps** | **342 kbit/s** | 5 → 12 |
| key omitted (repeat off) | 100.0 s (`durationMs` 20 133 → 120 164) | 39 | 1 282 276 | **0.39 fps** | **103 kbit/s** | 4 → 5 |

The comparison is run through an experiment-only switch: launch the activity with
`--ez omitRepeatFrame true` and the service omits the key for that session, logging
`repeat-previous-frame=off` (default logs `repeat-previous-frame=250000`). Nothing else changes.

Reading: repeating the last frame costs about **3.3x the bitrate and 9.5x the frames** of a static
screen. It is also what keeps keyframes flowing — with the key omitted, keyframe counters barely
advance (4 → 5 in 100 s) because nothing is encoded at all, so a receiver that flushes during a
still period waits for the next screen change. Keeping the key is the right default for a viewer;
the measured cost is the number to watch on battery.

Caveat: the 30-minute run above measured 4.6–5.1 fps and 244–321 kbit/s, but its visible screen was
the experiment app, whose status label repaints — close to, but not identical with, the 3.7 fps /
342 kbit/s static-screen figure here.

Not measured, and not claimed: end-to-end latency in milliseconds, CPU/GPU cost, battery drain,
thermal behaviour, reconnect time after a deliberate link drop.

## 7) Defects found and fixed during this run

- **Mac identity regenerated on every Listen** (`29892b4`). `startListening()` called `MacIdentity.generate()` after `stop()`, so each Listen changed this Mac's pin. Every session restart then required Forget stored pin plus a fresh six-digit comparison, and reconnect recovery could not be exercised at all. The receiver now holds one identity for the life of the app run (persistent identity storage remains P15's scope).
  - Verified: two consecutive Listen presses reported the same `identity-pin=b8cae483…`, and after **Close session** a fresh Connect against a new listener port returned `handshake ok … authenticated=true` using the stored pin, with no Forget and no re-pair.
- **Scripted fault hook** (`29892b4`): `--p08-fault-after <seconds>` for the recovery case, inert unless passed.
- Cosmetic: the `LISTENING` event logs `port=0` because the port is read before the listener is ready; the real port appears in the following `EVENT listening on port …` line.

## Receiver/session hardening update (2026-11-17)

Automated changes now cover protocol and lifecycle edge cases found during review:

- The Mac receive limiter uses monotonic uptime and a bounded token bucket, retaining the configured sustained media/control rates while admitting only a finite burst. Media frames are no longer individually logged; accepted media totals are summarized once per second. This avoids both wall-clock discontinuity and per-frame logging cost without removing authentication, replay, framing, or sustained-rate checks.
- The Mac UI publishes frame counters every 30 received frames while retaining exact internal totals for logs, so SwiftUI does not re-render synchronously for every video frame.
- Video payload validation now reports IDR status, validates signed timestamp decoding without integer overflow, validates AVCC NAL boundaries/header classes, and the stream validator enforces non-negative strictly increasing PTS plus IDR after stream start/format change. A new encoder/format epoch resets PTS ordering; after decoder flush, non-IDR frames are dropped until recovery IDR. Protocol-invalid stream data closes the session; local sample-buffer construction failures instead request decoder recovery.
- The Android sender also drops non-increasing PTS within each format epoch. Rotation resets that epoch as the new encoder is published.
- Activity recreation reattaches to the process-held authenticated session instead of reporting an idle/disconnected UI while the service continues. This remains a prototype process-lifetime holder, not a production session ownership architecture.

MacOS unit suite: **65 passed, 0 failed** (macOS 27.2.1, arm64). Android mirroring suite: **22 passed, 0 failed**; Debug APK assembled. These are automated results only. The initial 2026-11-17 S24 Ultra check was over USB (`RZCY81TKBSV`, SM-S928B, Android 17/API 37, build `CP2A.260605.016.S928BXXS6EZJ1`); `adb_enabled=1`, `adb_wifi_enabled=1`, no DroidBridge listener/service was running, and Outlook was foreground. A later, separate live-device validation run is recorded below.

**Notification-shade reproduction after the receiver hardening: reproduced without a freeze or session closure in one 2026-11-17 run.** This is evidence for that run only; the historical root cause remains unconfirmed. Real-device rotation/session restoration is still **AWAITING LOCAL VALIDATION**. The rate-limit/UI-path changes remain a source-level mitigation hypothesis, not proof of the historical shade failure's cause or a general fix.

## 8) 2026-11-17 live-device follow-up

Samsung Galaxy S24 Ultra **SM-S928B** (`RZCY81TKBSV`), Android **17 / API 37**, build
`CP2A.260605.016.S928BXXS6EZJ1`; MacBook Air M1, macOS **27.2.1**. The Debug phone app and
`DroidBridge.app --p08-mirror --p08-allow-capture` were used on the same Wi-Fi LAN. The phone's
log records owner-confirmed pairing (TLS 1.3, new peer pin persisted) and MediaProjection consent
granted before capture. No real OTP was used; notification checks used only
the experiment's fixed synthetic string. No device settings or unrelated notifications were changed.

**USB/debugging state for this run:** USB connected **yes**; USB debugging **enabled**
(`adb_enabled=1`); wireless debugging **enabled** (`adb_wifi_enabled=1`); ADB itself was connected
through the USB transport. This is development evidence, not the required debugging-disabled P08
acceptance run.

- Live session: `590x1280` H.264 from `1080x2340` display content, encoder `c2.qti.avc.encoder`.
  MediaProjection display 32 and the mirroring foreground service were present throughout the
  checks. Android sampled stats show `stopReason=active`, `dropped=0`; at the shade screenshot the
  Mac receiver showed `state=streaming` and `received=5160 enqueued=5159 dropped=0`.
- Notification shade: opening Quick Settings/notifications visibly changed the phone display and
  the same shade appeared in the Mac mirror. After the shade was dismissed, the phone returned to
  `MirroringActivity`, MediaProjection/service remained active and the Mac image advanced to the
  experiment controls. **No freeze or session closure was observed in this run.** This does not
  disprove the historical freeze or establish its root cause. During the much longer follow-up
  session the Mac receiver later accumulated 93 dropped/enqueue differences; do not attribute those
  later totals to the short shade reproduction.
- No rotation was induced in this run; device-side activity/session restoration after rotation is
  still untested here.
- The mirroring session was left active at the end of validation. No final USB-disconnected /
  USB-debugging-disabled / wireless-debugging-disabled run, glass-to-glass latency measurement or
  device-cost measurement was performed.

## Remaining P08 gaps

1. **Final acceptance run with USB disconnected and USB + wireless debugging disabled.** Not run in this update; remains Awaiting Local Validation.
2. **Glass-to-glass latency, CPU/GPU, battery, thermal** not instrumented.
3. **Corrupt-sample precondition for decoder failure** did not reproduce; the request/IDR/resume branch is proven via scripted flush and explicit keyframe control, not renderer failure from corrupt sample.
4. **Rotation UI reattachment** implemented, but device test remains Awaiting Local Validation.

P08 is therefore **not** marked complete: the stream itself meets the measured criteria, but item 1
is acceptance evidence that has not been captured, and item 2 is not measured at all.

### Side observation while scripting these runs

The 120 s idle deadline is easy to trip with a script: a session that authenticated and then sat
idle during a consent dialog had been closed by the receiver, so `start` failed with
`send failed: ConnectionResetException` / `stopReason=start failed` when the capture started. The
failure is loud and correct (no stale socket was presented as live), but it means any automated run
must open the session and start the capture inside two minutes.

## 9) Later owner-reported observations (2026-11-17; not instrumented)

The owner subsequently reported that, with the S24 unplugged and Developer options disabled, rotating
the phone through all directions left it showing connected/mirroring while the Mac mirror continued
to update. This is user-reported evidence only: no captured logs or measurements establish UI
reattachment, exact rotation count, or uninterrupted duration, so it does not close the rotation
acceptance item.

The owner also reported that mirroring stopped after the phone locked. The cause and exact trigger
were not diagnosed in that attempt. This is consistent with earlier observed MediaProjection stop
on lock but does not establish a new controlled reproduction. A later unattended stability attempt
also ended after the phone locked; no successful 30-minute awake stability run is established from
these later attempts. Do not leave the phone unlocked unattended to complete one.

## Matrix rows measured 2026-11-21 (S24 Ultra, Android 17 / API 37)

| Row | Result |
|---|---|
| Keyframe recovery | **Pass** — `FAULT_INJECTED method=corrupt-sample bytes=4096 afterSeconds=8.0` → `FAULT_FALLBACK scripted-flush` → `KEYFRAME_REQUEST reason=scripted-fault count=1` → stream continued at `status=rendering` with `dropped=0` |
| 30-minute continuous session | **Pass for endurance** — 8496 frames / 49.0 MB, `dropped=0`, `keyframeRequests=0`, `status=rendering` throughout; ended at 29 min 48 s on `system onStop (lock / screen-sharing chip / user stop)` because the screen timeout fired |
| Rotation | **Pass — measured 2026-11-22, no human needed.** Portrait `590x1280` (content `1080x2340`) → landscape `1280x590` (content `2340x1080`) → back to `590x1280`, all mid-session on one PID. `mirror.format` is re-emitted on every change (`MirrorFormat(1280x590…)` at frame 114, `MirrorFormat(590x1280…)` at frame 187 on the receiver) and the stream keeps running: 600 frames received/enqueued, `dropped=0`, `keyframeRequests=0`, `status=rendering`, no exception. The earlier "blocked" note was a **wrong method**, not a device limitation: `settings put system user_rotation` and `cmd window user-rotation lock 1` do nothing on this build until `cmd window fixed-to-user-rotation -d 0 enabled` has been set. With that, `cmd window user-rotation -d 0 lock {0,1}` rotates deterministically in both directions. |

Two operational findings worth keeping:

- **A sleeping screen ends the projection.** `svc power stayon usb` was set and the screen still timed out at the configured 30 minutes, which stopped capture. Any long endurance run must hold the screen awake, and the row's failure mode (`system onStop`) must not be mistaken for a transport fault.
- **The phone's window manager can wedge.** Mid-run, focus stuck on a stale `NotificationShade` window, `uiautomator` returned no hierarchy and every tap missed. A reboot recovered it; taps against a wedged WM look exactly like a broken app.

## Matrix rows measured 2026-11-24 (S24 Ultra, Android 17 / API 37; USB connected, USB debugging on)

Run conditions: S24 Ultra `RZCY81TKBSV`, phone pins intact from the 2026-11-23 pairing, Mac listener on fixed
port 59695, USB connected and USB debugging enabled for the whole run (the final acceptance sweep still needs
USB out and debugging off). ADB drove setup only: launching the app, choosing the Mac in the picker, tapping
`Share this screen`, and selecting **Share entire screen** in the capture dialog. No ADB input went into the
measured stream, and no screen capture or clipboard read was performed.

| Row | Result |
|---|---|
| 30-minute continuous session | **Pass** — capture started 07:34:10, last sample 08:06:47 (32 min 56 s of capture, uninterrupted). Receiver: `received=10800 enqueued=10800 dropped=0 status=rendering`, `keyframeRequests=0`, zero closes in the whole run. Phone: `frames=10885 bytes=57106207 keyframes=182 fps=5.5 kbps=231 dropped=0 formats=2 keyframeRequests=0 reEncodes=0 largestFrame=201133 maxSendMs=9 durationMs=1976212 stopReason=active`. Nothing was tapped or touched during the window; the screen was held awake with `svc power stayon true` and `screen_off_timeout=3600000`. |
| Reconnect re-announce through the product path | **Pass** — listener killed at 08:07:16 with the stream live. Phone `RECONNECTING` 08:07:22.760 → `CONNECTED` 08:07:28.271 (5.9 s, inside one backoff step), `re-announced format 590x1280 on new session gen=2`, and the new receiver saw `MIRROR_FORMAT 590x1280 … sps=18 pps=4` at 08:07:28 followed by `FRAMES received=60 → 120 dropped=0 status=rendering`. The restarted listener authenticated on the stored pin (`authenticated=true`) with no code comparison and no tap. |
| Stop path (notification action, system sharing chip) | **Not measured.** The affordance is verified present — `dumpsys notification` shows `id=8108 android.title="Mirroring your screen"`, `numWithActions=2`, `[0] "Stop" -> PendingIntent … com.droidbridge.app startService` — but it cannot be exercised by ADB on this build: expanding the shade makes `uiautomator` fail with `ERROR: could not get idle state`, leaves `mCurrentFocus=null`, and every subsequent tap is swallowed until the device is rebooted. Reproduced twice. The in-app `Stop sharing` control could not be measured for the same reason: the window manager had already wedged, so the tap was never delivered. |

A 2026-11-26 dry run retried the in-app `Stop sharing` row on the S24 with the UI responsive and its
frame counter live. After returning from `com.droidbridge.experiment.mediaprojection`'s `FLAG_SECURE`
activity via `am start`, `dumpsys window` still reported `mCurrentFocus` on that experiment window even
though the DroidBridge UI was on screen; two taps on `Stop sharing` (at its scrolled, unobstructed
position) and one long-press were swallowed and no `mirror.stop` reached the receiver. `KEYCODE_HOME`
returned focus to the launcher. The row stays **not measured**: this attempt exercises a focus-loss mode,
not the app's stop path, and the input never reached the app. USB state for that dry run: **not
connected**; wireless ADB on.

Three defects were found by running this, and all three are fixed:

- **A first pairing could never complete.** A clean install confirmed the code on the phone, whose heartbeat
  then arrived while the Mac was still `awaitingConfirmation`; the Mac closed with `peer sent data before the
  pairing was confirmed`, the confirm then failed (`PAIRING_CONFIRM_FAILED session is not awaiting pairing
  confirmation`), no pin was stored, and the two sides looped forever. The receiver now answers PING before
  confirmation (carrying no user data) and still refuses everything else until the user confirms. Measured
  after the fix: `AUTO_CONFIRM_CODE_VERIFIED code=903 717` → `AUTHENTICATED`, heartbeats `rttMs=10/131/237`.
  The first attempt at this fix crashed the listener (`SIGTRAP` in `isPingFrame`, because `Data` slices keep
  their parent's indices) — the crash report named the exact line.
- **The capture notification — the only stop affordance outside the app — was never requested.**
  `POST_NOTIFICATIONS` was declared in the manifest and described there as "requested at the moment mirroring
  starts", but nothing requested it. After a plain reinstall, `dumpsys notification` reported
  `importance=NONE` and `granted=false`, so the ongoing notification and its Stop action were invisible to
  the user. It is now requested exactly when capture starts, chained *before* the capture consent: launching
  both in one frame makes the system drop one, and the consent is the one that must not be lost.
- **Granting consent crashed the app.** `RemoteServiceException$ForegroundServiceDidNotStartInTimeException`,
  with `Last startServiceCommon()` pointing at `MirroringService$Companion.start` called from
  `MainActivity.onCreate$lambda$3`. `MirroringService.handleStart` validated its inputs before calling
  `startForegroundWithNotification()`, and the early-return path never posted the notification, which Android
  treats as a fatal FGS violation. Foreground is now claimed first, before anything can fail.

One operational finding: the Mac receiver's `CLOSED idle timeout after 120s` fired during a reboot gap
(08:24:26) and closed a session that was merely waiting. A reconnect test must restore the listener inside
120 s, and the product needs to decide whether 120 s is the right idle policy for a phone that may be
reconnecting.

## End of the overnight run (2026-11-23 22:00:27)

The 2026-11-23 endurance attempt ended at 22:00:27 with 6960 frames received, `dropped=0` — 20 min 42 s of
clean stream — at the moment the USB cable was pulled and the phone was picked up and locked. The last
received frame coincides with the lock, which matches the recorded platform behaviour (a locked screen ends
the projection, U10) rather than a transport fault: pulling the cable alone does not stop capture.

## Matrix rows measured 2026-11-24 on the OnePlus Nord (AC2001, Android 12 / API 31)

Run conditions: fresh listener on port 59695 with the pin reset (this phone kept its own stored pin for the
Mac identity, so it authenticated with `code=none` on its side; the Mac showed `code=537 797`, which matches
the value recorded for this device pair in Phase 2, and confirmation was accepted on that basis). USB
connected, USB debugging on. ADB drove setup only: launching the app, choosing the peer, tapping
`Share this screen` and `START NOW`; no ADB input entered the measured stream.

| Row | Result |
|---|---|
| **Stop via the notification action** | **Pass** — `Stop` tapped in the shade at 09:17:44 → phone `state=stopped: user stop … frames=226 bytes=1979080 keyframes=4 fps=6.0 kbps=420 dropped=0 formats=2 keyframeRequests=0 reEncodes=0 largestFrame=87701 maxSendMs=9 durationMs=37707 stopReason=user stop`; Mac received `frame mirror.stop:236:user stop` → `MIRROR_STOP reason=user stop`, and the underlying session stayed authenticated afterwards (a heartbeat arrived the next second), so stopping capture does not tear down the pairing. This also proves the notification Stop affordance end to end — the row the S24 could not measure because expanding the shade wedges its window manager. |
| **In-app `Stop sharing`** | **Not measured** — after the `START NOW` consent flow the OnePlus window manager also ends with `mCurrentFocus=null` and swallows taps (the S24 wedge reproduced on API 31), and a reboot cycle to retry it ran out of session time. The notification route above covers the same teardown. |
| Reconnect re-announce (second device) | **Pass** — listener killed mid-stream; phone `RECONNECTING` 09:19:01.446 → `CONNECTED` 09:19:05.383 (3.9 s), `re-announced format 640x1280 on new session gen=3`, new listener `FRAMES received=60 → 120 dropped=0 status=rendering`, authenticated on the stored pin with no code. Confirms the 2026-11-23 S24 result is not device-specific. |

Two defects found on this phone:

- **A stale half-open session is never reaped.** After the phone's reboot mid-session the Mac kept the
  abandoned session alive and rejected every reconnect with `one session per listener in this process`
  — still rejecting three minutes later, well past the 120 s idle timeout that is supposed to close an
  unused session. Only restarting the listener cleared it. Repro: connect, reboot the phone, watch the Mac
  refuse the returning client. The idle-timeout path needs auditing for sessions whose peer went away
  without a close frame.
- **The window-manager wedge is not Samsung-specific.** `mCurrentFocus=null` with all taps swallowed,
  `uiautomator` dumps still working, recovered only by reboot. It arrived after a SystemUI dialog
  (`START NOW` consent flow) rather than the notification shade, so the earlier "expanding the shade wedges
  it" theory is narrower than the reality: a system dialog over the app can wedge focus on this OnePlus
  build too.

OnePlus-specific note: `settings put system user_rotation` is denied to adb shell on this device
(`WRITE_SETTINGS` SecurityException), so rotation had to be driven with
`cmd window fixed-to-user-rotation -d 0 enabled` + `cmd window user-rotation -d 0 lock 0` and unlocked with
`user-rotation -d 0 free` afterwards.

## 2026-11-24 afternoon session (S24 Ultra, USB in; ADB operator mode)

Run conditions: USB connected, USB debugging re-enabled by the owner after the cable-out sweep; wireless
debugging off. ADB drove setup (app launch, picker, consent dialog) and no ADB input entered the measured
stream. Three measured rows and a class of defect found and fixed:

- **Cable-out acceptance, P08: pass.** ~33 minutes of mirroring with the USB cable physically disconnected
  and USB debugging disabled (`received=10380 enqueued=10380 dropped=0`, heartbeats every 15 s, listener
  restart mid-run reconnected pin-only and resumed the stream). Closed the P08 cable-out acceptance row.
- **Cable-out acceptance, phone→Mac clipboard: pass.** 117 bytes (113 chars, `sha=8a9bf01e5f95`,
  `reason=manual`) applied on the Mac pasteboard with the cable out and debugging off; verified by reading
  the pasteboard.
- **Cable-out acceptance, Mac→phone clipboard: sent, not acknowledged.** The 27-byte offer raced a session
  flap; the phone's card never showed an ack in the log. One tap on the phone (or a reconnect) re-arms it;
  left open.
- **P10 re-measured with fresh numbers (cable-in).** With the Wi-Fi password dialog focused on the phone,
  the mirrored video mean luma was **0.18** (black; only the status bar rendered) versus **12.75** with the
  home screen. The earlier P10 numbers stand; the protected-surface withholding reproduces.

### Stop path on the S24: the window-manager wedge is total

After the capture-consent dialog closes, `mCurrentFocus=null` and **every** tap is swallowed — in-app
`Stop sharing`, the system screen-sharing chip, and home/launcher taps included, while `uiautomator` dumps
keep working. The notification route is the only stop that works (measured on the OnePlus). Reproduced
across four attempts today. The in-app stop and chip-stop rows on this device remain unmeasured for that
reason; the wedge is a device/window-manager defect, not an app defect.

**Correction (2026-11-26):** at least the in-app half of this was the app. `Stop sharing` was rendered
`enabled=false` during a live share because `MirroringService.isCapturing()` tested a state string the
pipeline never writes; the control now enables during a share and ends it on the first tap on this device
(details in the 2026-11-26 section below).

### Defect: a returning phone could not take back its session (fixed in two commits)

Three independent reproductions today (reboot mid-session, reconnect after give-up, Wi-Fi toggle):

1. The listener rejected every returning connection with `one session per listener` until its 120 s idle
   timeout reaped the half-open session. The phone gives up in ~60 s, so it always lost the race
   (`GAVE_UP` 56 s before the reap in the measured case) and recovery needed a manual tap.
2. First fix (`4be0e32`): park the extra connection, verify its identity during the handshake, and
   supersede the live session when the pin matches the same phone. A flapping phone can outrun the
   handshake, so a newcomer while one is parked is refused, and mid-handshake states no longer cancel the
   parked connection.
3. Second fix (`8e0a079`): an adopted connection that was already `.ready` never received the state event
   again, so `connectionReady` never ran — no receive loop, no PONG, `peerPinValue` stayed nil, and every
   later parked connection was rejected as `does not match the live session's phone`. The post-verify body
   now runs in `completeHandshake` (once per adoption), called directly from the supersede path.

Final measurement: `svc wifi disable/enable` mid-session → `parking an additional connection` →
`superseding the live session: the same phone reconnected` → phone `CONNECTED` on the first attempt →
heartbeats n=6 with normal RTTs on the adopted session.

## 2026-11-26 overnight session (S24 Ultra over Wi-Fi ADB; USB disconnected)

Run conditions: USB disconnected, USB debugging on (no cable, so unused), wireless debugging on;
`adb -s 192.168.31.197:5555` drove setup only. The Mac receiver was the Debug build copied to
`/tmp/DroidBridgeHarness.app` and re-signed with the Apple Development identity **without**
`com.apple.security.app-sandbox` (the sandboxed build cannot write the recording dirs). Identity pin
`2178541711…`, phone pin `73e96f52…`, no re-pairing.

### In-app `Stop sharing`: measured, after two dashboard-state defects

The 2026-11-24 conclusion that the S24 swallows every tap after consent was wrong for this control
(see the correction note above). The button was rendered `enabled=false` because the dashboard derived
`capturing` from `MirroringService.isCapturing()`, which tested `stats.state.startsWith("streaming")`
while the pipeline publishes `"mirroring WxH"`. The dashboard therefore believed it was idle during a
live share: **`Stop sharing` was disabled and `Share this screen` stayed enabled**, which is also how a
second projection could be started on top of a working one (observed earlier as two capture pipelines
and 95 fps).

- Fixed `MirroringService.isCapturing()` to match the published state prefix (`mirroring`).
- Fixed the dashboard tick: it only incremented while `MirroringService.isRunning`, so after a stop the
  final `stopped: …` state never rendered and the buttons kept their capturing enablement. The tick now
  runs once a second unconditionally.

Measured on the S24, twice (12:05–12:08 and 12:16–12:17):

| Step | Evidence |
|---|---|
| Capturing state renders correctly | `Share this screen` parent `enabled=false`, `Stop sharing` parent `enabled=true`, card text `mirroring 590x1280` |
| Tap `Stop sharing` | phone `sent mirror_stop:1046:user stop` then `P08Mirroring: teardown complete reason=user stop`; Mac `EVENT frame mirror.stop:1046:user stop` |
| Session survives the stop | phone `sent ping` / `HEARTBEAT` after the teardown; no `CLOSED` on the Mac |
| Dashboard returns to idle | card text `stopped: user stop`; `Share this screen` `enabled=true`, `Stop sharing` `enabled=false` |


### Mac-initiated stop (measured 2026-11-26, 14:09–14:12)

The Mac can now ask the phone to stop sharing, which closes the gap the shell check left open (the viewer's
stop glyph used to close only the local session). S24 Ultra over wireless debugging, USB disconnected,
wireless debugging enabled, stored pins on both sides, sharing `Share entire screen`; the stop glyph was
clicked on the Mac's own viewer window (no ADB input in the functional path; ADB only launched the app and
accepted the system's share consent, and restored `screen_off_timeout` afterwards):

| Step | Evidence |
|---|---|
| Viewer stop glyph | Mac `p08: STOP_SHARING_REQUESTED` 14:09:03 |
| Phone receives it | `P08Mirroring: stop requested by the Mac reason=mac request` 14:09:03 |
| Same teardown as the in-app stop | `MIRROR state=stopped: mac stop ... frames=859 bytes=11003281 fps=10.0 durationMs=86272 stopReason=mac stop` then `teardown complete reason=mac stop` |
| Mac sees the stop | `MIRROR_STOP reason=mac stop audioFrames=3704`; no `CLOSED` line, so the session stayed up |
| Viewer returns to waiting | capture of the window shows the empty state on black - the last decoded frame is hidden rather than shown under the text |
| Share again on the same session | `mirroring started content=1080x2340` 14:12:41, new `MIRROR_FORMAT 590x1280`, no re-pairing |

The click only registers once the HUD is visible (it fades out after 2 s of no pointer movement); that is
the intended behaviour of a hidden control, not a defect.
The notification `Stop` action is still **unmeasured**: with the shade open `uiautomator dump` fails with
`ERROR: could not get idle state.` (reproduced with the app foreground, with the app behind HOME, and with
capture running), and `screencap` is prohibited, so the action's bounds cannot be located without blind
taps. The notification action and the in-app button both deliver `ACTION_STOP` to `MirroringService`
(`PendingIntent.getService` at `MirroringService.kt` vs `MirroringService.stop`), and the measured in-app
path exercises that handler; the widget itself is what remains untested.

### Receiver: a display-rate burst no longer ends the session (policy changed)

On the strict build, opening the notification shade ended the mirror:
`CLOSED framing/protocol violation: peer exceeded 120 media frames per second` at 12:12:33, with a single
capture pipeline (the encoder had been at 15–80 fps; the shade's 120 Hz animation delivered more than one
second of frames inside a second). Closing on the first over-budget media frame turns ordinary device
animation into a dead session.

`SecureListener.enforceRateLimit` now **drops** over-budget media frames, up to 600 consecutive drops
(~5 s at the 120/s budget), and closes only past that ceiling. The 120 media frames/s budget and every
control-frame limit are unchanged (ADR-058).

Verified on the fixed build: the same HOME → shade sequence and five rapid shade toggles kept the session
alive (`received=4680 enqueued=4680`, no `CLOSED`). The drop path itself fired once, in the run before
that: `media frames dropped=120 accepted=1721` at 12:02:59 while the shade was open, session still live.
`FrameRateLimiterTests`: 5 tests, 0 failures.

### Defect fixed: the phone now stops encoding shortly after the receiver closes

After the 12:12:33 close the phone kept capturing: `fps=66.3 → 79.7` over ten seconds, `frames=3200`,
`keyframes` climbing ~2/s, `largestFrame=207237`, `stopReason=active`, notification still up until the app
was force-stopped.

Cause: the sender ignored the transport's return value. `MirrorTransport.send` is specified to return
`false` when the session cannot carry a frame, and the video path used it as a bare statement, so the
loop kept encoding and "sending" into nothing. The sender now arms a 15 s grace window on the first
failed send, keeps the pipeline alive that long so a reconnect can still re-announce its format, and then
calls `teardown("session lost")`. Frames that cannot be queued are no longer counted as sent.

Verified end to end on the S24 over Wi-Fi (2026-11-26, USB disconnected): session up at 12:30:42, share
started 12:31:36 (`mirroring started content=1080x2340`, `P08Encoder: started 590x1280@30 cap=60
name=c2.qti.avc.encoder`), harness killed 12:31:48 → `state=waiting for session` → 12:32:03.712
`session lost for 15 s; stopping capture` → `state=stopped: session lost` (fps 3.9 → 3.6, durationMs
27 370) → 12:32:03.826 `teardown complete reason=session lost`. Afterwards no `MirroringService` record
in `dumpsys activity services`, no `p08_mirroring` notification in the live notification list, and no
encoder log lines. The 15 s window is deliberate: a reconnect inside it still re-announces the format.

### Sender-side frame cap

The encoder now carries `KEY_MAX_FPS_TO_ENCODER = 60` (its configured rate stays 30). On the S24 the
platform encoder accepted the key - the start line reports `cap=60` and no fallback warning - so the
worst-case burst the receiver's 120/s budget sees is halved. Measured under the same stimulus that
produced 79.7 fps before the cap (2026-11-26, 12:35:37-12:36:02): six shade expand/collapse cycles took
the 5 s average to **55.9 fps** peak, the receiver accepted 2 709 media frames (video and audio share that counter) with **no drops**, and the
session never closed. If an encoder rejects the key, the codec is
reconfigured without it once and the mirror still starts; the receiver budget remains the enforcement
point.

(The earlier draft of this note listed a sender-side cap as "follow-up worth doing" because the
encoder's configured 30 fps is not enforced for surface input; that cap is the section above and it is
now measured.)

### Endurance re-check with the cap and the grace window

Same session, 2026-11-26 12:39:23-13:09:08 (28 min 26 s, Wi-Fi, USB disconnected): 44 936 video frames,
~26 fps average because the launcher's wallpaper animated for most of the run (the dashboard went to the
background mid-run and the mirror kept going, which is the P14 behaviour), `largestFrame=410323`,
`maxSendMs=8`, `dropped=0` on the phone and `dropped=0` on the receiver, which accepted 114 036 media
frames. The notification shade was toggled every third minute (nine cycles) and the session never
closed, so the drop policy and the 60 fps cap hold under a sustained animated load, not only a burst.

The in-app stop was re-checked on the same build: tap -> `state=stopped: user stop`, `sent
mirror_stop:121929:user stop`, `teardown complete reason=user stop`; the Mac logged `MIRROR_STOP
reason=user stop`, the notification and the foreground service disappeared, and the TLS session stayed
up (the next heartbeat went through).

### Final no-debugging acceptance — 2026-11-26

Gap closed: the "all debuggings disabled" run this report has carried as missing since 2026-11-12 was
performed. Device: S24 Ultra `RZCY81TKBSV`, Android 17, `com.droidbridge.app` 0.1.0 debug APK; Mac: Debug
`DroidBridge.app` launched plainly from `macos/build` with **Start listening** pressed by the owner.
**USB connected: no · USB debugging: off · wireless debugging: off** for every row (`adb devices` listed
only the OnePlus `192.168.31.64:5555`, so ADB itself was alive and the S24 was not on it). Bluetooth off.
The device-side steps - pairing prompt, screen-share consent, the stop controls - were performed by the
owner at the phone; no ADB input, capture or clipboard access.

- *Start and viewer.* `Share this screen` -> consent -> the Mac showed the phone's screen live.
- *Stop from the Mac.* The stop control ended the share: the phone reported not sharing and its
  notification cleared.
- *Stop from the phone.* `Stop sharing` ended the share and the Mac's viewer closed by itself, with no
  action on the Mac.
- *Reconnect.* The Mac app was killed mid-session: the phone showed `reconnecting (attempt 1..7)` and,
  once the app was relaunched and listening, came back on the stored pins - `authenticated=true` at
  16:40:58 with no code prompt and no user action on the phone.

One defect surfaced by this run belongs to the transport rather than the stream and is fixed: the Mac
refused frames that arrived before its own pairing confirm, so a phone that confirmed first looped.
See the D2 entry in `Docs/PROGRESS_LOCAL.md` and ADR-060.

### Notification-shade stop — measured in the final sweep (2026-11-26)

The one row the sweep above still owed: tapping **Stop** on the ongoing capture notification, which is
the only stop affordance that lives outside the app. Same conditions as the rest of the sweep —
**USB connected: no · USB debugging: off · wireless debugging: off**, phone driven by hand, no ADB
input, no screen capture, no clipboard read. Receiver: the plain Debug app (PID 66319) on port 64429.

| Step | Evidence |
|---|---|
| Live share before the tap | `FRAMES received=2640 enqueued=2640 dropped=0 ptsUs=26941671332 status=rendering` 17:41:30.486; `AUDIO rms=0 silence … total=3551` at 17:41:30.033 (audio still arriving) |
| `Stop` tapped in the shade | Mac `frame mirror.stop:6405:user stop` 17:41:30.486 → `MIRROR_STOP reason=user stop audioFrames=3575` 17:41:30.545 |
| Notification cleared | owner-observed: **yes** - the ongoing "Mirroring your screen" notification left the shade |
| App shows no active share | owner-observed: **yes** - the dashboard was back to its idle state |
| Screen-sharing chip gone | owner-observed: **yes** - the status-bar / Quick Settings sharing chip disappeared |
| Session survives the stop | `frame ping:6406` at 17:41:40 - the TLS session stayed up, so this stop ends capture and not the pairing |
| Share again on the same session | `MIRROR_STARTED` 17:43:09.762 → `MIRROR_FORMAT 590x1280 rotation=0 … sps=18 pps=4` → `MIRROR_STOP reason=user stop audioFrames=104` 17:43:12.466, with no code comparison and no re-pairing |

The notification action is `ACTION_STOP` → `teardown("user stop")` (`MirroringService.kt:164`, `:223`),
the same handler the in-app button reaches, so the row proves the widget end to end rather than a
separate code path. **Known limitation to keep:** both stop paths log `reason=user stop`, so the Mac
log alone cannot say which control was used; the three phone-side observations above are what
identifies this row. Prior attempts could not measure it because `uiautomator dump` fails with
`ERROR: could not get idle state.` while the shade is open and screen capture is prohibited.

Still not measured anywhere in P08: glass-to-glass latency and device cost under a fixed workload
(carried forward unchanged from the 2026-11-12 acceptance).

