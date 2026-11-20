# P09 — Initial remote input feasibility checks (historical, scoped NO-GO)

**Status: completed for the mechanisms tested; not a final verdict on Mac Bluetooth HID.** On
2026-11-10 the S24 Ultra and public Android/macOS APIs confirmed no generic touch/key injection
path through the specific mechanisms tested. Later source review (R-32 / ADR-036, 2026-11-17)
identified distinct BLE HOGP and Bluetooth Classic HIDP Mac-peripheral implementations in
`jqssun/darwin-bt-remote`. P09 did not test that Classic HIDP route. P16/P17 now gate a separate,
non-Accessibility Bluetooth HID feasibility decision. Until target evidence exists, DroidBridge
claims no remote-control support and production mirroring remains view-only.

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
| **Accessibility Service** | **Prohibited by project / not used** | Accessibility was disabled on the device and remains prohibited under ADR-036. |
| `InputManager.injectInputEvent` | **Not available** | Requires the signature-level `android.permission.INJECT_EVENTS`; the app does not hold or request it, and a normal app cannot obtain it. |
| `UiAutomation` | **Not available** | Shell/privileged instrumentation only. |
| Knox `RemoteInjection` | **Not available** | Signature permission plus managed-device/MDM authorisation (ADR-017); the SDK permission is not held by the app. |
| Android as Bluetooth HID peripheral | **Wrong direction** | `BluetoothHidDevice` makes Android the peripheral for a host (e.g. a phone as a keyboard for a PC), not a host that accepts a Mac's input. |
| Mac as Bluetooth HID peripheral | **Not tested in P09** | Later source review found separate BLE HOGP and Classic HIDP implementations; see R-32 and P16/P17. |
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

- **Arbitrary touch/key injection using the mechanisms tested in P09: NO-GO.** The Mac HIDP/Classic HID routes were not tested here.
- Mirroring is **view-only** (ADR-015).
- The only supported input interaction is the user touching the Android device itself.
- Typing into a focused field on the phone is not remote injection and does not set the Android
  clipboard; no feature may claim otherwise.
- This report does not establish whether the Mac can act as a Bluetooth HID peripheral; P16/P17 test those paths.

## Acceptance

| Criterion | Result |
|---|---|
| Exact supported inputs and refusals recorded | **Pass** — table above |
| P09 conclusions limited to the mechanisms actually tested | **Pass** — Mac BLE/Classic HID remains open for P16/P17 |
| Report states focused-field typing is not arbitrary injection and does not set the clipboard | **Pass** — stated above |

P09 is complete only for its original scope; its blanket remote-input wording is superseded by ADR-036. P16/P17 are pending and must provide target-device evidence before any control claim.

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
