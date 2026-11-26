# P12 — Audio and calls

**Status:** media audio **measured GO (conditional)** · call audio **NO-GO, now measured rather than
merely cited** · call notifications/control **deferred**

**Measured:** 2026-11-22 on SM-S928B (Galaxy S24 Ultra), Android 17 / API 37, serial `RZCY81TKBSV`.
**Test conditions:** USB connected, USB debugging **enabled**, wireless debugging **disabled**.

**2026-11-26 dry run:** the A/V offset baseline on the live mirroring path is **measured** (audio later
than video by **+276 ms** mean) — see *A/V offset baseline* below. Conditions for that run differ:
**USB not connected, wireless ADB on**.

## Question

`PRODUCT_REQUIREMENTS.md` P1 asks to "investigate audio streaming under Android's playback-capture
rules"; P2 lists call notifications and call-audio routing as future goals. `R-15` already concluded
that playback capture is bounded and call audio is impossible. P12 asks the measurement question
those conclusions rest on:

> Which audio does `AudioPlaybackCaptureConfiguration` actually deliver on the target device, and
> what does it deliver when a stream is refused?

That distinction matters because every refusal in this area is **silent**. A capture that is blocked
returns a full-rate stream of digital silence, which is indistinguishable from "the app forgot to
ask", "the buffer was empty", or "the audio never started". Citing a documentation rule is therefore
not evidence; only the signal itself separates those cases.

## Method

Two isolated experiment apps, neither depended on by `:app`:

| App | Role |
|---|---|
| `com.droidbridge.experiment.audiocapture` | Holds a `mediaProjection` foreground service, builds `AudioRecord` + `AudioPlaybackCaptureConfiguration` matching `USAGE_MEDIA`, `USAGE_GAME`, `USAGE_UNKNOWN` |
| `com.droidbridge.experiment.toneplayer` | Plays a 440 Hz sine at quarter scale; the capture policy and usage class are chosen per launch |

The tone is a controlled signal on purpose: measuring against a third-party player would confound
the result with that app's own policy. The player publishes `ALLOW_CAPTURE_BY_ALL` or
`ALLOW_CAPTURE_BY_NONE` **at runtime on the same APK**, so between the positive and negative runs
nothing differs but the policy. A 440 Hz sine at quarter scale must appear as **peak 8190, RMS ≈
5791** if it is captured unmodified; anything materially below that is attenuation or refusal.

The measurement is the PCM the capture API hands back, logged per second as frame count, RMS and
peak:

```
t=2.0s frames=88200 total=5336100 rms=5791.5 peak=8190 silent=no
t=2.0s frames=88200 total=6350400 rms=0.0    peak=0    silent=yes
```

This is the P10 lesson applied again: a live playback-capture session reports frames regardless of
whether they carry audio, so frames are not the evidence — **amplitude is**.

## Consent flow (Android 17)

The projection consent is now a two-step picker. The scope choice matters and was made explicitly:

1. `Share your screen with P12 Audio Capture?` — a spinner offering **Share entire screen** or
   **Share one app**.
2. **Share entire screen** was selected. Single-app sharing scopes both picture and audio to the
   chosen app and would have produced a false negative for the cross-app rows.
3. `Share screen` confirmed.

## Results

Every row below is `USAGE_MEDIA` unless the usage column says otherwise. "Playing?" is independently
confirmed from `dumpsys`/audio-policy `startOutput` or an active `MediaSession`, so a silent capture
cannot be explained by a tone that never played.

| # | Source app | Policy | Usage | Playing? | Max RMS | Peak | Verdict |
|---|---|---|---|---|---|---|---|
| 1 | capture app itself | `ALLOW_CAPTURE_BY_ALL` | media | yes | **5791.5** | 8190 | captured, bit-exact |
| 2 | tone player (external) | `ALLOW_CAPTURE_BY_ALL` | media | yes | **5791.5** | 8190 | captured, bit-exact |
| 3 | tone player (external) | `ALLOW_CAPTURE_BY_ALL` | game | yes | **5791.5** | 8190 | captured, bit-exact |
| 4 | tone player (external) | `ALLOW_CAPTURE_BY_ALL` | unknown | yes | **5791.5** | 8190 | captured, bit-exact |
| 5 | tone player (external) | `ALLOW_CAPTURE_BY_ALL` | **notification** | yes | **0.0** | 0 | **refused by platform** |
| 6 | tone player (external) | `ALLOW_CAPTURE_BY_ALL` | **voice communication** | yes | **0.0** | 0 | **refused by platform** |
| 7 | tone player (external) | `ALLOW_CAPTURE_BY_NONE` | media | yes | **0.0** | 0 | **refused by the app** |
| 8 | capture app itself | `ALLOW_CAPTURE_BY_NONE` | media | yes | **0.0** | 0 | **refused by the app** |
| 9 | third-party YouTube client | its own policy | media | yes | 876–1217 | 5323 | **captured (real content)** |

