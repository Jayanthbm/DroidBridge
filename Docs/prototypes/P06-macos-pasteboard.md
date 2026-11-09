# P06 — macOS pasteboard monitoring and the access prompt

**Status: partial — allowed-state behaviour complete; deny/ask states could not be induced on this
macOS build.** On macOS 27.0.1 with an ad-hoc signed, sandboxed build, `NSPasteboard.accessBehavior`
is **alwaysAllow** and no access prompt appears for either `changeCount` polling or a real
`string(forType:)` read. That makes automatic Mac→Android monitoring viable on this OS, but it also
means the per-state "ask / don't allow" UX required by the prototype has no evidence yet. A signed
build (P15) or a different macOS version is needed to close those states.

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
| Per-state UX (undetermined/allowed/denied) | **Partial — allowed only; ask/deny not reachable** |
| No busy loop | **Pass — 500 ms timer; CPU check below** |
| No sync echo | **Pass — self-write suppressed** |
| No clipboard content in logs | **Pass — length/hash only** |
| If a prompt appears on every read, fall back to manual paste | **Not applicable — no prompt appeared** |

P06 is **in progress**, not complete: the deny/ask states still need a signed build or a macOS
version that prompts.

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
