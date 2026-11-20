# P17 — macOS Bluetooth Classic HIDP feasibility

**Status:** **Preliminary GO on the OnePlus Nord (Android 12 / API 31) only.** A Mac acting as a Bluetooth Classic HID device publishes an HID SDP record, opens the HID L2CAP control (`0x0011`) and interrupt (`0x0013`) channels to Android, and delivers real arrow key press/release reports that Android's input system routes to a focused app. **No S24 Ultra evidence exists**, and the run used the unmodified upstream app as the test vehicle, not DroidBridge code. This is not final acceptance and not a production-input approval.

**Related:** [P16 BLE HID](P16-ble-hid.md) (NO-GO on Android for BLE), ADR-041, R-35.

## Objective

Determine whether a macOS app can act as a standard Bluetooth **Classic** HID device (`HIDP`) toward Android, and deliver keyboard input to a focused app, using only documented/public platform APIs within the project's constraints.

## Device and environment

| Item | Value |
|---|---|
| Android device | OnePlus Nord AC2001, Android 12 / API 31, build `AC2001_11_F.24` — **preliminary device; not the target acceptance device** |
| Android Bluetooth address | `5C:17:CF:B4:1B:B9` |
| Mac | MacBook Air `MacBookAir10,1`, Apple M1, macOS 27.2.1 build `26B131` |
| Mac Bluetooth address | `60:3E:5F:31:D0:6D` (same address used for LE advertising) |
| Xcode | 27.2 build `27B52` |
| USB connected | **No** (`dumpsys usb` → `connected=false`) |
| USB debugging enabled | **Yes** (`adb_enabled=1`) |
| Wireless debugging enabled | **No** (`adb_wifi_enabled=0`) |
| ADB transport | Wi-Fi `192.168.31.64:5555` |
| Bluetooth | On |

This run therefore does **not** satisfy final acceptance, which requires USB disconnected *and* both debugging modes disabled.

## Test vehicle and licensing

The run used the **unmodified upstream** `jqssun/darwin-bt-remote` app (commit `e64cae983b8816e7e2f067e79025ffc49faf7e2d`, AGPL-3.0-only), built locally, in its `classic` transport mode. It is an AGPL research artifact used only as a feasibility probe; **no upstream source may be adopted into DroidBridge**. DroidBridge's own independent P17 implementation remains outstanding.

## Permissions and API surface used

- macOS: public `IOBluetooth` APIs only — `IOBluetoothSDPServiceRecord.publishedServiceRecord(with:)`, `IOBluetoothDevice.openConnection()`, `openL2CAPChannelSync(_:withPSM:delegate:)` for PSM `0x0011` and `0x0013`, and channel writes of `0xA1`-prefixed input reports.
- No Accessibility permission was granted or needed. The app displays an Accessibility alert for its unrelated *Direct Input* feature; it was dismissed with **Not now** and that path was never enabled.
- Android: no permission was added to the receiver. It continues to observe ordinary `onKeyDown`/`onKeyUp` arrow events.

**Research correction:** earlier project notes described the upstream Classic backend as containing "prohibited private-API behavior". Source review shows the KVC helper `HIDClassicDevice._privateBool` is declared and **never called** — dead code. The Classic HIDP path itself uses only public API. This removes the previously assumed private-API blocker for P17.

## Procedure

