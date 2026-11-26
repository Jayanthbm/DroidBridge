# P12 — Audio and calls

**Status:** media audio **measured GO (conditional)** · call audio **NO-GO, now measured rather than
merely cited** · call notifications/control **deferred**

**Measured:** 2026-11-22 on SM-S928B (Galaxy S24 Ultra), Android 17 / API 37, serial `RZCY81TKBSV`.
**Test conditions:** USB connected, USB debugging **enabled**, wireless debugging **disabled**.

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
