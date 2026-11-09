# P05 — Clipboard Mac → Android

**Status: Android 12 matrix complete; Android 17 / S24 Ultra rows pending.** The Android 12 run
proves that a background app can write the clipboard, that a focused app can read the result, and
that the 100 KiB cap, duplicate suppression and oversize rejection behave as designed. The
Android 13+ copy preview, sensitive-clip behaviour, One UI 9 background behaviour and the exact
"active mirroring foreground service" variant still need the S24 Ultra.

| Field | Value |
|---|---|
| Date | 2026-11-09 |
| Device | OnePlus Nord CE (AC2001) |
| Android version | 12 |
| API level | 31 |
| Build | AC2001_11.F.24 |
| Security patch | 2023-12-05 |
| USB connected | yes |
| USB debugging enabled | yes |
| Wireless debugging enabled | no |
| Device lock state | unlocked during the run |
| Wi-Fi | phone `192.168.31.64`, Mac `192.168.31.109` |
| Mac | MacBookAir10,1, macOS 27.0.1 |
| Experiment app | `com.droidbridge.experiment.clipboardreceive` 0.1.0 |

## Transport, stated plainly

This prototype uses a **plain framed TCP socket**, not TLS:

```
[4-byte big-endian UTF-8 byte length][UTF-8 text]
```

P03 already owns TLS 1.3, pairing and pinning. P05 asks a different question: does Android permit a
background app to write the clipboard, and what happens to the content afterwards? Keeping the
transport plain isolates that question. **This run is not TLS evidence and does not claim to be.**

The Mac side is the development harness `scripts/p05-clipboard-sender.py`; the Android side is
`android/experiments/clipboard-receive/`, a separate installable app with its own
`applicationId`. The receiver runs in a `dataSync` foreground service. The exact "active mirroring
foreground service" variant is deferred to the S24/P08 context.

## Matrix — observed on Android 12

| Case | Observation | Verdict |
|---|---|---|
| App foreground, receive 21 bytes | `DELIVER wrote=true len=21 hash=f7d000751130`; readback matched | **Pass** |
| App background, receive 21 bytes | `DELIVER wrote=true len=21 hash=76c8d4a36821` with the activity gone; readback after return matched | **Pass** |
| Process recreated, then receive 20 bytes | new process, `DELIVER wrote=true`, readback matched `a1b7bfd389bc` | **Pass** |
| Write while another app is in front | P05 background, P04 app foreground; Mac sent 21 bytes; P04 read `textLen=21 preview="p05-background-sample"` | **Pass** |
| Unicode / emoji / CJK / newline / zero-width | sender bytes **55**, service wrote, readback **47 UTF-16 units**, hash `c43c7851d789` matched | **Pass** |
| 100 KiB text | sender bytes **102,400**, service wrote, readback length **102,400**, hash matched | **Pass** at the cap |
| Duplicate content, two sends | received 2, wrote **1**, ignored **1** (`duplicate=true`), readback matched the first write | **Pass** |
| Empty payload (0 bytes) | rejected on the length header (`REJECT declaredLength=0`); clipboard unchanged | **Rejected as designed** |
| Oversize payload (102,401 bytes) | rejected on the header before allocation (`REJECT declaredLength=102401 cap=102400`); clipboard unchanged | **Rejected as designed** |
| Android 13+ copy preview | not available on API 31 | **Pending S24 Ultra** |
| Sensitive clip handling | P05 does not mark Mac text sensitive; P04 already measured that the flag is not an access control | **Pending S24 Ultra** |
| Active mirroring FGS context | `dataSync` FGS used as the prototype stand-in | **Pending S24/P08** |

### Auto-clear

At **5 minutes 50 seconds** after the duplicate write, the clipboard still contained the same 20-byte
text (`hash=8255faa7e97a`); Android 12 had not auto-cleared it. The 15-, 30- and 60-minute checks
were not run on the OnePlus and remain pending, together with the S24 Ultra comparison. The service
kept retrying the Mac connection while the sender was down; that had no effect on the clipboard.

