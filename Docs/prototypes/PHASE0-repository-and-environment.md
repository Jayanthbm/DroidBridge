# Phase 0 — Repository and environment audit

**Date:** 2026-11-09
**Owner:** Local Development Agent
**Status:** Complete. Both native shells build from a clean checkout; toolchain and device baseline
recorded; permission and entitlement audits performed against the actual built artifacts.

Companion documents: `Docs/prototypes/P00-environment-report.md` (host/device baseline) and
`Docs/PERMISSIONS_MATRIX.md` §6 (audit diff).

## 1. What Phase 0 required

From `PHASES_AND_ACCEPTANCE.md`:

- Inspect repository, toolchains and actual device/host environment. — done, P00 report.
- Create native Android and macOS project shells. — done.
- Establish shared module/build structure and scripts/CI where practical. — done.
- Verify clean builds from a clean checkout. — done.
- No production feature implementation. — honoured; see §5.

Exit criteria: both shells build cleanly; actual tool/device versions recorded; permission and
repository audits documented; focused commit pushed to `development`.

## 2. Repository structure created

```text
android/
├── settings.gradle.kts          # modules + composite build of shared/protocol
├── build.gradle.kts             # shared SDK levels + lint policy for all modules
├── gradle.properties
├── gradle/libs.versions.toml    # single source of truth for versions AND sdk levels
├── lint.xml                     # documented suppressions only
├── gradlew, gradlew.bat, gradle/wrapper/
├── app/                         # application shell
├── core/{common,protocol,network,pairing,security}/
└── feature/{dashboard,clipboard,mirroring,settings}/
macos/
├── project.yml                  # xcodegen source of truth
├── DroidBridge.xcodeproj/       # generated, committed so the repo opens without xcodegen
├── App/                         # DroidBridgeApp.swift, Info.plist, entitlements
├── Core/                        # EnvironmentSummary.swift
├── Features/Dashboard/          # DashboardView.swift
└── Tests/                       # EnvironmentSummaryTests.swift
scripts/
├── env-baseline.sh              # regenerates the P00 baseline
├── verify-all.sh                # Phase 0 gate / CI entry point
└── android-install.sh           # development install helper
.github/workflows/verify.yml     # shared protocol + Android on a clean runner
```

### 2.1 Why `shared/protocol` is a composite build, not a module

`shared/protocol` stays a standalone Kotlin/JVM build so it can be tested anywhere a JDK exists,
including the cloud agent and CI, with no Android SDK. The Android app consumes it through
`includeBuild("../shared/protocol")` with a dependency substitution, so there is exactly one copy of
the framing rules rather than a duplicated module.

`android/core/protocol/MediaFraming.kt` is a thin facade over it. Its purpose is to make the
linkage a **compile-time** fact: if the substitution ever breaks, that file stops compiling instead
of the app silently building against a stale artifact.

### 2.2 Why the Xcode project is generated and committed

`project.yml` is the source of truth; `xcodegen generate` produces `DroidBridge.xcodeproj`. The
generated project is committed so the repository opens in Xcode without requiring xcodegen, and
`scripts/verify-all.sh` regenerates it before building so the committed project cannot silently
drift from the spec.

## 3. Build and test evidence

| Check | Result |
|---|---|
| `shared/protocol` tests | 31 passed, 0 failed |
| Android `:app:assembleDebug` | BUILD SUCCESSFUL |
| Android `:app:lintDebug` | BUILD SUCCESSFUL, 0 errors (5 initial findings fixed, see §4) |
| macOS Debug build | BUILD SUCCEEDED |
| macOS Release build | BUILD SUCCEEDED |
| macOS unit tests | 4 passed, 0 failed |
| Clean-checkout rebuild (all three) | passed |
| Install + launch on OnePlus Nord CE (Android 12) | installed, launched, resumed, no crash, no ANR |

The clean-checkout test copied only git-tracked and non-ignored files into a fresh temporary
directory — no `build/`, no `.gradle/`, no `local.properties` — and rebuilt from there. The Android
build resolved the SDK from `ANDROID_HOME` alone, which is what CI does.

## 4. Findings that changed the build

Each of these was a real failure or a real defect, not a preference.

1. **AGP 9 rejects the separate Kotlin plugin.** `org.jetbrains.kotlin.android` fails with
   *"no longer required for Kotlin support since AGP 9.0"*. Removed from every module; Compose keeps
   `org.jetbrains.kotlin.plugin.compose`.
2. **`compileSdk` must be set per module.** The root `subprojects` convention block configured
   libraries but the application module still failed with *"project ':app' does not specify
   `compileSdk`"*. Added an application-specific block.
3. **AGP 9 deprecated the legacy DSL extension types.** `com.android.build.gradle.LibraryExtension`
   warns and is removed in AGP 10; switched to `com.android.build.api.dsl.*`.
4. **Release builds carried `get-task-allow`.** Xcode injects it into ad-hoc-signed builds, and the
   notary service rejects a shipped build containing it (`PERMISSIONS_MATRIX.md` §4). Fixed with
   `CODE_SIGN_INJECT_BASE_ENTITLEMENTS: NO` on Release only, and verified by reading the signed
   entitlements back out of the built bundle.
5. **Five lint errors, all legitimate.** A redundant activity label, an obsolete `-v26` resource
   qualifier (minSdk is 31), a missing monochrome adaptive-icon layer, a hard-coded dependency
   coordinate instead of the version catalog, and the wrapper-version check. All fixed at the source
   except the wrapper check, which is suppressed in `lint.xml` with a written reason.

## 5. Scope discipline — what was deliberately not built

Phase 0 says "no production feature implementation", and the shell respects that:

