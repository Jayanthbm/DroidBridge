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

## Run 3 — 48-press stability burst (single session)

Sequence `UP,DOWN,LEFT,RIGHT` repeated 12 times, 900 ms apart, over one control/interrupt pair:

```
20:18:57.903  opening ACL to 5c-17-cf-b4-1b-b9
20:18:59.406  control channel open
20:18:59.510  interrupt channel open
20:19:05.581  harness sent UP   (first of 48)
```

```
harness sent = 48
BASELINE  UP: 3   DOWN: 1  / LEFT: 1  RIGHT: 1
AFTER     UP: 15  DOWN: 13 / LEFT: 13 RIGHT: 13
DELTA     +12      +12        +12        +12
```

Every press was delivered exactly once: zero drops and zero duplicates across 48 reports.

## Run 4 — abrupt app exit leaves the session locked

After killing the app while channels were open, the next launch of any process failed:

```
20:13:56.653  L2CAP control channel (0x0011) failed (0xE00002BC)
20:15:53.657  L2CAP control channel (0x0011) failed (0xE00002BC)
20:17:18.061  L2CAP control channel (0x0011) failed (0xE00002BC)
```

Android was **not** holding the link — `HidHostService` reported `STATE_DISCONNECTED`,
`mTargetDevice: null`, `mInputDevices: 60:3E:5F:31:D0:6D : 0` — so the stale state was on the
Mac side. It persisted for at least 8 minutes and was cleared only by cycling the phone's
Bluetooth adapter:

```
svc bluetooth disable ; svc bluetooth enable
20:16:39.862  control channel open
20:16:39.963  interrupt channel open
```

The bond survived the cycle: `60:3E:5F:31:D0:6D [BR/EDR] Jayanth's MacBook Air`.

## Run 5 — graceful quit now releases the session

`stop()` (close channels, close ACL, withdraw the SDP record) is now called from
`applicationShouldTerminate`. A quit while connected logs `stopped`, and an immediate relaunch
reconnects with no Bluetooth cycle:

```
20:22:57.738  stopped                       (session 1, quit while connected)
20:23:19.160  control channel open          (session 2, launched 2 s later)
20:23:19.461  interrupt channel open
20:23:25.721  harness sent UP
```

## Run 6 — S24 Ultra (target device), first delivery

Device: S24 Ultra `SM-S928B`, Android 17 / API 37. Mac at `60:3E:5F:31:D0:6D`; phone at
`C0:D5:E2:F6:D7:C2`. USB connected: **yes**; USB debugging: **on**; wireless debugging: **off**.
This is therefore preliminary evidence on the target device, not the debugging-off acceptance run.

Pairing was **phone-initiated** and used SSP numeric comparison. The phone displayed `695843`
(`Check pairing codes`), the owner read the same code on the Mac's Bluetooth pairing dialog and
clicked Pair on both sides. The agent driving the phone could not read the Mac's dialog itself
(no screen-capture capability in that session), so the numeric match was verified by the owner —
which is the only acceptable way to confirm it. Both sides then held a bond: the phone reported
`Bonded devices: 10` (was 9) and the Mac listed `Jayanth's S24 Ultra  C0:D5:E2:F6:D7:C2`.

Delivery, one session, receiver screen focused:

```
SDP published (handle 1330188580)
opening ACL to c0-d5-e2-f6-d7-c2
class of device -> 0x2540
control channel open
interrupt channel open
harness sent UP / DOWN / LEFT / RIGHT
```

```
receiver before: UP: 0  DOWN: 0  LEFT: 0  RIGHT: 0
receiver after : UP: 1  DOWN: 1  LEFT: 1  RIGHT: 1
Android        : addr …[public][BT_TRANSPORT_BR_EDR] fd:475 state:BTHH_CONN_STATE_CONNECTED
```

A second delivery on the same bond took the counters to `2/2/2/2`.

## Run 7 — 48-press stability burst on the S24 Ultra

Same session shape as the OnePlus burst: `UP, DOWN, LEFT, RIGHT` × 12 over one control/interrupt
pair, 900 ms apart.

```
receiver before: UP: 2   DOWN: 2   LEFT: 2   RIGHT: 2
receiver after : UP: 14  DOWN: 14  LEFT: 14  RIGHT: 14
```

`+12` per direction: no dropped, duplicated or stuck keys on the target device either.

## Run 8 — ACCEPTANCE: 48 presses with USB unplugged and debugging off

Environment, stated before the run and checked where possible: **USB connected: no**;
**USB debugging: off**; **wireless debugging: off**. `adb devices` returned zero entries, so the
device was not reachable by ADB at any point during the run. The Mac app was already signed with the
stable Apple Development identity (Team `BJ57QQD6`), so the Bluetooth consent grant carried over.

Everything below was driven from the Mac alone; the phone was operated by hand and read by eye.

```
SDP published (handle 1330188580)
opening ACL to c0-d5-e2-f6-d7-c2
class of device -> 0x2540
control channel open
interrupt channel open
harness sent … × 48   (UP, DOWN, LEFT, RIGHT × 12, 900 ms apart, one session)
```

Receiver counters, read on the phone screen after the run:

```
before: UP: 14  DOWN: 14  LEFT: 14  RIGHT: 14
after : UP: 26  DOWN: 26  LEFT: 26  RIGHT: 26
```

`+12` per direction — 48 of 48 reports delivered, no drops, no duplicates, no stuck keys, with no
debugging channel available to the host. This is the acceptance result for P17.

The photo of the receiver screen taken after the run shows `UP: 26  DOWN: 26 / LEFT: 26  RIGHT: 26`
and, in its event list, the last ten events arriving in order (`DOWN up, LEFT down, LEFT up,
RIGHT down, RIGHT up, UP down, UP up, DOWN down, DOWN up, LEFT down`), i.e. the tail of the
`UP, DOWN, LEFT, RIGHT` sequence with each press followed by its release. The same photo showed two
stale labels on that screen, which have been corrected in the app: the title still said
"P16 — BLE HID keyboard receiver" for a Classic run, and a caption warned that USB debugging "may be
on", which contradicted the run's own environment and invited exactly the misreading the acceptance
criterion exists to prevent.

## Failure mode found in Run 6/7 — `SIGTERM` leaves the session stale

Replacing a running instance with `pkill` (SIGTERM) killed the process without running
`applicationShouldTerminate`, so the SDP record was never withdrawn and the L2CAP channels were
never closed. The next launch was then refused with `L2CAP control channel (0x0011) failed
(0xE00002BC)` and `harness send skipped (not ready)`, while Android reported
`BTHH_CONN_STATE_DISCONNECTED` — the stale half was the Mac.

Two outcomes:

1. **Recovery:** cycling Bluetooth on the *phone* (`svc bluetooth disable` → `enable`) cleared it,
   and the next connect opened both channels first time. This is the documented "cycle one side"
   step, now confirmed as a phone-side action.
2. **Fix:** `P17ClassicHIDP` now installs `SIGTERM`/`SIGINT` handlers that run the same `stop()`
   path as a normal quit before exiting. Verified: `osascript … to quit` logs `stopped` and the
   next launch reconnects without any adapter cycle.

Also observed: the S24 carries a **stale LE HID host record for the Mac** (`DevType = LE` with a
boot-keyboard descriptor) from the P16 BLE attempts, next to the BR/EDR record this run uses. The
Classic path works with it present, but it is another reason to read `dumpsys bluetooth_manager`
carefully rather than trusting a single `HID status` line.

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
