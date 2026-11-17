# P15 — Sandboxed macOS build: personal-use validation and distribution deferral

**Status: personal-use identity persistence implemented and verified locally (2026-11-17); Developer ID distribution and notarisation explicitly deferred by the owner.** The owner confirmed this app is for personal use and does not have an Apple Developer Program account. This is not a notarized release and must not be described or distributed as one.

| Field | Value |
|---|---|
| Date | 2026-11-17 |
| Mac | MacBook Air M1, macOS 27.2.1 (26B131), arm64 |
| Xcode | 27.2 (27B52) |
| Bundle | `com.droidbridge.macos` |
| Build | Release, Xcode “Sign to Run Locally” / ad-hoc |
| Hardened runtime | yes |
| S24 / USB | Not involved; Mac-only test |
| Apple Developer Program membership | Not available; not required for this local-build check |
| Developer ID / notarisation | Explicitly deferred; no signing identity or notarisation credentials used |

## Local build and launch results

Built from the repository checkout with:

```bash
xcodebuild -project macos/DroidBridge.xcodeproj -scheme DroidBridge \
  -configuration Release -derivedDataPath macos/build build
```

Artifact: `macos/build/Build/Products/Release/DroidBridge.app`.

| Check | Result |
|---|---|
| Release build | **Pass** |
| Local `open -n` launch from the build output | **Pass** on this Mac; the Release executable was observed running |
| `codesign --verify --deep --strict --verbose=2` | **Pass** — valid on disk and satisfies its Designated Requirement |
| Signature | **Ad-hoc**, `TeamIdentifier=not set`, hardened-runtime flag present |
| Bundle identifier | `com.droidbridge.macos` |
| Entitlements embedded in the app | Exactly `app-sandbox`, `network.client`, `network.server` |
| `get-task-allow` | Absent |
| Gatekeeper `spctl -a -vv` | **Rejected** (exit 3), expected for this unnotarized ad-hoc artifact |
| Quarantined-download launch | **Not run**; this local build output had no quarantine attribute |
| Persistent login-Keychain identity | **Pass** — two separate Release `--identity` processes exited 0 and reported pin `2178541711aede012c381f584e5ef2e1e39baf5b1b8772c3305005c6f7ff9ffb` |
| Automated persistence/failure tests | **Pass** — isolated disposable-Keychain tests; full macOS suite 67/67 |
| Peer reconnect without re-pairing | **Not run** — Android reconnect against the persisted Mac identity remains outstanding |
| Developer ID signing, notarisation, stapling | **Not run; deferred by owner** |

The successful local launch and Gatekeeper's rejection are both recorded: the former demonstrates this build ran from the local build output on this Mac, while the latter means it is not an acceptable notarized distribution artifact. No Gatekeeper setting or security control was changed to run it.

## Identity implementation and evidence

The baseline Release implementation produced different pins across separate `--identity` processes. The corrected implementation uses an app-specific, versioned P-256 private-key application tag in the user's default login Keychain and stores the self-signed certificate in that Keychain. It resolves the certificate using the generated `DroidBridge` label and exact public-key application-label match. The private key query is explicitly scoped to the selected login Keychain; the certificate lookup temporarily restricts the legacy Keychain search list to that same Keychain and restores it afterwards. Identity resolution separately uses the normal search list with the selected Keychain included, then restores it. No Keychain synchronization is requested.

A new key is created only if the tagged key is absent. If the key exists but no unique matching certificate is found, or another Keychain operation fails, initialization fails closed instead of silently changing the Mac identity. The isolated tests use disposable Keychains and verify repeated loads retain the pin and an orphan key does not trigger replacement. The Release harness was then run twice as separate processes against the real login Keychain; both exited successfully and produced identical pins. This demonstrates persistence across process launches on this Mac.

An earlier failed experiment's generated Keychain key item remains untouched. This implementation uses a new v2 application tag and does not inspect, adopt, delete, or overwrite that prior item. No Keychain items were deleted or overwritten. Android reconnect using its existing stored peer pin has not yet been tested, and Keychain ACL behavior across a future code-signing identity change is not established.

## Scope decision and remaining distribution limitation

The current goal is personal use on the owner's Mac, not distribution. Continue developing and testing the sandboxed local Mac build without requiring an Apple Developer Program membership. The ad-hoc artifact is for this local workflow only; do not claim it will launch as a downloaded/quarantined app on another Mac.

If distribution becomes a goal, reopen P15 and complete Developer ID signing with the hardened runtime, notarisation, stapling, and clean/quarantined-machine launch checks. Phase 9's distribution gates remain unchanged. The P15 distribution portion is deferred rather than passed. The personal-use identity path is verified only for the current local ad-hoc build; persistence and ACL behavior after Developer ID signing or re-signing need separate validation.

## Reproducing the local-use check

```bash
xcodebuild -project macos/DroidBridge.xcodeproj -scheme DroidBridge \
  -configuration Release -derivedDataPath macos/build build
APP=macos/build/Build/Products/Release/DroidBridge.app
codesign --verify --deep --strict --verbose=2 "$APP"
codesign -d --entitlements :- "$APP"
codesign -dv --verbose=4 "$APP"
spctl -a -vv "$APP"   # expected: rejected for the ad-hoc, unnotarized artifact
open -n "$APP"        # local-use launch check; not a distribution test
"$APP/Contents/MacOS/DroidBridge" --identity  # run in separate processes; pins should match for this Keychain
```
