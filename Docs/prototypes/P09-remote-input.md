# P09 — Remote input feasibility (NO-GO confirmation)

**Status: completed — full remote input is NO-GO.** The S24 Ultra and public Android/macOS APIs
confirm the earlier research: no compliant path exists for arbitrary touch/key injection under the
project's constraints. Mirroring ships view-only.

| Field | Value |
|---|---|
| Date | 2026-11-10 |
| Device | Samsung Galaxy S24 Ultra, SM-S928B, Android 17 / API 37, One UI 9.0 |
| Build | `CP2A.260605.016.S928BXXS6EZJ1`, `ro.build.type=user` |
| Root | no (`adb shell id` is uid 2000 shell; no `su`) |
| Accessibility | disabled (`enabled_accessibility_services` empty, `accessibility_enabled=0`) |
| Test app | `com.droidbridge.experiment.session` (requests only INTERNET + ACCESS_LOCAL_NETWORK) |
| Mac | MacBookAir10,1, macOS 27.0.1, Xcode 27.2 |

## What a normal app can and cannot do

| Mechanism | Result on this S24 | Why |
|---|---|---|
| **Accessibility Service** | **Prohibited by project / not used** | AGENTS.md and the product constraints forbid it. Accessibility is also disabled on the device. |
| `InputManager.injectInputEvent` | **Not available** | Requires the signature-level `android.permission.INJECT_EVENTS`; the app does not hold or request it, and a normal app cannot obtain it. |
| `UiAutomation` | **Not available** | Shell/privileged instrumentation only. |
| Knox `RemoteInjection` | **Not available** | Signature permission plus managed-device/MDM authorisation (ADR-017); the SDK permission is not held by the app. |
| Android as Bluetooth HID peripheral | **Wrong direction** | `BluetoothHidDevice` makes Android the peripheral for a host (e.g. a phone as a keyboard for a PC), not a host that accepts a Mac's input. |
| Mac as Bluetooth HID peripheral | **Unsupported** | CoreBluetooth refuses to publish the HID-over-GATT service; classic HID transport belongs to `bluetoothd` (R-12/ADR-016). |
| MediaProjection | **View-only** | Captures the screen; it grants no input injection. |
| ADB / wireless debugging | **Development only** | Explicitly prohibited as a runtime dependency. |
| Root | **Not available / prohibited** | `ro.build.type=user`; no `su`; root is forbidden. |

The S24 session app's actual requested permissions are only:

```
android.permission.ACCESS_LOCAL_NETWORK
android.permission.INTERNET
com.droidbridge.experiment.session.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
```

No `INJECT_EVENTS`, no Accessibility binding, no Knox remote-control permission.

## Exact decision

- **Arbitrary touch/key injection from the Mac into other Android apps: NO-GO.**
- Mirroring is **view-only** (ADR-015).
- The only supported input interaction is the user touching the Android device itself.
- Typing into a focused field on the phone is not remote injection and does not set the Android
  clipboard; no feature may claim otherwise.
- The Mac cannot act as a Bluetooth HID peripheral, so there is no fallback path there either.

## Acceptance

| Criterion | Result |
|---|---|
| Exact supported inputs and refusals recorded | **Pass** — table above |
| Full remote control marked NO-GO if no compliant path | **Pass** — NO-GO |
| Report states focused-field typing is not arbitrary injection and does not set the clipboard | **Pass** — stated above |

P09 is completed as a **NO-GO decision**, not as a feature. Phase 5 still needs the owner to accept
the view-only product direction explicitly; the technical answer is not in doubt.

## Reproducing the checks

```bash
adb shell id
adb shell getprop ro.build.type
adb shell settings get secure enabled_accessibility_services
adb shell settings get secure accessibility_enabled
adb shell pm list permissions | grep -i INJECT
adb shell dumpsys package com.droidbridge.experiment.session | grep -A3 "requested permissions"
```

No source changes are associated with this prototype.
