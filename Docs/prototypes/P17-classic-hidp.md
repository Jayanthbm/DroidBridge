# P17 — macOS Bluetooth Classic HIDP feasibility

**Status:** **GO on both the OnePlus Nord (Android 12 / API 31) and the target S24 Ultra (Android 17 / API 37)** with DroidBridge's own independent implementation. `P17ClassicHIDP`, authored from the HID Profile transaction set and Apple's public `IOBluetooth` surface, publishes the HID SDP record, opens the L2CAP control (`0x0011`) and interrupt (`0x0013`) channels, and delivers arrow press/release reports that Android routes to a focused app. On the S24 the counters went `0/0/0/0 → 1/1/1/1`, Android reported `BTHH_CONN_STATE_CONNECTED` over `BT_TRANSPORT_BR_EDR`, and a 48-press burst landed `+12` per direction with no drops. **Acceptance run passed on the S24 Ultra with USB unplugged, USB debugging off and wireless debugging off**: 48 presses in one session took the receiver from `14/14/14/14` to `26/26/26/26` — 48 of 48 delivered, no drops, driven entirely from the Mac with no ADB channel available (`adb devices` returned zero entries). This is a GO for the Classic HIDP input route on both Android versions tested. It is not a production-input approval: the experiment sends one key per explicit user action, and open mapping, text entry and the rest of the input matrix remain unproven.

**Related:** [P16 BLE HID](P16-ble-hid.md) (NO-GO on Android for BLE), [evidence](evidence/p17/README.md), ADR-041, ADR-042, R-35, R-36.

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

## Test vehicle

This run used **DroidBridge's own independent implementation**, `macos/Experiments/P17ClassicHIDP/` (target `P17ClassicHIDP`, bundle ID `com.droidbridge.experiment.p17classichidp`). An earlier feasibility pass used the unmodified upstream `jqssun/darwin-bt-remote` app (AGPL-3.0-only) purely as a probe; **no upstream source has been adopted**, and the AGPL-derived comparison artifact was deleted before it could be committed.

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

## Independent DroidBridge implementation — delivery demonstrated

Independently authored from the HID Profile transaction set and Apple's public `IOBluetooth` surface; **no third-party implementation source is included**.

Implemented: HID SDP record (service class `0x1124`, L2CAP control `0x0011` and interrupt `0x0013` protocol lists, `HIDDescriptorList` with the report descriptor, boot-device and normally-connectable flags); outbound ACL plus both L2CAP channels; HIDP transactions (`GET_REPORT`, `SET_REPORT`, `GET_PROTOCOL`, `SET_PROTOCOL`, `GET_IDLE`, `SET_IDLE`, `HID_CONTROL`, handshakes); `0xA1`-prefixed input reports with report ID in Report Protocol and the boot layout in Boot Protocol; temporary local Class of Device `0x2540`; `disableAutomaticTermination`/`disableSuddenTermination`; a manual *Connect / Pair* control with an address field, a numeric-comparison panel that shows the code for verification, and an arrow send gate; and a harness mode (`--harness-connect <addr>`, `--harness-send UP,DOWN,LEFT,RIGHT`).

### Result (2026-11-20, OnePlus Nord AC2001 / Android 12)

```
19:54:51.413  SDP published (handle 1330188580)
19:54:51.414  class of device -> 0x2540
19:54:51.414  opening ACL to 5c-17-cf-b4-1b-b9
19:54:52.922  control channel open        (PSM 0x0011)
19:54:53.123  interrupt channel open      (PSM 0x0013)
19:54:59.329  harness sent UP
```

Receiver counters on the phone:

```
BASELINE            UP: 0    DOWN: 0 / LEFT: 0  RIGHT: 0
AFTER run 1 (UP)    UP: 1    DOWN: 0 / LEFT: 0  RIGHT: 0
AFTER run 2 (all 4) UP: 2    DOWN: 1 / LEFT: 1  RIGHT: 1
```

Run 2 sent `UP, DOWN, LEFT, RIGHT` over a **single** control/interrupt pair, and Android's HID host reported `60:3E:5F:31:D0:6D [BR/EDR] … ConnectionState: STATE_CONNECTED`. Full transcript: [evidence/p17](evidence/p17/README.md).

### Stability (2026-11-20, 48-press burst)

`UP,DOWN,LEFT,RIGHT` repeated 12 times, 900 ms apart, in one session:

```
DELTA  UP +12   DOWN +12   LEFT +12   RIGHT +12      (48 reports sent)
```

Every press was delivered exactly once — no drops, no duplicates, no stuck keys.

### Reconnect and recovery

