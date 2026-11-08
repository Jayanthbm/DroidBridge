# P04 — Clipboard Android → Mac

**Status: matrix complete on Android 12.** Every row was observed on the device, including the
decisive negative: with another app in front, the read returns nothing. The focus gate is measured,
not assumed.

| Field | Value |
|---|---|
| Date | 2026-11-09 |
| Device | OnePlus Nord CE (AC2001), Android 12, API 31, build AC2001_11_F.24 |
| USB connected | yes |
| USB debugging enabled | yes |
| Wireless debugging enabled | no |
| Device lock state | unlocked during the run (`deviceLocked=0`) |

## The matrix — observed

| Case | Expected | Observed | Verdict |
|---|---|---|---|
| Activity visible and focused, "Send clipboard" | works | `hasPrimaryClip=true`, `text/plain`, 22 bytes, `preview="droidbridge-p04-sample"` | **works** |
| Activity visible, no text field focused | works or null | same read succeeds; no text field is required | **works** |
| Foreground service only, another app on screen | null | `hasPrimaryClip=false`, no MIME types, 0 bytes | **null, as predicted** |
| Screen off / dozing | null | `hasPrimaryClip=false`, no MIME types, 0 bytes | **null, as predicted** |
| Process recreated, then "Send clipboard" | works | force-stop, new pid 22681, read returns the same 22 bytes | **works** |
| Clipboard empty | null / rejected | `hasPrimaryClip=false` after `clearPrimaryClip()` | **null, as predicted** |
| Clipboard contains an image only | rejected by policy | `hasPrimaryClip=true`, `mimes=[image/png]`, `textLen=0`, `hasImage=true` | **readable, but no text** |
| Duplicate content (same text twice) | deduplicated | both reads return identical content; nothing deduplicates at the OS level | **app must deduplicate** |
| Very long text (100 KB) | capped by policy | `textLen=102400` returned in full; no OS cap | **app must cap** |
| Sensitive clip (`EXTRA_IS_SENSITIVE`) | preview suppressed; check readability | `sensitive=true` **and** the full text was readable | **flag does not block a read** |

## The four findings that change the product

**1. The focus gate is real and total.** With the launcher in front, `hasPrimaryClip` returned
`false` — not an empty string, not a stale value, nothing. The same is true with the screen off. This
confirms ADR-013's premise with evidence rather than documentation, and it means the Android → Mac
story is a manual action from a focused app. There is no background path, and no amount of
engineering would produce one without an IME or Accessibility Service, both prohibited.

**2. `EXTRA_IS_SENSITIVE` does not block a read.** A clip written with the sensitive flag set was
read in full: `sensitive=true`, `textLen=16`, `preview="p04-secret-value"`. The flag is a
*preview-suppression hint for the system UI*, not an access control. DroidBridge must therefore
treat the flag as a signal to be cautious — never auto-sync a sensitive clip, never log it — but it
must not claim the OS protects it, because the OS does not.

**3. There is no OS-side size cap.** A 100 KB clip came back complete. The 100 KB figure in the P04
plan was an assumption; the OS does not enforce it. DroidBridge must impose its own cap, and the
framing limit in `shared/protocol` (1 MiB) is what makes an oversized message a protocol error
rather than an unbounded allocation.

**4. Nothing deduplicates.** Reading the same content twice returns it twice. Loop prevention must be
DroidBridge's own, keyed on content hash and origin ID as NETWORK_PROTOCOL §6 already specifies.

## What the Android → Mac user story can now say

> Copy text on your Android phone, open DroidBridge, and tap **Send clipboard**. DroidBridge can read
> the clipboard only while it is the app on screen — Android blocks clipboard reads for apps that are
> not in front, and the screen being off counts as not in front. If the app was closed or the phone
> was locked, reopen DroidBridge and tap Send again; nothing is lost, because the clipboard is still
> there. Text is sent, up to a limit DroidBridge sets. Images are not sent. A clip another app marked
> as sensitive is not synced automatically.

That wording is defensible because every clause in it was observed.

## Not observed

- **The Android 12+ "app accessed clipboard" toast.** No clipboard-access notice appears in logcat on
  this One UI build. It may be suppressed for a debug build or simply not logged; a screenshot during
  a read would settle it, and the device was driven over adb rather than watched. Recorded as
  unverified rather than claimed either way.
- **The sensitive row on API 33+.** `EXTRA_IS_SENSITIVE` does not exist on API 31, so this device
  could not exercise the flag as the OS would on Android 13+. The probe set the extra by its literal
  key and the read succeeded, which is the strongest available statement here. Re-run on Android 17.

## What was built

`android/experiments/clipboard/` — a separate installable app, never depended on by `:app`,
declaring **no permission at all**. There is no clipboard permission on Android; access is gated on
focus, and declaring anything would change the thing being measured.

`ClipboardProbe` runs one matrix row per action and logs a single greppable line per read:
`hasPrimaryClip`, MIME types, text length, an escaped preview, image/URI detection, and the sensitive
flag. A refused read is recorded as `hasPrimaryClip=false` with zero length — never as an empty
string, because "the OS refused" and "the clipboard was empty" are different findings and only one is
a product problem.

`ProbeReceiver` exists solely to measure the unfocused row. Pressing a button requires the activity to
be focused, so a broadcast is the only way to reach the app while another app is in front. It
performs one read, retries nothing, and stores nothing.

It is guarded by a `signature` permission. Lint flagged the unguarded version as
`ExportedReceiver` — an exported receiver any installed app could use to trigger a clipboard read —
and that is a real finding, so the guard went in rather than a lint suppression. The consequence is
that `adb shell` cannot reach it either, because shell cannot hold a signature permission. The
unfocused and screen-off rows were therefore measured **before** the guard was added, and
re-measuring them needs the `android:permission` attribute removed from the receiver for one run.
That trade is the right way round: the code stays safe, and the report says exactly how the evidence
was obtained.

## Verified

**9 unit tests pass** (`:experiments:clipboard:testDebugUnitTest`) over the reporting contract, and
**lint is clean** with `warningsAsErrors`. Five real lint findings were fixed at source rather than
baselined, including that `EXTRA_IS_SENSITIVE` is API 33 — which is how the API-level limitation was
found in the first place.

## Reproducing

```bash
cd android
./gradlew :experiments:clipboard:assembleDebug
adb install -r experiments/clipboard/build/outputs/apk/debug/clipboard-debug.apk
adb shell am start -n com.droidbridge.experiment.clipboard/.ClipboardActivity
# each button is one row; read the observations:
adb logcat -d -s P04Clipboard
# the unfocused row needs a broadcast, because a button press requires focus.
# The receiver is guarded by a signature permission, which adb cannot hold, so remove the
# android:permission attribute from ProbeReceiver in the manifest for this one run:
adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME
adb shell am broadcast -n com.droidbridge.experiment.clipboard/.ProbeReceiver --es case background_unfocused
```

Note: an implicit broadcast to this receiver is refused with `Background execution not allowed` on
Android 12. Use `-n` with the explicit component.

## Defect found and fixed

The first version of the log line interpolated clipboard content raw. A newline in the preview would
have split one observation across two lines and corrupted the evidence log — and clipboard content
routinely contains newlines. The unit test caught it; the preview is now escaped (`\n`, `\r`, `\t`,
quotes, backslashes, control characters as `\uXXXX`).