- No networking, no discovery, no TLS, no pairing.
- No clipboard code, no MediaProjection, no encoder.
- No foreground services, no notifications.
- No dead UI affordances: the dashboard shows the environment baseline only. There is no
  "Connect" button, because a button that cannot connect is a false claim about product state.

The `core:network`, `core:pairing`, `core:security`, `feature:clipboard`, `feature:mirroring` and
`feature:settings` modules exist with their build files and namespaces but **no source**. They are
the documented structure from `SYSTEM_ARCHITECTURE.md` §2, created empty so the dependency graph is
real and a later phase fills a module rather than inventing one.

## 6. Permission and entitlement audit

### 6.1 Android — actual manifest diff

The Phase 0 manifest declares **no permissions**. Measured from the built APK
(`aapt2 dump permissions`), the only entry is:

```
com.droidbridge.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
```

That is a **self-defined, app-private signature permission** that AndroidX Core injects so its
dynamically registered broadcast receivers are not exported to other apps. It grants no access to
anything, is not in the `PERMISSIONS_MATRIX.md` candidate list, and cannot be requested by another
app. It is recorded here so the diff is honest rather than "zero permissions" when the artifact says
otherwise.

Every permission in `PERMISSIONS_MATRIX.md` §1 remains **undeclared**, which is correct for a shell
with no features:

| Permission | Matrix status | Manifest | Why not yet |
|---|---|---|---|
| `INTERNET` | Declare when networking exists | absent | no networking code |
| `ACCESS_LOCAL_NETWORK` | Conditional on P02 | absent | ADR-010 targets the picker path |
| `FOREGROUND_SERVICE*` | With mirroring / background sync | absent | no services |
| `POST_NOTIFICATIONS` | From the first FGS | absent | no notifications |
| `BLUETOOTH_*` | Optional, opt-in only | absent | ADR-016, not in MVP |
| `CHANGE_NETWORK_STATE` | With `connectedDevice` FGS | absent | no FGS |

**No permission was added in Phase 0.** No owner approval is required for this phase.

### 6.2 Android — other manifest security settings

| Setting | Value | Reason |
|---|---|---|
| `android:allowBackup` | `false` | No clipboard history, no cloud; pairing material must not travel via backup |
| `android:fullBackupContent` | `false` | Belt and braces with the rules file |
| `dataExtractionRules` | all domains excluded | Explicit for Android 12+ cloud backup and device transfer |
| `android:exported` | `true` on `MainActivity` only | Launcher activity; nothing else is exported |
| `android:debuggable` | debug builds only | Release build has no signing config, so it cannot be shipped by accident |

### 6.3 macOS — actual entitlements diff

Read back from the **signed Release bundle** with `codesign -d --entitlements :-`:

```xml
<key>com.apple.security.app-sandbox</key>      <true/>
<key>com.apple.security.network.client</key>   <true/>
<key>com.apple.security.network.server</key>   <true/>
```

Exactly the audited set in `PERMISSIONS_MATRIX.md` §3 and ADR-026. Specifically:

- `get-task-allow` is **absent** from Release (it is present in Debug, which is required for
  debugging and is not a shipping configuration).
- No `com.apple.security.cs.*` hardened-runtime exception is present.
- No `keychain-access-groups` yet — that arrives with the Developer ID provisioning profile in
  Phase 2 (ADR-025), and declaring it before it can be authorized would be a false claim.

Info.plist carries `NSLocalNetworkUsageDescription` and `NSBonjourServices` =
`["_droidbridge-dev._tcp"]`, matching `NETWORK_PROTOCOL.md` and ADR-022. Nothing advertises yet, so
the local-network prompt is not triggered in Phase 0.

### 6.4 macOS — signing state

| Item | Value |
|---|---|
| Identity | ad-hoc (`Signature=adhoc`, `TeamIdentifier=not set`) |
| Hardened runtime | requested in settings; Xcode notes it is disabled for ad-hoc signing |
| Notarisation | not attempted — requires a Developer ID certificate (Phase 9, ADR-026) |

Ad-hoc signing is the correct state for Phase 0. It is recorded explicitly so nobody mistakes this
build for a release candidate: an unnotarised build is not one.

## 7. Repository hygiene

- `.gitignore` extended for lint reports, provisioning profiles, `.vscode/` and `.perch/`.
- No build output, no `local.properties`, no keystore, no `.env` is tracked. Verified by listing
  every file that would be committed before committing.
- No secrets were added. One pre-existing secret was **found** outside the repository
  (`~/.gradle/gradle.properties`) and is reported to the owner in the P00 report §7.
- CI uses `permissions: contents: read` and holds no credentials.

## 8. Tests that were not run, and why

| Test | Why not |
|---|---|
| Any Android instrumentation test | No instrumentation test exists yet; the shell has no behaviour worth driving |
| Any test on the S24 Ultra | Device not attached |
| Any test with USB disconnected | No runtime behaviour exists yet to test |
| macOS local-network prompt behaviour | Nothing advertises yet (P11) |
| Notarisation / Developer ID signing | No certificate; Phase 9 (P15) |
| Intel macOS build | Not claimed; arm64 only |

## 9. Phase 0 exit assessment

| Criterion | Status |
|---|---|
| Both native shells build cleanly | **Met** — Android and macOS, including from a clean checkout |
| Actual tool and device versions recorded | **Met** — P00 report; S24 Ultra explicitly pending |
| Permission and repository audits documented | **Met** — §6, §7 |
| Focused commit pushed to `development` | **Met** — see the commit for this phase |

Phase 0 is complete. Phase 1 (feasibility prototypes P00–P15) is next; P00's host/device half is
done above, and the prototype work proper begins with P01 (MediaProjection).
