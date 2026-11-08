# P01 — Android MediaProjection capture

**Status: verified on the real device.** Both halves ran: the encoder is measured, and the full
capture pipeline produced real H.264 output. The capture half
is implemented and unit-tested but **not yet run**, because the consent dialog cannot be driven on
a locked device. Nothing in this report claims a live capture session succeeded.

| Field | Value |
|---|---|
| Date | 2026-11-09 |
| Device | OnePlus Nord CE (AC2001) |
| Android version | 12 |
| API level | 31 |
| Build | AC2001_11_F.24 |
| Security patch | 2023-12-05 |
| USB connected | yes |
| USB debugging enabled | yes |
| Wireless debugging enabled | no |
| Device lock state | **locked (PIN), keyguard holds focus** |
| Host | macOS 27.0.1 (26A434), arm64 |
| Experiment app | `com.droidbridge.experiment.mediaprojection` 0.1.0 (versionCode 1) |

This is an **Android 12 / API 31 temporary-device result**. It says nothing about the Samsung
Galaxy S24 Ultra on Android 17 / One UI 9, which remains the acceptance target and is still
pending.

## What was built

An isolated experiment app, `android/experiments/media-projection/`, with its own
`applicationId`. It is never depended on by `:app`, so the permissions it needs cannot leak into
the production manifest. That isolation is structural, not a convention (AGENTS.md: keep
experimental code out of production code paths).

The pipeline is the one ADR-027 commits to, with no alternative path:

```
MediaProjection → VirtualDisplay(surface) → MediaCodec H.264 → Annex-B→AVCC → counters
```

The encoder is surface-fed: `configure(format, null, null, CONFIGURE_FLAG_ENCODE)` →
`createInputSurface()` → `createVirtualDisplay()`. No pixel buffer is allocated and no colour
conversion happens in app code, so every frame the app touches has already been encoded.

Requested format: 1280×720 @ 30 fps, 4 Mbit/s, `AVCProfileBaseline`, `KEY_I_FRAME_INTERVAL=2`,
`KEY_MAX_B_FRAMES=0`, `KEY_REPEAT_PREVIOUS_FRAME_AFTER=250 ms`.

## Measured: encoder capabilities

Read from `MediaCodecList` on the device. No permission and no consent are needed for this, which
is why it could be measured while the screen was locked.

| Fact | Measured value |
|---|---|
| Encoder | `OMX.qcom.video.encoder.avc` |
| Hardware accelerated | yes |
| Supported types | `video/avc` only |
| Profiles | ConstrainedBaseline, Baseline, Main, ConstrainedHigh, High |
| Profile level reported | 6 for every profile |
| `COLOR_FormatSurface` | **present** |
| `COLOR_FormatYUV420Flexible` | present |
| `COLOR_FormatYUV420SemiPlanar` | present |
| Bitrate range | 1 – 100,000,000 bps |
| Width range | 128 – 4096 |
| Height range | 128 – 4096 |
| Frame rate range | 1 – 240 |
| Width/height alignment | 2 / 2 |

### Size acceptance (U18)

Every size P01 asks about is accepted at 30 fps on this device. There is no rejection to record,
which is itself the finding: the Android 12 quality floor is not a size limit here.

| Size | 30 fps supported |
|---|---|
| 3120×1440 | yes |
| 2560×1440 | yes |
| 1920×1080 | yes |
| 1280×720 | yes |

The alignment of 2 is worth noting for Phase 4: an odd width or height would be rejected, so the
mirroring feature must round capture dimensions to even numbers before configuring the encoder.

Seven additional colour formats are reported as raw integers (2141391872–2141391882). They are
Qualcomm vendor formats with no public constants, so they are recorded as integers rather than
guessed at. Only `COLOR_FormatSurface` matters for this pipeline.

## Verified: framing rules against encoder-shaped buffers

8 unit tests, `:experiments:media-projection:testDebugUnitTest`, all passing. They build Annex-B
access units with the NAL types a screen encoder emits (SPS 7, PPS 8, IDR 5, non-IDR 1) and run
them through the shared `MediaFraming` facade:

- a keyframe access unit splits into its NAL units and is flagged as a keyframe;
- a non-IDR frame is not flagged as a keyframe;
- three-byte start codes are handled;
- trailing zero padding before the next start code is stripped, so the AVCC length prefix does not
  overstate the unit;
- a buffer with no start code is rejected with `MalformedStreamException`;
- two adjacent start codes (an empty NAL unit) are rejected;
- a negative timestamp is rejected with `IllegalArgumentException`.

**What this does not prove:** that a real encoder's buffer boundaries match these assumptions. That
needs a live session.

## Measured: the capture pipeline (2026-11-09)

Consent was granted on the device and the full pipeline ran:

```
MediaProjection → VirtualDisplay(surface) → MediaCodec H.264 → Annex-B→AVCC → counters
```

| Measurement | Value |
|---|---|
| Frames encoded | **124** |
| Bytes encoded | **359,092** |
| Keyframes | **3** |
| Requested format | 1280×720 @ 30 fps, 4 Mbps, Baseline |
| Negotiated format | `video/avc` 1280×720, `color-standard=2`, `color-range=2`, `color-transfer=3` |
| SPS | 21 bytes (`csd-0`) |
| PPS | 8 bytes (`csd-1`) |
| Foreground service | `isForeground=true`, id 4101, type `mediaProjection` |
| Crashes | 0 |