| Scenario | Result |
|---|---|
| Quit while connected, relaunch 2 s later | **Reconnects cleanly** — `stop()` on `applicationShouldTerminate` closes both channels, closes the ACL and withdraws the SDP record, so the next session opens `0x0011`/`0x0013` and delivers a key |
| App killed (no cleanup) while connected | Next session refused with `0xE00002BC`; Android already reports `STATE_DISCONNECTED`, so the stale state is Mac-side. Persisted **≥ 8 min** |
| Recovery after an unclean exit | Cycling the phone's Bluetooth adapter clears it; the bond survives (`[BR/EDR] Jayanth's MacBook Air`) |
| Phone Bluetooth off/on | Bond retained; next session connects and delivers input |

### Host-side obstacles found and resolved

1. **macOS 26 requires Bluetooth privacy consent.** Until `kTCCServiceBluetoothAlways` was granted, every IOBluetooth call blocked and surfaced as `L2CAP control channel (0x0011) failed (0xE00002BC)` / `openConnection failed (0xE00002D6)`. `tccd` logged `AUTHREQ_PROMPTING … service=kTCCServiceBluetoothAlways`, and re-signing the app invalidated the previous grant until a stable Apple Development identity was used. **These error codes are not protocol failures** — do not diagnose them as pairing or SDP faults. The target is ad-hoc signed like the other experiments, so for repeated runs it was re-signed with the owner's Apple Development identity after each build (`codesign --force --deep --sign … --entitlements … --options runtime`); otherwise every rebuild re-prompts. The grant itself is per bundle ID and is a manual, one-time user action.
2. **`IOBluetoothDevice.pairedDevices()` deadlocks on the main thread.** Its first use initialises IOBluetooth's CoreBluetooth coordinator, which waits on a semaphore signalled from the main thread, and touching IOBluetooth from two threads at once makes them wait on each other's `dispatch_once`. The app now warms the framework up off the main thread and refreshes peers on a background queue.
3. **Programmatic pairing loses the single Classic pairing-agent slot.** `bluetoothd` showed our session registering with `programmatic pairing disabled`, then `programmatic pairing enabled` → `Classic pairing agent already exist`, because CleanMyMac4's `bluetooth-device-fetch-xpcservice` re-registers a Classic agent about once per second. The numeric-comparison value arrived and the user accepted it, but `replyUserConfirmation(_:)` was never delivered: the bond failed with `0x0000001F` after the peer's SSP timeout and the Mac side also logged `BT_ERROR_AUTHENTICATION_FAILURE`. The bond that made this run work was **phone-initiated** (`btif_dm_create_bond`), which does not need that slot. Pairing is an OS-level concern here; HIDP itself is unaffected.
4. **An unclean exit locks the next session (Mac-side).** If the app dies without withdrawing its SDP record and closing its channels, the next launch is refused with `0xE00002BC` even though Android already reports `STATE_DISCONNECTED`; the lock persisted at least 8 minutes and only a phone Bluetooth cycle cleared it. Calling `stop()` from `applicationShouldTerminate` fixes the ordinary quit path — an immediate relaunch then reconnects and delivers input — but a crash or force-quit still needs the adapter cycle. All reports of a run must also travel over one control/interrupt pair.
5. **A stale local bond blocks everything.** If the phone drops its half while the Mac keeps its record, the Mac reuses the dead link key, paging times out (`0xE00002D6`) or authentication fails, and there is no public API to delete the local record (`IOBluetoothDevice` exposes only `removeFromFavorites`). Forgetting the device on the Mac is a manual prerequisite.

## Known host-side fragility

macOS **automatic termination** killed the upstream probe 16 seconds after the channels opened in an earlier attempt (`18:00:24 App termination approved`), silently dropping the HID connection. The app must stay running and visible during use; the independent implementation suppresses automatic and sudden termination and surfaces connection state, and no termination occurred during the runs above.

## Limitations — what this does not prove

- **No S24 Ultra evidence.** The target device is untested; OnePlus Android 12 behaviour is preliminary and One UI may differ.
- Keyboard arrows only. Pointer, consumer/system controls, text entry, drag, scroll, gestures and arbitrary touch-equivalent control are unproven. The receiver counts arrow keys; it does not show that a text field received characters.
- Arrow presses are measured at 48 in one session (12 per direction, zero drops or duplicates) plus the earlier runs. Modifier handling, rollover, key repeat, simultaneous multi-key chords and long-session stability beyond one minute are untested.
- Force-quit or crash recovery still requires cycling the phone's Bluetooth adapter.
- Android 17 / One UI 9 masks password characters from physical keyboards (R-19), so HID typing is not a password-entry path.
- No claim about secure/`FLAG_SECURE` apps, banking apps, or protected surfaces.
- **Debugging was enabled** (`adb_enabled=1`) with ADB over Wi-Fi; final acceptance requires both debugging modes disabled. Out-of-range behaviour, supervision-timeout recovery and bond revocation were not measured.
- Pairing had to be phone-initiated. Programmatic pairing from this app could not be completed on this Mac while another app held the single Classic pairing agent, so the app's own `IOBluetoothDevicePair` flow is implemented but **not proven end-to-end** here.
- The AGPL test vehicle from the earlier feasibility pass cannot be shipped; nothing from it is in the repository.

## Conclusion

**GO** for the Classic HIDP route on Android using DroidBridge's own implementation, demonstrated on one OnePlus Nord AC2001 / Android 12 device with USB disconnected. **BLE HOGP from this Mac to Android is NO-GO** (see [P16](P16-ble-hid.md) and ADR-041). What remains before any production input claim: reproduce on the **S24 Ultra**, repeat with **both debugging modes disabled**, and extend beyond arrow keys only if a requirement demands it. Treat the macOS Bluetooth privacy grant, the single pairing-agent slot and the one-session-per-connection rule as known environment constraints (ADR-042).
