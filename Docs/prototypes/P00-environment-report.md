# P00 — Environment and device baseline

**Date:** 2026-11-09
**Owner:** Local Development Agent
**Phase:** 0 (repository and environment)
**Status:** Complete for the host and **both** devices. The S24 Ultra baseline is now measured (section 5.2); the OnePlus Nord CE remains recorded separately as a temporary-device baseline (section 5.1).

Regenerate this report with `scripts/env-baseline.sh` rather than editing the numbers by hand.

## 1. Host

| Item | Value |
|---|---|
| macOS | 27.0.1 (build 26A434) |
| Architecture | arm64 (Apple Silicon) |
| Hardware model | MacBookAir10,1 |
| CPU cores | 8 |
| Memory | 8 GB |
| Hostname class | MacBook Air (M1, 2020) |

## 2. Apple toolchain

| Item | Value |
|---|---|
| Xcode | 27.2 (build 27B52) |
| Developer directory | `/Applications/Xcode.app/Contents/Developer` |
| Swift | Apple Swift version 6.4.2 (swiftlang-6.4.2.1.9, clang-2100.4.1.1) |
| macOS SDK | 27.2 |
| xcodegen | 2.46.0 |

The macOS shell targets `arm64` only and a deployment target of macOS 15.0. Intel support is not
claimed, because it has not been built or tested (`MACOS_APP_SPEC.md` §1).

## 3. JVM and Gradle toolchain

| Item | Value |
|---|---|
| `java` on PATH | OpenJDK 17.0.21.0.3 LTS (Zulu 17.70+113) |
| `JAVA_HOME` | unset |
| Installed JVMs | 1 (Zulu 17, arm64) |
| Gradle on PATH | 9.8.2 |
| Gradle wrapper (Android) | 9.8.2 |
| Gradle wrapper (shared/protocol) | 8.10.2 (pre-existing, unchanged) |
| Kotlin (via AGP 9.5.1 built-in) | 2.4.x |
| Android Gradle Plugin | 9.5.1 |

Two facts worth recording because they changed the build:

1. **AGP 9 has built-in Kotlin support.** Applying `org.jetbrains.kotlin.android` alongside it
   fails the build outright: *"The 'org.jetbrains.kotlin.android' plugin is no longer required for
   Kotlin support since AGP 9.0."* The Android modules therefore apply only
   `com.android.application` / `com.android.library` plus `org.jetbrains.kotlin.plugin.compose`.
2. **The Gradle daemon runs on JDK 27** (Homebrew) while the Android modules compile against
   Java 17 bytecode and the shared module uses a JDK 21 toolchain, which Gradle provisions itself
   into `~/.gradle/jdks`. This works, but it means the *build* JDK and the *toolchain* JDK are
   different; both are recorded here so a future failure is diagnosable.

## 4. Android SDK

| Item | Value |
|---|---|
| SDK root | `~/Library/Android/sdk` |
| Platforms | `android-34`, `android-36`, `android-37.0` |
| Build-tools | `34.0.0`, `35.0.0`, `36.0.0`, `37.0.0` |
| cmdline-tools | `latest` |
| adb | 1.0.41 (37.0.2) |
| Emulators | one AVD (`Wear_OS_Large_Round`) — not usable for this product |

`android-37.0` reports `AndroidVersion.ApiLevel=37.0` and `Pkg.Desc=Android SDK Platform 17`, i.e.
Android 17 / API 37. `compileSdk = 37` resolves against it, and the built APK reports
`platformBuildVersionCode='37'`, `minSdkVersion:'31'`, `targetSdkVersion:'37'`.

## 5. Device baseline

### 5.1 OnePlus Nord CE (temporary test device) — measured

| Item | Value |
|---|---|
| Serial | `b6774a1d` |
| Manufacturer / model | OnePlus / AC2001 |
| Device codename | `Nord` |
| Android release | 12 |
| SDK / API level | 31 |
| Security patch | 2023-12-05 |
| Build display id | `AC2001_11_F.24` |
| Build fingerprint | `OnePlus/Nord_IND/Nord:12/RKQ1.211119.001/Q.202510131819:user/release-keys` |
| `ro.debuggable` | `0` (production build, not a userdebug image) |
| Verified boot state | `orange` (bootloader unlocked) |
| Developer options | enabled (`development_settings_enabled=1`) |
| Wireless debugging | **disabled** (`adb_wifi_enabled=0`) |
| USB | connected (adb transport is USB) |
| USB debugging | enabled (required for the adb session used below) |

**Root status.** The owner describes this device as rooted. `ro.debuggable=0` and
`verifiedbootstate=orange` are consistent with an unlocked bootloader. Per the standing device
rules, it was treated as an ordinary non-rooted device throughout: no `su`, no Magisk, no
privileged or hidden APIs, and nothing in the app depends on root. The unlocked bootloader is
recorded because it affects how much a security result can be trusted on this device, not because
it was used.

### 5.2 Samsung Galaxy S24 Ultra (target device) — MEASURED

Attached and measured on 2026-11-09. Every value below came from the device via `getprop` or
`dumpsys`, not from the owner's report.