## Findings

1. **Background clipboard writes are allowed; reads are still focus-gated.** P05 wrote from a
   foreground service while its activity was gone, and a different focused app read the result.
   P04's read gate remains: a background app still cannot read the clipboard. This is the correct
   division for the product.
2. **The 100 KiB cap must be enforced by DroidBridge.** Android 12 accepted the full 102,400-byte
   clip; P04 had already shown there is no OS cap. The receiver checks the wire length before
   allocating, and empty/oversized frames are rejected without touching the clipboard.
3. **Content identity must be over UTF-8 bytes.** The Unicode payload was 55 UTF-8 bytes but 47
   UTF-16 code units. A cap or hash based on `String.length` would be wrong; the wire cap is bytes
   and the hash is over UTF-8.
4. **Duplicate suppression works at the receiver.** Two identical sends inside the 5-second window
   produced one write and one ignored duplicate. End-to-end loop suppression still needs both
   directions (P05 + P06) and an origin ID, which is the NETWORK_PROTOCOL design.
5. **No content is logged or persisted.** Logs carry length and SHA-256 prefix only. The app has
   backup/device-transfer excluded and stores nothing on disk. The clipboard is OS state and the
   system may clear it; DroidBridge does not.
6. **No always-on promise.** The service must be running and connected to the Mac. Process death
   stops it; it reconnects only while the service is alive. There is no silent persistence or retry
   after a reboot in this prototype.

## What was built

- `android/experiments/clipboard-receive/` — separate app, never depended on by `:app`.
- `ClipboardPayload` — 100 KiB byte cap, SHA-256 identity, 5-second dedupe window.
- `ClipboardReceiverService` — `dataSync` FGS, framed TCP client with automatic reconnect,
  clipboard write, duplicate rejection, no content logs.
- `ClipboardReceiveActivity` — host/port presets via intent extras, start/stop, readback comparison,
  counters and event log.
- `scripts/p05-clipboard-sender.py` — Mac-side harness with synthetic payloads (foreground,
  background, cross-app, duplicate, Unicode, newlines, empty, 100 KiB, oversize) and no content
  logging.
- 6 unit tests for the cap, UTF-8 hash and short-hash rule.

The experiment module is now part of `scripts/verify-all.sh`, so a broken P05 build cannot pass CI.

## Acceptance status

| Criterion | Status |
|---|---|
| Define supported foreground/background behaviour | **Android 12: pass** — writes from background FGS, reads from focused app |
| No false promise of always-on background writes | **Pass** — service must be running/connected; no reboot persistence |
| No content persistence by default | **Pass** — no disk, no content logs, backup excluded |
| Android 13+ preview and sensitive behaviour | **Pending S24 Ultra** |
| Active mirroring FGS variant | **Pending S24/P08** |
| Auto-clear timings | **Partially measured; S24 rows pending** |

P05 is **in progress**, not complete, because the S24-only rows have no evidence yet.

## Reproducing

```bash
cd android
./gradlew :experiments:clipboard-receive:assembleDebug \
          :experiments:clipboard-receive:testDebugUnitTest \
          :experiments:clipboard-receive:lintDebug
adb install -r experiments/clipboard-receive/build/outputs/apk/debug/clipboard-receive-debug.apk

# Terminal 1 (Mac):
python3 scripts/p05-clipboard-sender.py --payload foreground

# Terminal 2:
adb shell am start -n com.droidbridge.experiment.clipboardreceive/.ClipboardReceiveActivity \
    --es host 192.168.31.109 --ei port 47900
# Tap "Start receiver", then run the sender; tap "Read back clipboard".
adb logcat -d -s P05Clipboard
```

For the background/cross-app row, press Home (or open the P04 app) before running the sender, then
return to P05 and read back. For the duplicate row use `--payload duplicate --repeat 2 --delay 0.3`.
For the cap rows use `--payload large` and `--payload oversize`.
