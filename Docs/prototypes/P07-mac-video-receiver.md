# P07 — Mac H.264 receiver

**Status: in progress — parser, format description, sample building and live viewing pass; the
backpressure, latency and `preventsCapture` verification rows are not yet measured.** The native
viewer decoded and displayed the ffmpeg-generated fixture (`status=rendering`,
`readyForDisplay=true`) and recovered after a flush. This is Mac-only evidence; no Android code is
involved, exactly as P07 requires.

| Field | Value |
|---|---|
| Date | 2026-11-09 |
| Mac | MacBookAir10,1, macOS 27.0.1 (26A434), arm64 |
| Xcode | 27.2 (27B52) |
| Fixture | `macos/Resources/p07-test.h264`, 48,029 bytes, 640×360, 90 access units, 60 fps |
| Signing | ad-hoc, App Sandbox on |
| Test surface | P07 tab in the bundled app + XCTest |

## Fixture

Generated with ffmpeg 9.0.2:

```bash
ffmpeg -y -f lavfi -i testsrc=size=640x360:rate=30 -t 3 \
  -c:v libx264 -profile:v baseline -level 3.1 -pix_fmt yuv420p \
  -x264-params keyint=30:min-keyint=30:scenecut=0 \
  -bsf:v h264_mp4toannexb -f h264 macos/Resources/p07-test.h264
```

It is a test vector, not product content. The app loads it from the bundle; `--p07-stream <path>`
can point at another Annex-B file.

## Proven by tests

`macos/Tests/H264AnnexBTests.swift` — 7 tests, all passing (44 macOS tests total):

| Test | What it proves |
|---|---|
| Fixture parse | 97 NALs, SPS/PPS present, first access unit is IDR, PTS strictly increasing |
| AVCC conversion | 4-byte big-endian lengths in front of each NAL |
| Emulation prevention | `00 00 03` inside a NAL is preserved byte-for-byte; trailing Annex-B zeros stripped |
| Parameter sets with start codes | `makeFormatDescription` rejects SPS/PPS that still carry a start code |
| Wrong `nalUnitHeaderLength` | rejected with `unsupportedNalLengthSize(2)` |
| Format + sample | 640×360 format description and a valid `CMSampleBuffer` for the first access unit |
| Timing | duplicate and non-monotonic PTS rejected |

The tests use the real fixture, not a hand-waved byte array.

## Proven by the running viewer

```
LOADED bytes=48029 nals=97 units=90 sps=25 pps=5 preventsCapture=true status=unknown
PLAY frames=90 status=unknown ready=true
READY_FOR_DISPLAY true
FRAMES enqueued=30 next=30 status=rendering
FRAMES enqueued=60 next=60 status=rendering
FRAMES enqueued=90 next=90 status=rendering
PAUSE enqueued=90 next=90
```

The layer reached `readyForDisplay=true` and `status=rendering` with the fixture decoded. That is
the core conversion + display proof P07 asks for.

### Flush / keyframe recovery

With `--p07-recover`:

```
RECOVER flush status=rendering requiresFlush=false
READY_FOR_DISPLAY false
READY_FOR_DISPLAY true
FRAMES enqueued=120 next=30 status=rendering
… enqueued=180 next=90 status=rendering
```

The decoder was flushed, the display reset, playback restarted at the first keyframe, and the layer
returned to `readyForDisplay=true` / `rendering` without an error. A corrupt sample was not injected;
this is recovery from a deliberate flush, not from a decoder failure.

## Deprecation audit (U20)

- The viewer uses `AVSampleBufferDisplayLayer.sampleBufferRenderer.enqueue(_:)` — the current
  per-layer renderer path, not the older `AVSampleBufferDisplayLayer.enqueueSampleBuffer`.
- `AVSampleBufferVideoRenderer` and the newer `Receiver` API are present in the macOS 27.2 SDK.
- The P07 build produced no P07 deprecation warnings. The older `enqueueSampleBuffer` path is
  deprecated on macOS 27 and was not used; the audit records this rather than suppressing it.

## Not yet measured

- **Backpressure:** the layer stayed ready for the whole 90-frame live run. A forced
  `isReadyForMoreMediaData=false` state was not induced, so the coalescing assertion is unproven.
- **Latency:** no high-speed camera or burned-in millisecond counter was used; the display-path
  latency is not a number yet.
- **`preventsCapture`:** set to `true` on the layer, but not independently verified with a screen
  recording because the host's screen-recording permission is not granted to this test harness.
- **Rotation/resolution change, aspect-fit letterboxing, dropped frames, stream stop/restart** with
  real Android input: fixed-fixture only.
- **A corrupt sample / decoder failure:** the flush/recover path is proven; failure induction is not.

## Acceptance status

| Criterion | Status |
|---|---|
| Stable native viewer independent of Android capture | **Pass** — 90 frames, readyForDisplay, rendering |
| Conversion and timing proven, including negative cases | **Pass** — 7 unit tests + live decode |
| Recorded latency | **Pending** |
| Deprecation list | **Pass for P07 code** (no deprecated enqueue used) |
| `preventsCapture = true` verified by screen recording | **Pending** |

P07 is **in progress**, with the core viewer and conversion proven.

## Reproducing

```bash
cd macos
xcodegen generate
xcodebuild -project DroidBridge.xcodeproj -scheme DroidBridge -configuration Debug \
           -derivedDataPath build test

open -n build/Build/Products/Debug/DroidBridge.app \
  --args --p07 --p07-autoplay --p07-recover

# Or run the binary for stderr evidence:
build/Build/Products/Debug/DroidBridge.app/Contents/MacOS/DroidBridge \
  --p07 --p07-autoplay --p07-recover
```

Evidence tags: `[p07]` on stderr, `p07:` in unified logging. No clipboard or screen content is
logged; only frame counts and renderer state.
