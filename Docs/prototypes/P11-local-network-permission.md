# P11 — macOS Local Network permission and Bonjour

**Status: in progress — allowed-state advertising/discovery observed on macOS 27.2.1; first-launch prompt, denied-state recovery and network/lifecycle cases remain untested.** This report records a contained local run, not a complete P11 acceptance.

## Evidence header

| Field | Value |
|---|---|
| Date | 2026-11-17 |
| Repository commit | `5eb3821` (`development`; UI recovery change is currently uncommitted) |
| Mac | MacBook Air (M1, 2020), `MacBookAir10,1`, arm64 |
| macOS | 27.2.1 (`26B131`) |
| Xcode | 27.2 (`27B52`) |
| App build | Release, ad-hoc “Sign to Run Locally”; runs from `macos/build/Build/Products/Release/DroidBridge.app` and the canonical `~/Applications/DroidBridge.app` copied from it |
| App identity | Initial run used `com.droidbridge.macos`; final canonical run used `com.droidbridge.app` |
| Entitlements checked | App Sandbox, `network.client`, `network.server` all present in the source entitlement file |
| Usage description | Present: “DroidBridge connects to your Android phone over your local Wi-Fi network to sync the clipboard and mirror the screen. Nothing is sent to the internet.” |
| Bonjour declaration | `NSBonjourServices` contains `_droidbridge-dev._tcp` |
| Wi-Fi | `networksetup -getairportnetwork en0` reported “You are not associated with an AirPort network.” |
| Bluetooth | Not used or measured |
| USB / USB debugging / wireless debugging | Not applicable to this Mac-only test; no Android device used |

## Steps and actual results

1. Checked the app's `Info.plist` for `NSLocalNetworkUsageDescription` and `NSBonjourServices`, and checked `DroidBridge.entitlements` for sandbox, client and server declarations. **All expected declarations were present.** The API service type constant is `_droidbridge-dev._tcp`, matching both plist and `NSBonjourServices`.
2. Launched the bundled Release app with `--advertise-on-launch`. The process remained running. Unified logging recorded `DNSServiceRegister START`; the app listener bound TCP port `59555` in the initial run. After the `.waiting`-state handling change, a second smoke run from the rebuilt Release product resolved successfully on port `59558`.
3. Ran `dns-sd -B _droidbridge-dev._tcp local.`. It listed `DroidBridge` on interfaces 1 and 11.
4. Ran `dns-sd -L DroidBridge _droidbridge-dev._tcp local.`. Initial runs with the previous bundle ID resolved on ports `59555` and `59558`. The final canonical app with bundle ID `com.droidbridge.app` resolved alone on port `59559` after the previous advertiser was stopped. TXT fields were `sid=<session id>` and `v=1`.
5. After confirming the canonical app had started advertising, stopped the older process at `macos/build/Build/Products/Release/DroidBridge.app` by its exact PID/path. A subsequent resolve still found the canonical app alone on port `59559`, with its `sid` and `v=1` TXT fields. This separates the final Bonjour result from the old process.
6. The native desktop-control capability was unavailable, so the Local Network alert (if shown) could not be visually inspected or interacted with; prompt presence and wording are **unverified**. Because advertising succeeded, local-network access was effective for this process. This run does **not** determine whether the account's decision was already allowed, whether a prompt was shown and answered, or what the first-launch prompt says. TCC was not reset and no permission setting was changed.
7. The app's failure state previously displayed its error without a recovery route. The current change adds explanatory Local Network copy and an **Open System Settings** button when advertising is in `.waiting` or `.failed`; the user is directed to Privacy & Security → Local Network. This UI was build-verified but the denied path and Settings navigation were not exercised.

## Automated validation

- `xcodebuild ... -configuration Debug ... test`: **67/67 tests passed**, 0 failures, on macOS 27.2.1 / arm64.
- Release build: **passed** with Xcode 27.2. The build emitted existing Swift deprecation/unnecessary-`try` warnings in identity and TLS code; no build failure.
- `dns-sd` verification: service browse and resolve passed under the effective allowed state, including the canonical `com.droidbridge.app` bundle after stopping the previous app process. The Release bundle also contains the expected usage-description and Bonjour service declarations.
- No Android peer was present, so Android NSD picker visibility was not checked in this run.

## Not tested

- Visually confirm the fresh first-launch alert and record its exact wording; the native desktop-control capability was unavailable during this run.
- A denied permission, the actual `.failed` reason under denial, and recovery by allowing access in System Settings.
- Whether the app appears under Local Network settings after first grant/denial.
- Android picker discovery, a second macOS user account, Mac sleep/wake, or a Wi-Fi/interface change.
- Developer ID build behavior; the observed app is ad-hoc and the existing research warns that TCC decisions may not track reliably across ad-hoc signature changes.

## Conclusion

**P11 remains in progress.** The required declaration/service-type matching and Bonjour advertising/resolution work in the current effective allowed state. The exact first-launch prompt and denied-state UX remain unknown, and so P11 acceptance (“documented allowed/denied UX” and “clear failure without permission”) is not met. No permission reset, System Settings change, USB, or Android-device action was performed.
