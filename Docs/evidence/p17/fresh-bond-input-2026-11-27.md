# P17 fresh-bond input measured — text entry works, arrow counter is the wrong instrument (2026-11-27)

Device: Samsung SM-S928B (S24 Ultra), Android 17, serial `RZCY81TKBSV`.
Mac: `MacBookAir10,1`, macOS 27.2.1, `com.droidbridge.app` (Debug, rebuilt with protocol/report logging).
Bond: fresh Classic bond created by hand on 2026-11-27 (`60:3E:5F:31:D0:6D` ↔ `c0-d5-e2-f6-d7-c2`).
Run conditions: USB connected, `adb_enabled=1`, `adb_wifi=0`.

## Instrumentation added for this run

Three log lines only, no behaviour change (`ClassicHIDPeripheral`):

- `channels ready protocol=<…> (default, host has not set it yet)` when both L2CAP channels open.
- `host msg opcode=… parameter=… bytes=…` for every control-channel message the phone sends.
- `report written id=… protocol=… bytes=… prefix=… status=… total=…` for every input report.

## What the log says

```
09:52:12.163 control channel open
09:52:12.264 interrupt channel open
09:52:12.264 channels ready protocol=report (default, host has not set it yet)
09:52:18.484 report written id=2 protocol=report bytes=10 prefix=0xa1 status=0 total=1
09:52:18.484 report written id=2 protocol=report bytes=10 prefix=0xa1 status=0 total=2
09:52:18.484 harness sent UP
```

- The phone never sent a control-channel message at all — no `host msg` line, so no `SET_PROTOCOL`, no
  handshake, no `GET_REPORT`. The peripheral therefore keeps the HID default, **Report** protocol.
- Each keyboard report is `0xA1`, report ID `2`, 8 payload bytes = 10 bytes, and the stack accepts it
  (`status=0`). That is the report format the published descriptor declares.

## The key does arrive on the phone

`getevent -l` on the phone during a send:

```
/dev/input/event12: EV_KEY       KEY_UP               DOWN
/dev/input/event12: EV_KEY       KEY_UP               UP
```

and `dumpsys input` shows the device the bond creates:

```
23: Jayanth’s MacBook Air
  Classes: KEYBOARD | ALPHAKEY | EXTERNAL
  Location: c0:d5:e2:f6:d7:c2
  UniqueId: 60:3e:5f:31:d0:6d
```

Quitting the app removes that device and drops `HidHostService` `HID connection state` from `2` to `0`,
which proves the device belongs to this session.

## Why the receiver's arrow counters stayed at zero

The receiver's counters read `UP: 0 DOWN: 0 / LEFT: 0 RIGHT: 0` even though the kernel saw the key,
because the receiver now has a **focused `EditText`** (added for the P17 text-entry row) and the text
editor consumes arrow keys for cursor movement before the activity's `onKeyDown` sees them. The counter
is no longer a valid instrument for arrows while that field holds focus.

## Text entry over the same fresh bond — 14/14

```
09:55:39–09:55:40  report written id=2 protocol=report bytes=10 prefix=0xa1 status=0 total=22…28
09:55:40.264       typed 14 characters
```

The phone's field then contained exactly:

```
text="DroidBridge123"
```

with the harness run as `--p17-hid --harness-publish --harness-connect c0-d5-e2-f6-d7-c2
--harness-type "DroidBridge123"`, paced at 120 ms/character.

## Reading

- **Keyboard input works over the fresh bond.** Reports are well-formed, accepted, and reach the kernel
  input device as `KEY_UP` press/release; US-ASCII text entry delivers 14/14 into the focused field.
- The earlier "sent key never reaches the receiver" observation was a measurement artefact: the arrow
  counter is masked by the focused `EditText`, not by failed HID delivery.
- Not measured here: a key through the app's own arrow pad (needs the receiver confirmation toggle in
  the UI), and any pointer/gesture behaviour.
