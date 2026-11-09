# P15 — Sandboxed, signed and notarised macOS build

**Status: in progress — sandbox/entitlements/Release-build checks pass; Developer ID signing and
notarisation are blocked by the missing Apple Developer Program membership.** The dry run proves
the Release artifact carries exactly the audited entitlements and no `get-task-allow`, and that
Gatekeeper rejects it as expected for an ad-hoc build.

| Field | Value |
|---|---|
| Date | 2026-11-09 |
| Mac | MacBookAir10,1, macOS 27.0.1 (26A434), arm64 |
| Xcode | 27.2 (27B52) |
| Bundle | `com.droidbridge.macos` (fixed in P06) |
| Build | Release, ad-hoc (`CODE_SIGN_IDENTITY = "-"`) |
| Hardened runtime | yes |
| Notarisation | **not run — no Apple Developer Program membership** |

## Dry-run results

```bash
xcodebuild -project DroidBridge.xcodeproj -scheme DroidBridge \
           -configuration Release -derivedDataPath build build
codesign --verify --deep --strict --verbose=2 build/Build/Products/Release/DroidBridge.app
codesign -d --entitlements :- build/Build/Products/Release/DroidBridge.app
codesign -dv --verbose=4 build/Build/Products/Release/DroidBridge.app
spctl -a -vv build/Build/Products/Release/DroidBridge.app
```

| Check | Result |
|---|---|
| Release build | **Pass** |
| `codesign --verify --deep --strict` | **valid on disk / satisfies its Designated Requirement** |
| Signature | ad-hoc, hardened runtime flag, `TeamIdentifier=not set` |
| Entitlements | exactly `app-sandbox`, `network.client`, `network.server` |
| `get-task-allow` | **absent** (Release injection disabled) |
| Gatekeeper (`spctl`) | **rejected** — expected for an unnotarised ad-hoc build |
| Developer ID signing | **not run** — no membership/identity |
| Notarisation + stapling | **not run** — no membership/credentials |

## Findings

1. **The audited entitlement set is exact in Release.** No hardened-runtime exceptions, no
   `get-task-allow`, and no keychain group (that arrives with the provisioning profile in Phase 2).
2. **The bundle identifier defect is fixed.** P06 found the target was built as
   `com.droidbridge.DroidBridge` because `project.yml` had duplicate `settings:` keys. It is now
   `com.droidbridge.macos`, matching the docs and entitlements/TCC expectations.
3. **Gatekeeper rejection is the correct negative result** for an ad-hoc build; it is not evidence
   that the notarisation path works.
4. **The release dependency is external.** Developer ID signing and notarisation require the paid
   Apple Developer Program membership; the Owner must supply that before P15 can pass.

## Acceptance status

| Criterion | Status |
|---|---|
| Exact entitlement set that works sandboxed | **Pass for the local ad-hoc build** |
| Difference between ad-hoc and Developer ID builds | **Blocked — no Developer ID identity** |
| Notarisation path proven end to end with stapled ticket | **Blocked — no membership** |
| U14/U17 definitive answers | **Partial** — local sandbox/entitlement facts recorded; signing/TCC differences need a Developer ID build |

P15 is **in progress** and cannot be completed without the paid Apple Developer Program membership.

## Reproducing

```bash
cd macos
xcodegen generate
xcodebuild -project DroidBridge.xcodeproj -scheme DroidBridge \
           -configuration Release -derivedDataPath build build
APP=build/Build/Products/Release/DroidBridge.app
codesign --verify --deep --strict --verbose=2 "$APP"
codesign -d --entitlements :- "$APP"
codesign -dv --verbose=4 "$APP"
spctl -a -vv "$APP"   # expected: rejected until notarised
```
