# P01 — Android MediaProjection capture

**Status: validated on the target device — Samsung Galaxy S24 Ultra, Android 17 / API 37,
One UI 9.0 (2026-11-09).** Consent, foreground-service lifecycle, surface-fed H.264 output,
rotation, screen lock, user stop, system `onStop()`, 20 start/stop cycles, secure-content
protection and consent-token replay were exercised on the device. The Android 12 / OnePlus Nord CE
evidence is retained below as history; it is **not** the S24 Ultra result.

The user-required P01 validation matrix is met. Three plan-only extras remain open and are listed
under "Open items" — the system screen-sharing chip (lock already proved `onStop()`), `KEY_LATENCY`,
and the still-screen / `flush()` probes. None of them blocks the P01 acceptance criteria.

| Field | Value |
|---|---|
| Date | 2026-11-09 |
| Device | Samsung Galaxy S24 Ultra, **SM-S928B** (`e3q`, `e3qxins`) |
| Android version | **17** |
| API level | **37** |
| One UI | **9.0** (`ro.build.version.oneui=90000`) |
| Build | `CP2A.260605.016.S928BXXS6EZJ1` |
| Security patch | 2026-10-05 |
| Verified boot | `green` |
| Build type | `user` |
| USB connected | **yes** for the device sessions recorded here |
| USB debugging enabled | **yes** |
| Wireless debugging enabled | **no** |
| Device lock state | unlocked during capture; locked deliberately for the lock test |
| Host | MacBookAir10,1, macOS 27.0.1 (26A434), arm64, Xcode 27.2 |
| Experiment app | `com.droidbridge.experiment.mediaprojection` 0.1.0 |

The final wireless-only, debugging-disabled gate was **not re-run for P01**. The project-level
USB-disconnected run in `PROGRESS_LOCAL.md` / `P03-live-handshake-evidence.md` §7 Run P3 belongs
to P03. This report therefore records USB state honestly rather than borrowing that run's claim.

## What was built

An isolated experiment app, `android/experiments/media-projection/`, with its own
`applicationId`. It is never depended on by `:app`, so the permissions it needs cannot leak into
the production manifest. That isolation is structural, not a convention (AGENTS.md: keep
experimental code out of production code paths).

The product pipeline is the one ADR-027 commits to, with no alternative path:

```
MediaProjection → VirtualDisplay(surface) → MediaCodec H.264 → Annex-B→AVCC → counters
```

The encoder is surface-fed: `configure(format, null, null, CONFIGURE_FLAG_ENCODE)` →
`createInputSurface()` → `createVirtualDisplay()`. No pixel buffer is allocated and no colour
conversion happens in app code, so every frame the app touches has already been encoded.

A second, **measurement-only** path was added for the secure-content test:

```
MediaProjection → VirtualDisplay(ImageReader) → sampled centre luminance
```

It does not encode and is never used by production code. It exists because "does the platform
deliver protected pixels to a capture consumer?" cannot be answered by counting encoded bytes — a
black frame and a frozen frame have the same byte-count shape. Nothing in this path attempts to
bypass FLAG_SECURE; the test asserts that the platform honours it.

Requested encode format: 1280×720 @ 30 fps, 4 Mbit/s, `AVCProfileBaseline`, level 3.1,
`KEY_I_FRAME_INTERVAL=2`, `KEY_MAX_B_FRAMES=0`, `KEY_REPEAT_PREVIOUS_FRAME_AFTER=250 ms`.

## Measured on the S24 Ultra: encoder capabilities

Read from `MediaCodecList` with no permission and no consent (which is why it can be measured on a
locked device).

