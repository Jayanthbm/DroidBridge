# P17 evidence — independent Classic HIDP app delivers input to OnePlus Nord

Raw, machine-captured evidence for the run recorded in [P17](../../P17-classic-hidp.md).
No screenshots were taken: ADB was used only to launch the receiver and read its fixed
on-screen counters, and no ADB command generated functional input.

## Session

| Item | Value |
|---|---|
| Date/time | 2026-11-20, 19:54–19:58 IST |
| Mac | MacBook Air `MacBookAir10,1`, Apple M1, macOS 27.2.1 build `26B131` |
| Mac Bluetooth address | `60:3E:5F:31:D0:6D` |
| App under test | `P17 Classic HIDP Test.app` (`com.droidbridge.experiment.p17classichidp`), Debug, signed `Apple Development: jayanthbharadwajm@gmail.com (25D4Z5M9X5)` |
| Android device | OnePlus Nord `AC2001`, Android 12 / API 31, build `AC2001_11_F.24` |
| Android Bluetooth address | `5C:17:CF:B4:1B:B9` |
| Bond | `60:3E:5F:31:D0:6D [BR/EDR] Jayanth's MacBook Air` |
| USB connected | **No** (`dumpsys usb` → `connected=false`, `host_connected=false`) |
| USB debugging enabled | **Yes** (`adb_enabled=1`) |
| Wireless debugging enabled | **No** (`adb_wifi_enabled=0`) |
| ADB transport | Wi-Fi `192.168.31.64:5555` |
| Receiver | `com.droidbridge.experiment.bluetoothhid/.HidReceiverActivity`, foregrounded |

## Run 1 — single `UP`

Mac unified log (`subsystem com.droidbridge.experiment.p17classichidp`):

```
19:54:51.413  SDP published (handle 1330188580)
19:54:51.414  class of device -> 0x2540
19:54:51.414  opening ACL to 5c-17-cf-b4-1b-b9
19:54:52.922  control channel open          (PSM 0x0011)
19:54:53.123  interrupt channel open        (PSM 0x0013)
19:54:59.329  harness sent UP
```

Receiver counters, read from the app's own UI:

```
BASELINE  (before run)  UP: 0    DOWN: 0 / LEFT: 0  RIGHT: 0
AFTER     (19:55)       UP: 1    DOWN: 0 / LEFT: 0  RIGHT: 0
```

## Run 2 — all four directions over one HID session

```
19:57:35.977  class of device -> 0x2540
19:57:35.977  opening ACL to 5c-17-cf-b4-1b-b9
19:57:38.216  control channel open
19:57:38.317  interrupt channel open
19:57:44.578  harness sent UP
19:57:45.520  harness sent DOWN
19:57:46.471  harness sent LEFT
19:57:47.420  harness sent RIGHT
```

Receiver counters after run 2:

```
UP: 2    DOWN: 0 -> 1
LEFT: 1  RIGHT: 1
```

Android HID host state:

```
60:3E:5F:31:D0:6D [BR/EDR] Jayanth's MacBook Air
11-20 19:57:38 - 45256ms : 60:3E:5F:31:D0:6D (8)
ConnectionState: STATE_CONNECTED
```

Each direction was sent once and each counter advanced by exactly one, so the reports were
well formed and none was duplicated or dropped.

## Failure modes observed while reaching this result

1. **Bluetooth privacy consent (macOS 26).** Until `kTCCServiceBluetoothAlways` was granted,
   every IOBluetooth call blocked and the app reported `L2CAP control channel (0x0011) failed
   (0xE00002BC)` or `openConnection failed (0xE00002D6)`. Neither error was a protocol fault.
   `tccd` logged `AUTHREQ_PROMPTING … service=kTCCServiceBluetoothAlways` for this bundle ID.
2. **Main-thread deadlock.** `IOBluetoothDevice.pairedDevices()` on the main thread waits on a
   semaphore signalled from the main thread, so any first IOBluetooth call made there hangs the
   app before its window appears (no log output at all).
3. **Programmatic pairing loses the single pairing-agent slot.** `bluetoothd` logged our session
   registering with `programmatic pairing disabled`, then `programmatic pairing enabled` →
   `Classic pairing agent already exist` while CleanMyMac4's
   `bluetooth-device-fetch-xpcservice` re-registered a Classic agent roughly once per second.
   The numeric-comparison value arrived and was accepted by the user, but the reply was never
   delivered: the bond failed with `0x0000001F` after the peer's SSP timeout, and the Mac side
   additionally saw `BT_ERROR_AUTHENTICATION_FAILURE`. The successful bond in this run was
   **phone-initiated** (`btif_dm_create_bond`), which bypasses that slot.
4. **One HID session per connection.** After the Mac process exits, Android keeps the previous
   HID link until its own timeout, so an immediate reconnect is refused with `0xE00002BC`.
   The working run therefore sends every report over a single control/interrupt pair.
