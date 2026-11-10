# P06 — macOS pasteboard monitoring and the access prompt

**Status: completed on macOS 27.0.1, with a documented platform limitation.** On this OS the
pasteboard is **alwaysAllow** and no access prompt appears for either `changeCount` polling or a
real `string(forType:)` read. This holds for the ad-hoc build **and** for an Apple Development-signed
build, and the TCC log contains no pasteboard service at all. The only observable state is
**allowed**; `ask`/`denied` are not reachable on this macOS version. That is the definitive result
for this OS, not a missing test. The per-state UX must be re-test on a macOS version that actually
implements the prompt (the 15.4-era behaviour).

| Field | Value |
|---|---|
| Date | 2026-11-09 |
| Mac | MacBookAir10,1, macOS 27.0.1 (26A434), arm64 |
| Xcode | 27.2 (27B52) |
| App bundle | `com.droidbridge.macos` (after the P06 bundle-ID fix below) |
| Signing | ad-hoc, App Sandbox on (`app-sandbox`, `network.client`, `network.server`) |
| USB / debugging | not applicable — Mac-side test |
| Test surface | bundled app, GUI P06 tab + `--p06-harness` mode |

## What was tested

The app polls `NSPasteboard.general.changeCount` every 500 ms and performs a real
`string(forType: .string)` read only when the count changes. The harness writes only length and
SHA-256 prefix to the log; clipboard content is never logged.

| Case | Observation |
|---|---|
| Cold start, GUI window, `--p06-monitor` | `MONITOR start accessBehavior=alwaysAllow` |
| External `pbcopy` while app foreground | `CHANGE selfWrite=false`, `READ result=text`, no prompt |
| External `pbcopy` while another app (Finder) is front | read succeeds, `accessBehavior=alwaysAllow` |
| App writes synthetic test text | `WRITE`, then `CHANGE selfWrite=true suppressed=true`; no read-back |
| `tccutil reset Pasteboard com.droidbridge.macos` | reports success; behavior still `alwaysAllow` |
| `tccutil reset All com.droidbridge.macos` | reports success; behavior still `alwaysAllow` |
| LaunchServices launch vs direct binary | same result |
| Apple Development-signed build (not ad-hoc) | same result: `alwaysAllow`, no prompt |
| TCC log service list | no pasteboard service is requested or denied on this OS |
| Requesting a manual read | succeeds with text; no alert |

### CPU / busy-loop check

Ten-minute harness run: **1,200 polls** at 500 ms, **0 reads** (the clipboard was not changed) and
**0.0% CPU** in the `ps` sample at nine minutes. The timer is 2 Hz, not a busy loop.

## Findings

1. **No prompt on this configuration.** Both the GUI and harness started with
   `accessBehavior=alwaysAllow`; neither `changeCount` polling nor a real read triggered an alert,
   even after resetting the app's Pasteboard and All TCC state. The expected macOS 15.4+ `ask`
   state did not appear.
2. **Automatic monitoring is viable here.** An external copy was detected and read within one poll
   interval (~0.5 s), foreground and background, with no user interaction.
3. **Write suppression works.** The app's own write was observed as a `changeCount` change and
   suppressed with `selfWrite=true`; no echo read occurred. This is the clipboard loop-prevention
   primitive the product needs.
4. **No content is logged.** The evidence line carries `len`, `bytes` and a SHA-256 prefix only.
5. **The deny/allow UX is still open.** Because `ask`/`alwaysDeny` could not be induced, the
   required per-state UX and the "Don't Allow returns nil" test have no evidence. The result is
   recorded as partial, not as a pass for the full P06 matrix.
6. **Configuration defect found and fixed.** `macos/project.yml` had two `settings:` keys for the
   app target. YAML kept the second, so the bundle ID/product/Info.plist settings were silently
   discarded and the built app was `com.droidbridge.DroidBridge`, not the documented
   `com.droidbridge.macos`. The two blocks are now merged and the binary is signed as
   **`com.droidbridge.macos`**. This matters for TCC, keychain groups and P15.
7. **This is an OS limitation, not a signing limitation.** Re-signing the development bundle with
   the Apple Development identity (`com.droidbridge.macos`, Team `BJ57AFQQD6`) did not change the
   result. TCC logs contain no pasteboard service requests. Do not claim the 15.4-era prompt UX on
   macOS 27.0.1.

## What was built

- `macos/Core/PasteboardMonitor.swift` — 500 ms timer, `changeCount` polling, real reads only on
  change, `accessBehavior` reporting, self-write suppression, no content logs.
- `macos/Features/Pasteboard/PasteboardView.swift` — P06 tab with Start/Stop/Write/Read/Clear and
  an evidence log.
- `macos/Core/P06Harness.swift` — deterministic `--p06-harness` run for scripted evidence.
- `macos/App/DroidBridgeApp.swift` — third tab, auto-start flag, harness entry.
- `macos/project.yml` — merged target settings; correct bundle identifier.

## Acceptance status

| Criterion | Status |
|---|---|
| Definitive statement of which operation triggers the alert | **Pass for this config: neither polling nor a read triggers one; alwaysAllow** |
| Per-state UX (undetermined/allowed/denied) | **Allowed only on macOS 27.0.1; ask/deny not implemented/reachable — documented limitation** |
| No busy loop | **Pass — 500 ms timer; CPU check below** |
| No sync echo | **Pass — self-write suppressed** |
| No clipboard content in logs | **Pass — length/hash only** |
| If a prompt appears on every read, fall back to manual paste | **Not applicable — no prompt appeared** |

P06 is **completed for macOS 27.0.1**: the only state is allowed, and that is recorded as a
platform limitation. The deny/ask UX must be re-tested on a macOS version that implements the
pasteboard prompt; it is not claimed here.

## Reproducing

```bash
cd macos
xcodegen generate
xcodebuild -project DroidBridge.xcodeproj -scheme DroidBridge -configuration Debug \
           -derivedDataPath build build

# Reset only this app's pasteboard TCC state, then launch the GUI:
tccutil reset Pasteboard com.droidbridge.macos
open -n build/Build/Products/Debug/DroidBridge.app --args --p06-monitor

# Trigger an external copy and inspect the unified log:
printf 'p06-external-sample' | pbcopy
log show --last 1m --info --debug \
  --predicate 'subsystem == "com.droidbridge.macos" AND category == "P06Pasteboard"' --style compact

# Or run the deterministic harness with self-write suppression:
build/Build/Products/Debug/DroidBridge.app/Contents/MacOS/DroidBridge \
  --p06-harness --p06-self-write --p06-seconds 60
```

The harness also prints `[p06] ...` to stderr for shell capture. No clipboard content is included.