1. Phone paired to the Mac at OS level and listed in the app's device picker.
2. App switched to **Classic** transport; HID SDP record published.
3. App connected to the phone (Mac-initiated ACL). Phone screen awake, P16 receiver foregrounded.
4. Arrow keys pressed in the app's ArrowPad; results read from the receiver's own on-screen counters.
5. ADB was used only for setup (launching the receiver, collapsing the notification shade) and read-only sanitized diagnostics (`dumpsys`, and a narrowly scoped UI dump limited to the receiver's fixed counters). **No ADB command generated any functional input.**

## Results

**SDP and L2CAP (Mac log, 2026-11-20):**

```
17:58:37.651  SDP service published; handle=1330188580
18:00:05.634  opening ACL to 5c-17-cf-b4-1b-b9
18:00:07.636  control L2CAP open        (PSM 0x0011)
18:00:07.787  interrupt L2CAP open      (PSM 0x0013)
```

**Android HID host binding:**

```
Profile: HidHostService
  mInputDevices:
    60:3E:5F:31:D0:6D : 2
ConnectionState: STATE_CONNECTED
```

**Android input devices registered from the Mac:**

```
10: Jayanth's MacBook Air Mouse
11: Jayanth's MacBook Air Keyboard
12: Jayanth's MacBook Air Consumer Control
13: Jayanth's MacBook Air System Control
```

**Key delivery to the focused receiver** (machine-captured counter transition):

```
BASELINE 18:05:50 :: UP: 0  DOWN: 0  LEFT: 0  RIGHT: 0
CHANGED  18:05:56 :: UP: 2  DOWN: 2  LEFT: 1  RIGHT: 0
```

Later reading of the receiver screen:

```
UP: 8    DOWN: 3
LEFT: 3    RIGHT: 1
Events: UP down / UP up / UP down / UP up / UP down / UP up
        DOWN down / DOWN up / LEFT down / LEFT up / RIGHT down
```

Press **and** release pairs are present, so reports are well-formed and no key is stuck. Key events reached the app through Android's normal input pipeline — not through ADB.

## Independent DroidBridge implementation (built; delivery not yet demonstrated)

`macos/Experiments/P17ClassicHIDP/` — target `P17ClassicHIDP`, bundle ID `com.droidbridge.experiment.p17classichidp`. Independently authored from the HID Profile transaction set and Apple's public `IOBluetooth` surface; **no third-party implementation source is included**, and the earlier AGPL-derived comparison artifact was deleted before this work was committed.

Implemented: HID SDP record (service class `0x1124`, L2CAP control `0x0011` and interrupt `0x0013` protocol lists, `HIDDescriptorList` with the report descriptor, boot-device and normally-connectable flags); outbound ACL plus both L2CAP channels; HIDP transactions (`GET_REPORT`, `SET_REPORT`, `GET_PROTOCOL`, `SET_PROTOCOL`, `GET_IDLE`, `SET_IDLE`, `HID_CONTROL`, handshakes); `0xA1`-prefixed input reports with report ID in Report Protocol and the boot layout in Boot Protocol; temporary local Class of Device `0x2540`; and `disableAutomaticTermination`/`disableSuddenTermination` to prevent the silent disconnect observed with the upstream app.

Observed so far (OnePlus dozing, screen off):

```
SDP published (handle 1330188580)
class of device -> 0x2540
opening ACL to 5c-17-cf-b4-1b-b9
L2CAP control channel (0x0011) failed (0xE00002BC)
```

So the record publishes and the ACL opens, but the control channel is refused while the handset is asleep, and `HidHostService` keeps the device at state `0`. **Key delivery from this app is not demonstrated.** Build and strict `codesign --verify --deep --strict` pass. Manual UI is the default; a `--harness-publish` / `--harness-connect <addr>` / `--harness-send <ARROW>` launch mode exists for automated runs.

## Known host-side fragility

macOS **automatic termination** killed the Mac app 16 seconds after the channels opened in one attempt (`18:00:24 App termination approved`), which silently dropped the HID connection. The app must stay running and visible during use. An independent implementation must prevent this (e.g. disable sudden/automatic termination) and surface disconnect/reconnect state.

## Limitations — what this does not prove

- **No S24 Ultra evidence.** The target device is untested; OnePlus Android 12 behaviour is preliminary.
- **Not DroidBridge code.** Feasibility was shown with the upstream app; the project's own implementation does not exist yet.
- Keyboard arrows only. Pointer, consumer/system controls, text entry, drag, scroll, gestures and arbitrary touch-equivalent control are unproven.
- Android 17 / One UI 9 masks password characters from physical keyboards (R-19), so HID typing is not a password-entry path.
- No claim about secure/`FLAG_SECURE` apps, banking apps, or protected surfaces.
- Debugging was enabled during the run; final acceptance requires it disabled, and disconnect/reconnect, relaunch, Bluetooth-toggle and bond-recovery cycles were not measured.
- The AGPL test vehicle cannot be shipped.

## Conclusion

**CONDITIONAL GO** for the Classic HIDP route on Android, based on preliminary single-device evidence. **BLE HOGP from this Mac to Android is NO-GO** (see [P16](P16-ble-hid.md) and ADR-041). Next step is an independent DroidBridge P17 POC using public `IOBluetooth` APIs, reproduced on the **S24 Ultra**, with reconnect/relaunch coverage and a USB-disconnected, debugging-off acceptance pass.