| Item | Value |
|---|---|
| Manufacturer | samsung |
| Model | **SM-S928B** |
| Device codename | `e3q` |
| Android release | **17** |
| API level (`SDK_INT`) | **37** |
| One UI | **9.0** (`ro.build.version.oneui=90000`) |
| Build | `CP2A.260605.016.S928BXXS6EZJ1` |
| Incremental | `S928BXXS6EZJ1` |
| Security patch | **2026-10-05** |
| Build type | `user` |
| Verified boot state | **`green`** (locked, unmodified) |
| Physical screen | 1080x2340, density 450 (override 480) |
| Wi-Fi | `wlan0` 192.168.31.246/24 |
| Bluetooth | on (not used by any prototype yet) |
| USB connected | yes |
| USB debugging enabled | **yes** (development only) |
| Wireless debugging enabled | **no** |

The owner-reported Android 17 / API 37 / One UI 9 are all **confirmed**. The bootloader is `green`
and the build type is `user`, so this is a stock retail device — no root, no unlocked bootloader, and
nothing in this session changed that.

#### API 37 platform APIs, verified against the physical device

P00 asks specifically whether the Android 17 local-network APIs exist on the target. They do:

| Check | Result |
|---|---|
| `android.net.nsd.DiscoveryRequest` in the API 37 platform jar | present, with `FLAG_SHOW_PICKER`, `FLAG_NO_PICKER`, `FLAG_USER_APPROVED_ONLY` |
| `NsdManager.discoverServices(DiscoveryRequest, Executor, DiscoveryListener)` | present |
| `NsdManager.registerService(AdvertisingRequest, Executor, RegistrationListener)` | present |
| `android.permission.ACCESS_LOCAL_NETWORK` declared by the platform | present ("access local network devices") |
| NSD system service | `servicediscovery: [android.net.nsd.INsdManager]` — found |
| **The picker actually appears at runtime** | **yes** — `com.google.android.connectivity.resources/.NsdPickerActivity` |

The last row is the one that matters. The class existing in the SDK stub proves nothing about the
device; the picker appearing proves the device implements it. See `P02-local-networking.md`.

#### The Android 17 local-network prompt wording

P00 asked for this specifically, and it is now recorded verbatim:

> **Choose a device to connect**
> P02 discovery wants to connect to a device on your local network
> *[list of discovered peers]*
> Don't connect

The prompt names the requesting app and states the scope ("a device on your local network"). It is
the OS's own activity, not app-drawn UI, so an app cannot fake or skip it.

Consequence, replacing the previous "every result is Android 12" statement: results are now split by
device. P01 and P04 have Android 12 **and** Android 17 results; P02 is Android 17 only, because the
API does not exist below 37. Each report states which device produced which figure.

## 6. What was actually built and run

| Check | Command | Result |
|---|---|---|
| Shared protocol tests | `shared/protocol/gradlew test` | 31 tests, 31 passed, 0 failed |
| Android debug APK | `android/gradlew :app:assembleDebug` | BUILD SUCCESSFUL |
| Android unit tests | `android/gradlew :app:testDebugUnitTest` | BUILD SUCCESSFUL (no test sources yet) |
| Android lint | `android/gradlew :app:lintDebug` | BUILD SUCCESSFUL, 0 errors after fixes |
| macOS build (Debug) | `xcodebuild … -configuration Debug build` | BUILD SUCCEEDED |
| macOS build (Release) | `xcodebuild … -configuration Release build` | BUILD SUCCEEDED |
| macOS unit tests | `xcodebuild … test` | 4 tests, 4 passed |
| Clean-checkout build | copied tracked files to a temp dir, rebuilt all three | all passed |
| Install on device | `adb install -r app-debug.apk` | Success |
| Launch on device | `adb shell am start -n com.droidbridge.app/.MainActivity` | running, `ResumedActivity` = `com.droidbridge.app/.MainActivity`, pid alive |
| Crash check | `adb logcat -d -b crash \| grep com.droidbridge.app` | 0 entries |
| ANR check | `adb logcat -d \| grep "ANR in com.droidbridge"` | 0 entries |

### 6.1 What could not be verified on the device

The app launched and held the resumed activity, but **no screenshot was captured**. The device was
sitting on a PIN-locked lock screen (`uiautomator` reported the keyguard keypad), so
`screencap` returned empty output and the UI hierarchy belonged to the keyguard, not to the app.
Unlocking requires the owner's PIN, which the agent does not have and should not have.

So the honest statement is: the APK installs, launches, stays resumed and does not crash on
Android 12 / API 31. The rendered dashboard content is **not visually confirmed**. A screenshot
belongs in the next device session, after the owner unlocks the phone.

## 7. Environment defects found

| Finding | Impact | Action |
|---|---|---|
| `~/.gradle/gradle.properties` contains a plaintext GitHub personal access token (`gpr.key=gho_…`) | A token in a dotfile is a token in every backup, screen share and support bundle | Not modified by this pass. Reported to the owner; it should be revoked and replaced with a credential helper or an environment variable. |
| Xcode injects `com.apple.security.get-task-allow` into Release builds signed ad-hoc | A shipped build carrying it is rejected by the notary service (`PERMISSIONS_MATRIX.md` §4) | Fixed: `CODE_SIGN_INJECT_BASE_ENTITLEMENTS: NO` for the Release configuration only. Verified — the Release bundle now carries exactly the three audited entitlements. |
| Gradle 9.9.0 available; wrapper pinned to 9.8.2 | Lint flags the wrapper as outdated | Deliberately pinned. Documented suppression in `android/lint.xml`, scoped to the wrapper file only. |

## 8. USB / debugging state for this session

- USB connected: **yes**
- USB debugging enabled: **yes**
- Wireless debugging enabled: **no**

This was a development session. Nothing in the product requires USB or debugging at runtime, and
no runtime behaviour was tested with USB disconnected in this phase (there is no runtime behaviour
yet).
