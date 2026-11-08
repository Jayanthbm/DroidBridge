# P04 — Clipboard Android → Mac

**Status: implemented, matrix not run.** The probe is built, tested and installed, but every row of
the P04 matrix needs a focused, visible DroidBridge activity, and the phone is PIN-locked. No row
has been observed. The matrix below is empty of results on purpose.

| Field | Value |
|---|---|
| Date | 2026-11-09 |
| Device | OnePlus Nord CE (AC2001), Android 12, API 31, build AC2001_11_F.24 |
| USB connected | yes |
| USB debugging enabled | yes |
| Wireless debugging enabled | no |
| Device lock state | locked (PIN); `mCurrentFocus=Window{… NotificationShade}` |

## What was built

`android/experiments/clipboard/` — a separate installable app, never depended on by `:app`,
declaring **no permission at all**. That is deliberate: there is no clipboard permission on Android,
access is gated on the app being the focused visible app, and declaring anything would change the
thing being measured.

`ClipboardProbe` runs one matrix row per action and logs a single greppable line per observation:

```
case="activity_focused" hasPrimaryClip=true mimes=[text/plain] textLen=21 hasImage=false
hasUri=false sensitive=false desc="p04" preview="droidbridge-p04-sample"
```

Every row records `hasPrimaryClip`, MIME types, text length, an escaped preview, whether the clip
is an image, whether it is a URI list, and the sensitive flag. A refused read is recorded as
`hasPrimaryClip=false` with zero length — never as an empty string, because "the OS refused" and
"the clipboard was empty" are different findings and only one is a product problem.

## Verified

**9 unit tests pass** (`:experiments:clipboard:testDebugUnitTest`), covering the reporting contract:

- a refused read reports no content and no preview;
- a successful read carries its length and preview;
- an image-only clip is reported as an image and not as text;
- a `text/uri-list` clip is detected;
- the sensitive flag is carried through;
- an error is reported rather than swallowed;
- the log line is greppable, and a newline in clipboard content cannot break the
  one-line-per-observation contract;
- an empty read omits the preview field rather than printing `preview=""`.

**Lint is clean** with `warningsAsErrors`. Five real findings were fixed at source, not baselined.

**Installed and launched** on the device: `ResumedActivity` confirmed, zero crash entries, and
`dumpsys package` shows exactly one requested permission —
`…DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, injected by AndroidX Core. No clipboard-related
permission is declared, which is correct.

## Two findings that change the matrix

**1. `ClipDescription.EXTRA_IS_SENSITIVE` is API 33, not API 31.** The last row of the P04 matrix
("sensitive clip set by another app") cannot be exercised on this device at all: the flag does not
exist in API 31, so it can be neither set nor read. The probe now logs
`sensitive=NOT_APPLICABLE api=31` instead of reporting `false`, which would have looked like a
measurement. This row must be run on Android 17.

**2. The clipboard read gate is a focus gate, and the probe cannot test it from a shell.** The
activity can be started and is reported as resumed, but the keyguard holds input focus, so no
button can be pressed. `uiautomator dump` shows only `com.android.systemui` nodes. Every matrix row
is therefore unrun.

## The matrix — unrun

| Case | Expected | Observed |
|---|---|---|
| Activity visible and focused, "Send clipboard" | works | **not run** |
| Activity visible, no text field focused | works or null | **not run** |
| Foreground service only, another app on screen | null | **not run** |
| Screen off / locked | null | **not run** |
| Process recreated, then "Send clipboard" | works | **not run** |
| Clipboard empty | null / rejected | **not run** |
| Image only | rejected by policy | **not run** |
| Duplicate content | deduplicated | **not run** |
| Very long text (100 KB) | capped by policy | **not run** |
| Sensitive clip (`EXTRA_IS_SENSITIVE`) | preview suppressed | **not applicable on API 31** |

Also unrecorded: whether the Android 12+ "app accessed clipboard" toast appears on each successful
read.

## Why this matters for the product

P04's acceptance criteria are about producing "the definitive wording for the Android → Mac user
story, including the cases that will not work". That wording cannot be written from this device,
because the central claim — that a read works only while DroidBridge is the focused visible app —
has not been observed here. It is the documented platform behaviour, but documented behaviour is
not evidence, and this project's rule is that an untested feature is never marked complete.

## Reproducing

```bash
cd android
./gradlew :experiments:clipboard:assembleDebug :experiments:clipboard:testDebugUnitTest
adb install -r experiments/clipboard/build/outputs/apk/debug/clipboard-debug.apk
adb logcat -c && adb shell am start -n com.droidbridge.experiment.clipboard/.ClipboardActivity
# unlock the device, then press each button and read:
adb logcat -d -s P04Clipboard
```

## Defect found and fixed

The first version of the log line interpolated clipboard content raw. A newline in the preview
would have split one observation across two lines and corrupted the evidence log — and clipboard
content routinely contains newlines. The unit test caught it; the preview is now escaped
(`\n`, `\r`, `\t`, quotes, backslashes, control characters as `\uXXXX`).