Total frames across the session: **17,463,600** (≈396 s at 44.1 kHz) — the capture never stalled;
only the amplitude changed.

Captured audio is **bit-exact**: peak 8190 against a source amplitude of 8192, and RMS 5791.5
reproduced to one decimal in every allowed row. Playback capture is not a lossy tap on this device.

## Findings

1. **The documented allow-list is exactly right, and it is enforced by the platform.** `USAGE_MEDIA`,
   `USAGE_GAME` and `USAGE_UNKNOWN` are captured; everything else is not, even when the playing app
   asks for `ALLOW_CAPTURE_BY_ALL` (row 6).
2. **`USAGE_NOTIFICATION` is also excluded** (row 5). The documentation lists the allowed set rather
   than the excluded ones, so this was worth confirming: system UI sounds will not leak either.
3. **Call audio needs no live call to disprove.** Publishing `USAGE_VOICE_COMMUNICATION` with
   `ALLOW_CAPTURE_BY_ALL` — the most permissive posture an app can adopt — still yields silence
   (row 6). `R-15`'s NO-GO is now **measured on the target device**, not just cited.
4. **An app can refuse, and the refusal is total** (rows 7–8): the stream continues at full rate
   carrying nothing. A product that streams audio must therefore detect and surface this, because
   the user will otherwise see a working connection and hear silence.
5. **A capturing app does capture its own audio** (row 1). This contradicts the common reading of the
   API documentation and was not assumed — it was measured. Useful: a single app can verify its own
   pipeline end-to-end before blaming the platform.
6. **Mainstream media works** (row 9): a third-party YouTube client's audio was captured while
   playing real content, so the positive case is not an artefact of our own tone player.

## Decision

- **Media audio: GO, conditional.** Public APIs only, `RECORD_AUDIO` + MediaProjection consent, no
  root, no Accessibility, no hidden API. Conditional on the product treating silence as a first-class
  state (rows 5–8 are normal, not errors) and on `RECORD_AUDIO` being an approved, contextual,
  user-visible permission. **Shipped on 2026-11-26** in `:app` after the owner approved media audio and
  the permission (ADR-057); the shipping path re-measured the same numbers (phone `rms=5791.3`, Mac
  `AUDIO rms=5781`, silence at `rms=0` while frames advanced).
- **Call audio: NO-GO, closed.** Measured, plus `R-15`. No further test can reopen it: the exclusion
  is in the platform's capture policy, not in an app's choice.
- **Call notifications/control: deferred to P2.** `READ_PHONE_STATE` and notification-listener access
  are sensitive permissions that Phase 1 does not need, and `AGENTS.md` requires separate approval for
  them. Deferring costs nothing: nothing in the MVP depends on it.

## Permissions and isolation

`RECORD_AUDIO` was approved by the owner on 2026-11-22 for this experiment, and on 2026-11-26 for the
shipping app (ADR-057), so it now appears in `:app`'s manifest as well as this experiment's. The usage
set remains USAGE_MEDIA / USAGE_GAME / USAGE_UNKNOWN only, and `Docs/PERMISSIONS_MATRIX.md` records the
scope. The tone player requests **no permission at all** —
no microphone, no network, no storage — so a P12 measurement can attribute everything it observes to
the capture side.

Neither experiment has `INTERNET` or `ACCESS_LOCAL_NETWORK`: audio is measured locally and never
transmitted, which is also why the report contains no audio capture artefact — the evidence is
numeric, and recording the microphone would have been a wider privacy act than the test requires.

## What ADB did (required disclosure)

The owner directed that setup and prompts be driven through ADB instead of by hand. Performed:

- installed both experiment APKs (`adb install -r -g`), granted `RECORD_AUDIO` via `pm grant`;
- launched the capture activity and the tone player with intent extras;
- read the projection picker's UI hierarchy and tapped **Share entire screen**, then **Share screen**
  — a **consent dialog accepted through automation at the owner's instruction**. Nothing was
  accepted without first reading the on-screen text and confirming it was the expected screen-share
  consent with the intended scope;