| Fact | Measured value |
|---|---|
| Encoder | `c2.qti.avc.encoder` |
| Hardware accelerated | yes |
| Supported types | `video/avc` only |
| Profiles | Baseline, ConstrainedBaseline, Main, High, ConstrainedHigh |
| Profile level reported | level 6 for every profile (the encoder's maximum, not a per-profile cap) |
| `COLOR_FormatSurface` | present |
| `COLOR_FormatYUV420Flexible` | present |
| `COLOR_FormatYUV420Planar` | present (deprecated platform constant warning) |
| `COLOR_FormatYUV420SemiPlanar` | present (deprecated platform constant warning) |
| Other colour formats | raw integers 20, 39, 54, 2130750114 (vendor formats) |
| Bitrate range | 1 – 220,000,000 bps |
| Width range | 128 – 8192 |
| Height range | 128 – 8192 |
| Frame rate range | 1 – 480 |
| Width/height alignment | 2 / 2 |

### Size acceptance (U18)

Every size P01 asks about is accepted at 30 fps on this device:

| Size | 30 fps supported |
|---|---|
| 3120×1440 | yes |
| 2560×1440 | yes |
| 1920×1080 | yes |
| 1280×720 | yes |

The alignment of 2 still matters: an odd width or height would be rejected, so capture
dimensions must be rounded to even numbers before configuring the encoder. That policy now lives in
`CaptureFormat.kt` with unit tests, rather than only in the service.

### Actual output format

For a 1280×720 session the encoder reported:

```
mime=video/avc 1280x720 profile=1 level=512 bitrate-mode=1 csd-0=21 csd-1=8
```

- profile 1 = Baseline;
- level 512 = AVC level 3.1;
- bitrate-mode 1 = VBR (the encoder's default; the requested 4 Mbit/s is a ceiling, not a target);
- SPS 21 bytes, PPS 8 bytes — real encoder output on `csd-0`/`csd-1`;
- `iFrameInterval=-1` in the output format, i.e. the encoder does not report the interval back.

Measured bitrate varied with content: ~300–450 kbps on a mostly static screen, ~1.3–1.7 Mbps
during shade/animation activity. The encoder did not attempt to fill the 4 Mbit/s ceiling.

## Measured on the S24 Ultra: capture session

A 189-second session (2026-11-09 19:27:11–19:30:21) produced:

| Measurement | Value |
|---|---|
| Frames encoded | **12,610** |
| Bytes encoded | **32,960,629** |
| Keyframes | **211** |
| Mean bitrate over the session | **1,392 kbps** |
| Max frame gap | **513 ms** |
| Max keyframe gap | **14,775 ms** |
| Content resizes | 2 |
| Crashes | **0** |
| Foreground service | id 4101, `isForeground=true`, `types=0x00000020` (`mediaProjection`) |

Two rate observations that matter for the product:

1. **The requested 30 fps is not enforced.** On a still screen the encoder emitted about 4–14
   frames/s (the repeat-previous-frame interval and content changes drive it); during shade and
   animation activity the cumulative rate rose to roughly 70–80 frames/s. `KEY_FRAME_RATE` is a
   hint to the encoder, while the surface's frame delivery drives the actual output. A production
   mirroring path needs its own frame pacing if it must cap bandwidth/frame rate.
2. **`KEY_I_FRAME_INTERVAL=2` is frame-count based.** At the observed ~4 fps still-screen rate a
   "2 second" keyframe interval stretched to ~15 seconds. The product must not assume wall-clock
   IDR cadence; Phase 4's keyframe-recovery design should request sync frames explicitly rather
   than rely on the interval.

The session survived the activity being backgrounded (frames continued, FGS stayed foreground) and
produced frames on a still screen, which is what `KEY_REPEAT_PREVIOUS_FRAME_AFTER` exists for.

### Rotation

Rotation was forced with:

```bash
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1   # landscape
adb shell settings put system user_rotation 0   # portrait
```

`onCapturedContentResize()` fired with:

| Transition | Values |
|---|---|
| Session start | `1080x2340` (the panel's content size), then a duplicate callback |
| Portrait → landscape | `2340x1080`, then a duplicate |
| Landscape → portrait | `1080x2340`, then a duplicate |

`VirtualDisplay.resize(width, height, dpi)` accepted each change and frame production continued
across both rotations (231 frames at the next 5-second stats line). The encoder surface stayed at
1280×720, so the platform scales the new aspect into it; letterboxing is a receiver property, not
something this encoder reports.

**Platform restriction found:** Android 17 rejects a second `createVirtualDisplay()` on the same
`MediaProjection` instance:

```
SecurityException: Don't take multiple captures by invoking
MediaProjection#createVirtualDisplay multiple times on the same instance.
```

The first implementation tried to rebuild the encoder + VirtualDisplay at the new size and was
killed by this rule. The fix is to use `resize()` on the existing display and to treat a change of
*encoded* resolution as a new session (fresh consent), which is a Phase 4 product decision, not a
P01 defect.

### Screen lock, user stop, and system `onStop()`

| Action | Observed |
|---|---|
| Press power / lock mid-session | `MediaProjection.Callback.onStop fired`; teardown completed (309 frames, 2,853,648 bytes, 6 keyframes, 15.4 s); service gone |
| Tap **Stop** in the app | `TEARDOWN reason=user stop`, clean counters, service gone |
| Unlock afterwards | Device returned to launcher; a new session requires a fresh consent dialog |

The lock test is the system-initiated termination evidence: `onStop()` is the same callback the
screen-sharing chip uses. Tapping the chip itself was not separately automated.

### Repeated start/stop — 20 cycles

`scripts/p01-capture-cycles.py` drives the debug UI over USB (including the system consent dialog)
and records each teardown.

| Result | Value |
|---|---|
| Cycles completed | **20 / 20** |
| Frames | **2,005** |
| Bytes | **7,903,188** |
| Crashes (`FATAL EXCEPTION` in the crash buffer) | **0** |
| Process | same PID before and after (5591) |
| Threads | 31 before, 32 after (one encoder thread still winding down) |
| Service after the loop | none |

Every cycle received a fresh consent grant; the consent token was never reused to start a session.
The descriptor count could not be captured reliably during this run (`/proc/<pid>/fd` needs the
app's uid; a later `run-as` count succeeded but there was no matching before-value). The thread
count and same-PID result are the cleanup evidence recorded here.

### Secure / protected content

`ContentTestActivity` shows a white screen with or without `FLAG_SECURE`. The pixel probe samples
the centre of the MediaProjection consumer surface and logs mean luma.

| Content on screen | Measured mean luma |
|---|---|
| Normal (no FLAG_SECURE) white test screen | **159** |
| Same screen with `FLAG_SECURE` | **0** (black); black samples incremented |
| Back to normal screen | **159** |

No bypass was attempted. The platform delivers protected windows as black to the capture consumer,
which is the expected behaviour and the evidence P01 needs.

### Consent-token replay (R-04) — corrected finding

The documented expectation is a `SecurityException` when a consent `Intent` is reused. On this
Android 17 build the behaviour is more subtle:

| Probe | Result |
|---|---|
| `getMediaProjection` with a used token while the live session is running | **returned a projection object** (`same_instance=false`), not an exception |
| Calling `stop()` on that returned object | ended the live session — it is bound to the same capture |
| `createVirtualDisplay` on the same instance a second time | `SecurityException: Don't take multiple captures…` (the one-shot rule is enforced here) |
| `getMediaProjection` with a used token with no `mediaProjection` FGS running | `SecurityException: Media projections require a foreground service of type FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION` |

**Product consequence:** a consent token must be treated as single-use by the app, and the app must
not rely on `getMediaProjection` throwing to enforce that. The experiment's probe now runs inside
the active session and does not touch the returned object; the reproducible failure is recorded
above.

### Whole-display vs selected-app capture

The One UI 9 consent dialog offers **Share one app** (the default) and **Share entire screen**.
Selecting one app and choosing the 2048 game produced 64 frames / 162,759 bytes / 2 keyframes over
~24 s. Bringing the experiment back to the foreground made
`onCapturedContentVisibilityChanged(isVisible=false)` fire, which is the expected
selected-app behaviour and a useful signal for a future viewer.

## Defects found and fixed

1. **Consent and notification dialogs raced.** Requesting `POST_NOTIFICATIONS` and launching the
   capture consent in the same call made the two system dialogs overlap. The notification result
   now launches the capture consent, never in parallel.
2. **Drain loop exited immediately (zero frames).** `isRunning` was set after the pipeline started,
   so the drain thread could observe `false` on its first iteration. It is now set before the
   pipeline starts. The S24 Ultra run caught this as "running, zero frames".
3. **Stats format string produced `fps=%.1f`.** `.format()` was attached to the last string
   fragment instead of the concatenated message. Parenthesised; the format now renders.
4. **Rotation rebuilt the pipeline illegally.** Fixed to `VirtualDisplay.resize()`; see the platform
   restriction above.
5. **UI counters froze at zero.** The polling `tick` was no longer read by the composable. It is
   read again so recomposition happens.
6. **Consent token was lost on rotation.** A ViewModel now retains it across configuration changes
   for the explicit reuse test. It is never replayed to start a session.
7. **`EncoderProbe` reported `levels=[]` and had a duplicate profile branch.** Levels are now
   derived from `profileLevels`, and the duplicate branch is removed.
8. **Token-reuse probe killed the live session.** The first probe called `stop()` on the returned
   projection; the probe is now non-destructive.
9. **No secure-content evidence path existed.** Added the ImageReader luminance probe and the
   white/secure comparison activity, without changing the product pipeline.

## Open items

These are P01 plan items that were not exercised, not acceptance failures:

- Tapping the system screen-sharing chip directly (lock already exercised the same `onStop()`
  callback).
- `KEY_LATENCY` in the output format.
- A 30 s still-screen comparison with and without `KEY_REPEAT_PREVIOUS_FRAME_AFTER`.
- `flush()` without `start()` stopping frame production (the async-mode trap).
- A wireless-only P01 run with USB disconnected and debugging off. The owner directed that the
  existing project-level wireless-only run not be repeated; this report records the USB-attached
  state of the P01 sessions instead of claiming otherwise.

## Permissions

The experiment manifest declares three permissions, all justified by P01 itself:

| Permission | Why |
|---|---|
| `FOREGROUND_SERVICE` | required for any foreground service since Android 14 |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | required for the `mediaProjection` service type |
| `POST_NOTIFICATIONS` | without it the FGS notification is hidden from the drawer |

Deliberately absent: `INTERNET`, `ACCESS_LOCAL_NETWORK`, every `BLUETOOTH_*`, `RECORD_AUDIO`, and
every clipboard permission. P01 captures and encodes locally and transmits nothing; there is no
socket in the module. These are in the experiment app only; `:app` still declares no permissions,
and `PERMISSIONS_MATRIX.md` §6 is unchanged.

## Reproducing

```bash
cd android
./gradlew :experiments:media-projection:assembleDebug \
          :experiments:media-projection:testDebugUnitTest \
          :experiments:media-projection:lintDebug
adb install -r experiments/media-projection/build/outputs/apk/debug/media-projection-debug.apk
adb logcat -c
adb shell am start -n com.droidbridge.experiment.mediaprojection/.ExperimentActivity
adb logcat -d -s P01Probe | grep PROBE          # encoder capabilities, no consent needed
```

The 20-cycle harness (development-only; it drives the consent dialog over ADB):

```bash
python3 scripts/p01-capture-cycles.py --cycles 20 --settle 2.5
```

Evidence tags: `P01Probe` (capabilities), `P01Projection` (`pipeline`, `OUTPUT_FORMAT`, `STATS`,
`TEARDOWN`, `PIXEL`, `TOKEN_REUSE`, `onCapturedContentResize`).

## Historical: OnePlus Nord CE, Android 12 / API 31

The first P01 run used a OnePlus Nord CE (AC2001), Android 12, API 31, build `AC2001_11_F.24`,
security patch 2023-12-05, with USB attached and USB debugging enabled. That run produced
124 frames / 359,092 bytes / 3 keyframes at 1280×720 and found the encoder quirk that `KEY_PROFILE`
requires `KEY_LEVEL` on the Qualcomm encoder. It also produced the original size-acceptance table
and the 8 framing unit tests.

Those figures are Android 12 evidence and are **not** used as S24 Ultra evidence. The S24 Ultra
re-run above is the acceptance result.
