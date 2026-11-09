# P05 — Clipboard Mac → Android

**Status: completed on Android 12 and Android 17 / S24 Ultra.** The Android 12 and Android 17
runs both prove that a background foreground-service app can write the clipboard, that a focused
app can read the result, and that the 100 KiB cap, duplicate suppression and oversize rejection
behave as designed. Android 17 adds two findings: the OS shows a generic **"Copied."** confirmation
with no content preview, and a non-picker local TCP connection is blocked until
`ACCESS_LOCAL_NETWORK` is granted. The exact "active mirroring FGS" integration variant is deferred
to P08; the 15/30/60-minute auto-clear observations are not run.

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
| Android 13+ copy preview | not available on API 31; S24 shows a generic **"Copied."** confirmation with no content preview | **Observed on S24** |
| Sensitive clip handling | P05 does not mark Mac text sensitive; P04 measured the flag on S24 and found it is not an access control | **Covered by P04** |
| Active mirroring FGS context | `dataSync` FGS used as the prototype stand-in | **Deferred to P08 integration** |

### Auto-clear

- **Android 12:** at **5 minutes 50 seconds** after the duplicate write, the clipboard still
  contained the same 20-byte text (`hash=8255faa7e97a`).
- **Android 17 / S24 Ultra:** at **≈6 minutes** after the last write, the clipboard still contained
  the same 20-byte text. The OS had not cleared it.

The 15-, 30- and 60-minute checks were not run on either device. DroidBridge does not rely on the OS
clearing the clipboard; if the product needs expiry, it must implement one. The service's retries
while the Mac sender was down had no effect on the clipboard.

## S24 Ultra re-run (Android 17 / API 37)

| Field | Value |
|---|---|
| Device | Samsung Galaxy S24 Ultra, SM-S928B |
| Android / API / One UI | 17 / 37 / 9.0 |
| Build | CP2A.260605.016.S928BXXS6EZJ1 |
| USB / debugging | yes / yes |
| Wi-Fi | phone 192.168.31.123, Mac 192.168.31.109 |
| Permissions | `ACCESS_LOCAL_NETWORK` **granted** (see finding below), `POST_NOTIFICATIONS` granted |

| Case | S24 observation | Verdict |
|---|---|---|
| Foreground 21 B | written, readback `len=21 hash=f7d000751130` matched | **Pass** |
| Background 21 B | written while activity gone, readback `76c8d4a36821` matched | **Pass** |
| Process recreation | new process wrote 20 B, readback `a1b7bfd389bc` matched | **Pass** |
| Unicode | 55 UTF-8 bytes → 47 UTF-16 units, hash matched | **Pass** |
| 100 KiB | 102,400 bytes written and read back | **Pass** |
| Duplicate ×2 | received 2, wrote 1, ignored 1 | **Pass** |
| Oversize 102,401 B | rejected on the header; clipboard unchanged | **Rejected as designed** |
| Android 13+ copy preview | a generic **"Copied."** confirmation appears; no content preview is shown | **Observed** |
| Sensitive clip | P04 already measured on this S24: the flag does not block a read; P05 writes plain text | **Covered by P04** |
| Active mirroring FGS | not run; dataSync FGS used. Deferred to P08 integration | **Deferred** |
| Auto-clear at 5 min | checked after the last write; see below | **Observed** |

### Android 17 permission finding

The first S24 attempts logged `SocketTimeoutException` connecting to the Mac even though the Mac's
listener was up, `ping` worked in both directions, and a raw `nc` from the phone's shell reached the
server. The app had only `INTERNET`. Adding `ACCESS_LOCAL_NETWORK` to the experiment manifest and
granting it made the same app connect immediately:

```
before: DISCONNECTED SocketTimeoutException: failed to connect … after 5000ms
after:  CONNECTED 192.168.31.109:47900
```

This is the non-picker counterpart to P02: the picker grant covers outbound TCP to an approved
peer, but a raw local-network socket outside that grant needs `ACCESS_LOCAL_NETWORK` on API 37.
The permission is declared **only** in this experiment app; the production manifest is unchanged.

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
7. **Android 17 gates raw local TCP outside the picker.** P05's plain socket timed out until the
   experiment declared and was granted `ACCESS_LOCAL_NETWORK`; P02's picker-approved outbound path
   did not need it. The product keeps the picker as the grant mechanism.
8. **Android 17's copy confirmation is generic.** The S24 shows "Copied." with no content preview;
   the prototype therefore cannot claim a content-preview UX on One UI 9.

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
| Android 13+ preview and sensitive behaviour | **Observed** — generic "Copied." confirmation; no content preview; sensitive covered by P04 |
| Active mirroring FGS variant | **Deferred to P08 integration** |
| Auto-clear timings | **Partially measured** — Android 12 and Android 17 5-minute checks; 15/30/60 not run |

P05 is **completed** for the clipboard path on Android 12 and Android 17. The only deferred row is
the exact active-mirroring-FGS integration context, which belongs to P08; that is recorded rather
than claimed.

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