- rebooted the device once to clear a wedged notification shade that was freezing launched
  activities (the app process was `freezing … audiocapture` behind a stale `NotificationShade`);
- set `svc power stayon usb` and a longer screen timeout for the session; both restored afterwards.

ADB did **not** generate any audio. The functional input under test — the 440 Hz tone and its capture
policy — came from `AudioTrack` inside the experiment apps, and the consent was only the authorisation
step. No screenshot or screen recording was taken, and no clipboard content was read or written.

## A/V offset baseline measured 2026-11-26 (dry run)

The question: when a phone-side source changes on screen and in the speakers at the same instant, how
far apart do the mirror's video and audio arrive at the Mac? This is the first measurement of that
offset on the product path; it is a dry run of the harness, not a perceptual verdict.

**Conditions:** SM-S928B (Galaxy S24 Ultra), Android 17 / API 37, build
`CP2A.260605.016.S928BXXU6EZJB`; Mac macOS 27.2.1 (`26B131`). **USB connected: no.** adb listed only
`192.168.31.197:5555` and `192.168.31.64:5555` (TCP) — no USB transport. **USB debugging: cannot be
asserted off from this host; no cable was attached, so it had no effect on the run. Wireless debugging
on (ADB over Wi-Fi drove setup).**

Receiver: the Debug build copied to `/tmp/DroidBridgeHarness.app` and re-signed ad-hoc **without**
`com.apple.security.app-sandbox`, so `P08_RECORD_DIR` and `P08_AUDIO_TIMELINE_DIR` could write under
`/tmp` (the sandboxed build cannot create those directories outside its container). Sources and
entitlements are otherwise unchanged. The repeat run used the Apple Development identity for the same
copy after a freshly re-signed ad-hoc binary hit a Keychain ACL prompt on the peer-pin item.

Stimulus: the new `experiments/av-sync-stimulus` app (`com.droidbridge.experiment.avsync`) fires a
150 ms 1 kHz stereo burst from an `AudioTrack` and turns the whole window white for 180 ms from the same
main-thread step, ten times six seconds apart. Its log line carries one `tNs` for both events.

Measurement: `P08_RECORD_DIR` wrote `stream.h264` plus `timeline.csv` (`index,ptsUs,bytes,wall`), and the
new `P08_AUDIO_TIMELINE_DIR` wrote `audio.csv` (`index,wallUs,ptsUs,frames,rms16`). Flash onset is the
first frame whose mean luma is above 200 after a dark frame (ffmpeg decode to 59x128 grey); beep onset
is the first access unit with 16-bit RMS above 300 after silence.

| Cycle | Flash frame arrival (Unix s) | Beep AU scheduled (Unix s) | Audio later by |
|---|---|---|---|
| 1 | 1795670967.700 | 1795670968.022 | +322 ms |
| 2 | 1795670973.739 | 1795670974.062 | +323 ms |
| 3 | 1795670979.754 | 1795670980.012 | +258 ms |
| 4 | 1795670985.778 | 1795670985.967 | +189 ms |
| 5 | 1795670991.781 | 1795670992.085 | +305 ms |
| 6 | 1795670997.811 | 1795670998.045 | +234 ms |
| 7 | 1795671003.827 | 1795671004.123 | +296 ms |
| 8 | 1795671009.983 | 1795671010.219 | +236 ms |
| 9 | 1795671016.018 | 1795671016.336 | +319 ms |
| 10 | 1795671022.018 | 1795671022.301 | +284 ms |

**Mean +276.5 ms, median +289.7 ms, standard deviation 43.2 ms, min +189 ms, max +323 ms (n = 10):
audio later than video.** Captured 3205 video frames and 5832 audio access units (~135 s). Beep peak
RMS was 7735 of 32768 (-12.5 dBFS), i.e. real signal rather than the silent-capture failure this
prototype warns about.

**Repeatability and drift.** A second session repeated the same ten-cycle block five minutes apart on a
449 s capture: block 1 mean **+254 ms** (sd 67), block 2 mean **+268 ms** (sd 56), a +13 ms
difference that is inside the run-to-run spread. The 19 340 access units cover 449 s exactly
(19 340 x 1024 / 44 100), so nothing was dropped while the screen sat idle between the blocks. On
this path the offset is stable over the tested window; it is a constant, not a leak.

