# P08 — End-to-end mirroring stream (evidence)

**Status: acceptance run performed 2026-11-12 on the S24 Ultra against a rebuilt Debug Mac app.**
Rotation/re-encode, keyframe-request recovery, clean stop/teardown, screen-off stop and a
30-minute continuous session are recorded with logs below. Two gaps remain and are stated
explicitly at the end: the final "all debuggings disabled" run, and glass-to-glass latency.

| Field | Value |
|---|---|
| Prototype | P08 — end-to-end mirroring over the ADR-012 transport (pinned TLS + length-prefixed H.264) |
| Android device | Samsung Galaxy S24 Ultra, **SM-S928B** (`e3q`/`e3qxins`), Android **17 / API 37**, build `CP2A.260605.016.S928BXXS6EZJ1`, One UI `90000` |
| Mac | macOS **27.2.1** (`26B131`), Apple silicon; Debug `DroidBridge.app` launched with `--p08-mirror` |
| Network | Both ends on the same Wi-Fi LAN; Mac `192.168.31.109`, phone `192.168.31.106` |
| Capture | `MediaProjection` whole-display, `1080x2340` content → `590x1280` (then `1280x590` after rotation), encoder `c2.qti.avc.encoder`, 4 Mbit/s, `KEY_I_FRAME_INTERVAL` + `KEY_REPEAT_PREVIOUS_FRAME_AFTER` |
| **USB connected** | **No** — `adb devices -l` listed only `192.168.31.106:5555` (transport_id 2, TCP); no USB transport was present for any run in this report |
| **USB debugging** | Not applicable for these runs (no cable attached); cannot be asserted off from this host |
| **Wireless debugging / ADB over Wi-Fi** | **Yes** — required to drive `adb install`, `logcat`, `dumpsys` and `input` for the scripted runs. The final acceptance run with both debuggings disabled has not been performed |
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
- **Observed robustness gap (not fixed):** rotation recreates `MirroringActivity` (`identity pin=…` re-logged) while `MirroringService` keeps streaming. The mirrored output is unaffected, but the recreated activity loses its session handle and displays `status: idle`, so restart controls are unavailable until reconnection. Tracked here rather than changed, because it is P08 UI state, not stream correctness.

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
- A locked-screen (keyguard) run was **not** performed separately; both observed stops are the screen-off / projection-revoked case. Lock remains unrun for P08.

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
`KEY_REPEAT_PREVIOUS_FRAME_AFTER` (repeat last frame instead of a free-running 30 fps). The
comparison run **without** `KEY_REPEAT_PREVIOUS_FRAME_AFTER` was not executed, so the "with vs
without" delta in `PROTOTYPE_PLAN.md` is only half measured.

Not measured, and not claimed: end-to-end latency in milliseconds, CPU/GPU cost, battery drain,
thermal behaviour, reconnect time after a deliberate link drop.

## 7) Defects found and fixed during this run

- **Mac identity regenerated on every Listen** (`29892b4`). `startListening()` called `MacIdentity.generate()` after `stop()`, so each Listen changed this Mac's pin. Every session restart then required Forget stored pin plus a fresh six-digit comparison, and reconnect recovery could not be exercised at all. The receiver now holds one identity for the life of the app run (persistent identity storage remains P15's scope).
  - Verified: two consecutive Listen presses reported the same `identity-pin=b8cae483…`, and after **Close session** a fresh Connect against a new listener port returned `handshake ok … authenticated=true` using the stored pin, with no Forget and no re-pair.
- **Scripted fault hook** (`29892b4`): `--p08-fault-after <seconds>` for the recovery case, inert unless passed.
- Cosmetic: the `LISTENING` event logs `port=0` because the port is read before the listener is ready; the real port appears in the following `EVENT listening on port …` line.

## Remaining P08 gaps

1. **Final acceptance run with USB disconnected and both USB and wireless debugging disabled.** These runs were wireless-only (USB absent), but wireless debugging was necessarily on for scripted control. Owner action: disable Wireless debugging / USB debugging and re-run a manual 30-minute session.
2. **Glass-to-glass latency, CPU/GPU, battery, thermal** not instrumented.
3. **Bitrate/frame-rate comparison without `KEY_REPEAT_PREVIOUS_FRAME_AFTER`.**
4. **Locked-screen (keyguard) stop** not run; only screen-off.
5. **Corrupt-sample precondition for decoder failure** did not reproduce; recovery is proven via the flush + keyframe request branch and the explicit keyframe control.
6. **Rotation UI state**: the recreated activity reports `idle` while the service keeps streaming; controls need a reconnect.

P08 is therefore **not** marked complete: the stream itself meets the measured criteria, but items
1–3 above are acceptance evidence that has not been captured.