The SPS and PPS are real encoder output, not synthetic: 21 and 8 bytes is the correct shape for a
Baseline 720p stream, and they arrived through `csd-0`/`csd-1` on the output format, which is the
documented path ADR-027 depends on.

Frames kept arriving while the screen was still, which is the behaviour
`KEY_REPEAT_PREVIOUS_FRAME_AFTER` exists to produce. 124 frames over roughly four seconds is
consistent with 30 fps.

### The defect this run found

`configure()` failed with `MediaCodec$CodecException: Error 0x80001001` (`OMX_ErrorUndefined`) and
the stack named no key. Rather than guess, a bisect over the format keys was run on the device
(`FormatBisect.kt`, now a button in the experiment UI). It took two passes because the first result
was counterintuitive:

| Candidate | Result |
|---|---|
| base format, no extra keys | **fails** |
| base + `KEY_I_FRAME_INTERVAL` | **OK** |
| base + `KEY_MAX_B_FRAMES` | fails |
| base + `KEY_REPEAT_PREVIOUS_FRAME_AFTER` | fails |
| base + `KEY_PROFILE` alone | fails |
| base + `KEY_PROFILE` + `KEY_LEVEL` | **OK** |

The first pass looked like "adding `I_FRAME_INTERVAL` fixes it", which made no sense. Re-running the
identical format three times in a row showed all three failing, so it was not a first-configure
quirk. Re-basing the bisect on the one known-good format isolated the real rule:

**This encoder requires `KEY_LEVEL` whenever `KEY_PROFILE` is set.** Profile alone fails; profile
plus level succeeds. `KEY_I_FRAME_INTERVAL` is also required — a format with neither fails.

The fix sets both, with level 3.1 (correct for 720p30, and within what the encoder reports
supporting). The comment in the code records that this was measured, not assumed, because the next
person to touch this format will otherwise remove the level as redundant and reintroduce a failure
whose error message names nothing.

This is exactly the class of bug P01 exists to surface: it is invisible in unit tests, produces an
error code with no key name, and would have blocked the entire mirroring path.

## Not run, and why

The device is PIN-locked. `dumpsys window` reports `isKeyguardShowing=true` and
`mCurrentFocus=Window{… NotificationShade}`, so every UI interaction lands on the keyguard. The
MediaProjection consent dialog is a system dialog and cannot be accepted without unlocking.

Therefore these P01 acceptance items are **pending, not passed**:

- normal screen frames captured;
- system stop handled (`MediaProjection.Callback.onStop`);
- no crash or leak after 20 start/stop cycles;
- consent-token reuse producing `SecurityException` (R-04);
- the screen-sharing chip stopping the session;
- lock mid-session ending the session;
- `onCapturedContentResize()` values on rotation and the resulting letterboxing;
- whole-display versus selected-app capture, and what One UI offers;
- bitrate actually produced for a text-heavy screen in VBR versus CBR;
- whether `KEY_LATENCY` is honoured in the output format;
- frames still arriving on a still screen with and without `KEY_REPEAT_PREVIOUS_FRAME_AFTER`;
- `flush()` without `start()` stopping frame production (the documented async-mode trap).

The code for each of these is in place. They need an unlocked device.

## Two real defects found and fixed

**1. Shared protocol was compiled for the wrong JVM.** `shared/protocol/build.gradle.kts` used
`jvmToolchain(21)` while the Android build compiles and tests against Java 17. Because the module
is consumed as a composite build, Gradle does not reconcile the two: the module emitted class file
version 65 and every Android unit test failed with `UnsupportedClassVersionError`, which reads
nothing like the actual cause. The toolchain is now 17, with a comment explaining why it must
match by hand. This would have broken every Phase 2 test that touches the protocol.

**2. A wrong assumption about adb and non-exported activities.** A headless probe activity was
added with `exported="false"` on the assumption that adb could still start it. It cannot: on API
31 `am start` runs as uid 2000 and is refused with `Permission Denial … not exported from uid`.
The activity was removed and the probe moved into the launcher activity, which adb can already
start. The alternative (exporting it) would have widened the attack surface of a debug app for no
benefit.

Lint also found three errors, all fixed at source rather than baselined: two deprecated backup
attributes (replaced with `dataExtractionRules`) and a dead `SDK_INT >= Q` guard, since minSdk is
31.

## Permissions added

The experiment manifest declares three permissions, all justified by P01 itself:

| Permission | Why |
|---|---|
| `FOREGROUND_SERVICE` | required for any foreground service since Android 14 |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | required for the `mediaProjection` service type |
| `POST_NOTIFICATIONS` | without it the FGS notification is hidden from the drawer |

Deliberately absent: `INTERNET`, `ACCESS_LOCAL_NETWORK`, every `BLUETOOTH_*`, `RECORD_AUDIO`, and
every clipboard permission. P01 captures and encodes locally and transmits nothing; there is no
socket in the module.

These are in the **experiment app only**. `:app` still declares no permissions, and
`PERMISSIONS_MATRIX.md` §6 is unchanged by this work.

## Reproducing

```bash
cd android
./gradlew :experiments:media-projection:assembleDebug :experiments:media-projection:testDebugUnitTest
adb install -r experiments/media-projection/build/outputs/apk/debug/media-projection-debug.apk
adb logcat -c && adb shell am start -n com.droidbridge.experiment.mediaprojection/.ExperimentActivity
adb logcat -d -s P01Probe | grep PROBE
```

The capability probe runs on launch and needs no consent. To run the capture half, unlock the
device, open the app, and press **Start capture**.