**Where the delay plausibly comes from.** Audio does not arrive smoothly: `AudioRecord.read` fills a
16 KiB buffer (4096 frames, 93 ms), the encoder emits four access units per read, and the Mac
schedules each burst on arrival (median burst 4 AUs, 64-91 ms apart). A sound therefore waits for
the read window that contains it - up to ~93 ms, ~46 ms on average - before it is even encoded.
That is a floor the current pipeline can lower by shrinking the read, and it is the first thing to
try before adding a deliberate video delay.

Limits, which is why this is a baseline and not a verdict:

- The audio number is when the decoded buffer was **scheduled** on the Mac, not when it left the
  speaker; the player schedules each buffer on arrival (`at: nil`), so no deliberate delay is added.
  The video number is when the encoded frame **reached the receiver**, not when it was displayed; the
  screen capture emits on change, so it includes capture, encode and network time.
- Quantisation: audio access units are 23.2 ms; the flash step is resolved to the captured frame
  interval (0.13-0.30 s on this mostly static screen), which is the source of the per-cycle spread.
- One path only: whole-display `MediaProjection`, AAC-LC 44.1 kHz stereo 128 kbit/s, no Bluetooth and
  no resampling, two runs (135 s and 449 s) on one device and one Mac.

Consequence: on the current path the Mac plays mirrored audio roughly a quarter second after the
matching video frame arrives — noticeable for speech. Nothing re-times it today: the phone sends no
presentation offset and the Mac does not delay video. P12's remaining decision is whether to re-time
(delay video, or start audio earlier) and then re-measure against the chosen policy. The cheapest
first experiment is the capture read size: the 16 KiB read is 93 ms of sound, and cutting it to a few
AAC frames costs only a higher packet rate.

## Not measured / open

- **A live cellular or VoIP call.** Row 6 measures the platform rule that makes call audio
  uncapturable, which is the underlying question; a live call would add nothing except a second
  confirmation of the same policy. Recorded as unmeasured rather than claimed.
- **Sharing one projection between mirroring and audio capture.** Android 17 rejects a second
  `createVirtualDisplay` on one `MediaProjection` (P01 finding). Whether audio capture can share the
  mirror's existing projection instance, or needs its own consent, is untested and is the first thing
  a product audio track must settle.
- **Long-run drift.** The session ran ≈396 s. Bluetooth/A2DP and sample-rate resampling paths were not
  exercised, because capture is local.

## Reproduce

```bash
adb install -r -g android/experiments/audio-capture/build/outputs/apk/debug/audio-capture-debug.apk
adb install -r -g android/experiments/tone-player/build/outputs/apk/debug/tone-player-debug.apk
adb shell pm grant com.droidbridge.experiment.audiocapture android.permission.RECORD_AUDIO

# capture + projection consent (choose "Share entire screen")
adb shell am start -n com.droidbridge.experiment.audiocapture/.AudioCaptureActivity --ez startCapture true

# positive case, then the refusal — the only difference is the policy
adb shell am start -n com.droidbridge.experiment.toneplayer/.ToneActivity --es policy allow --es usage media --ei seconds 10
adb shell am start -n com.droidbridge.experiment.toneplayer/.ToneActivity --es policy none  --es usage media --ei seconds 10

# call-audio class: allowed by the app, still refused by the platform
adb shell am start -n com.droidbridge.experiment.toneplayer/.ToneActivity --es policy allow --es usage voice --ei seconds 10

adb logcat -s P12Audio
```

Expected: `rms=5791.5 peak=8190` for the allowed rows, `rms=0.0` with frames still advancing for
every refused row.

For the A/V offset measurement (the stimulus is a separate app with no permissions):

```bash
adb install -r android/experiments/av-sync-stimulus/build/outputs/apk/debug/av-sync-stimulus-debug.apk

# receiver records both timelines; the transcript also prints the event lines
P08_RECORD_DIR=/tmp/av/video P08_AUDIO_TIMELINE_DIR=/tmp/av/audio \
  DroidBridge.app/Contents/MacOS/DroidBridge --p08-listen --p08-allow-capture

# connect and share the whole screen, then fire ten flash+beep cycles six seconds apart
adb shell am start -n com.droidbridge.experiment.avsync/.AvSyncActivity
adb logcat -s P12AvSync
```

Analyse `video/timeline.csv` + `video/stream.h264` for the flash frames and `audio/audio.csv` for
the beep access units; the offset is beep onset minus flash onset. A sandboxed build cannot create
those directories outside its container, so a measurement build needs the sandbox entitlement removed
(the 2026-11-26 run used an ad-hoc re-signed copy under `/tmp`).
