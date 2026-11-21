# P10 evidence — protected-surface measurement (2026-11-21)

S24 Ultra, Android 17 / API 37, `590x1280` H.264 mirror, Python/ffmpeg measurement.

## Method

The Mac receiver recorded every received frame to disk (`P08_RECORD_DIR`), the phone phases were
tagged with wall-clock windows, and the recording was decoded **offline** afterwards
(ffmpeg → 64×128 gray → mean luma per frame). Decoding offline matters: a second live
`VTDecompressionSession` deadlocks, and running it inline blocked the main thread so the stream went
silent after roughly one decoder-queue depth.

## Results

| Phase | Frames | Mean luma | Range |
|---|---|---|---|
| Baseline, same screen, same motion | 645 | 196.4 | 195.2–197.9 |
| **`FLAG_SECURE` ON** | **809** | **1.0** | **0.9–1.1** |
| `FLAG_SECURE` OFF | 646 | 196.4 | 195.2–197.8 |
| Password-field screen (unprotected) | 46 | 238.1 | 238.1 |
| OTP notification, private version only | 493 | 80.3 | 66.7–87.4 |
| OTP notification, with public version | 510 | 80.5 | 73.9–87.2 |
| Notification shade open | 819 | 64.2 | 60.9–68.1 |
| Shade with private-only OTP | 838 | 114.4 | 112.5–115.5 |

## Images

- `baseline.png` — the identical screen unprotected, fully legible in the mirror (luma 196.4).
- `flag-secure-on.png` — the same screen with `FLAG_SECURE` set: the app's window is entirely black
  while the system status and navigation bars still render. Luma 1.0 across 809 delivered frames, so
  the stream was live and the pixels were black — not a stalled capture.

The status/nav bars staying visible is expected: they are system chrome, not the protected window, and
they are the reason the mean is 1.0 rather than exactly 0.

## Scope

Synthetic test surfaces and a throwaway OTP string only. No personal content was captured, and no
device-side screen capture (`screencap`/`screenrecord`) was used; the frames come from the product's
own MediaProjection stream.
