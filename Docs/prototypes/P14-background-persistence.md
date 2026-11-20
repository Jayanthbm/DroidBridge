# P14 — Background session persistence (clipboard while mirroring is off)

**Status:** **In progress — blocked on device access.** Tests 1 and 2 are measured. Test 3 (BOOT_COMPLETED)
is inconclusive because the reboot removed the ADB transport and the handset left the Wi-Fi network, and
the survival matrix has not run yet. **ADR-023 remains Proposed**; nothing here supports or refutes a
session surviving an ordinary night.

**Related:** ADR-023, R-20, R-21.

## Objective

Prove or disprove ADR-023: a `connectedDevice` foreground service keeps a TLS session usable in normal
use, and characterise exactly where it stops working.

## Implementation

`android/experiments/background-session/` — a separate installable app, applicationId
`com.droidbridge.experiment.backgroundsession`, never depended on by `:app`.

It reuses the production session rather than a stand-in: `SecureSession` from `:core:network` (TLS 1.3,
mutual authentication, pinned peer) and `KeystorePinStore` from `:core:security`. If a toy socket had been
used, a pass would say nothing about the real client.

- `SessionService` — foreground service, type `connectedDevice`, started only from the activity or
  `BOOT_COMPLETED`. Sends a real `PING` every 15 s and requires the `PONG` before the next beat, so a
  half-open socket is detected rather than assumed alive. Reconnects with 2/4/8/16/32/60 s backoff; every
  attempt and outcome is logged. No wake lock, no battery-optimisation exemption, no silent retry loop.
- `ServiceNotifications` — persistent `IMPORTANCE_LOW` notification with state, counters and a
  Disconnect action.
- `BootReceiver` — `BOOT_COMPLETED`, only when background sync was previously enabled.
- `MainActivity` — manual Start/Stop plus launch-extra harness (`host`, `port`, `pin`, `autostart`,
  `backgroundProbeMs`) so a run can begin without a human tap and so the background-start test can make
  its attempt from an already-backgrounded process.
- `SessionLog` — in-memory ring buffer mirrored to logcat under `P14Session`, so runs are read by script.

Manifest permission set is exactly the plan's, plus `INTERNET` and `RECEIVE_BOOT_COMPLETED`:
`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `POST_NOTIFICATIONS`,
`CHANGE_NETWORK_STATE` (declared, never requested), `RECEIVE_BOOT_COMPLETED`, `INTERNET`.
Deliberately absent: any Bluetooth permission, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `WAKE_LOCK`.

## Environment

| Item | Value |
|---|---|
| Android device | OnePlus Nord AC2001, Android 12 / API 31, build `AC2001_11_F.24` — **not the target device** |
| Mac | MacBook Air `MacBookAir10,1`, Apple M1, macOS 27.2.1 build `26B131` |
| Mac listener | `DroidBridge --listen --expect-pin <android-pin> --seconds 32400` (P03 harness, TLS 1.3, mutual pinning) |
| Peer pins | Mac `2178541711aede…` / Android `ad84d9f0d5ef…`; both sides verify, no first-pairing prompt needed |
| USB connected | No |
| USB debugging enabled | Yes (`adb_enabled=1`) |
| Wireless debugging enabled | No (`adb_wifi_enabled=0`); ADB was over Wi-Fi `192.168.31.64:5555` |

Session established end-to-end before the tests: Android `state=connected`, `HEARTBEAT n=1 / PONG n=1`;
Mac `AUTHENTICATED (stored pin matched)`, `FRAME type=ping`, `SENT pong`. Cadence measured 15 s
(`HEARTBEAT n=1` at :27, `n=2` at :42).

## Test results

| # | Test | Result |
|---|---|---|
| 1 | Start from a visible activity | **PASS.** `startForegroundService accepted (origin=activity-autostart)` → `startForeground succeeded for connectedDevice`. No runtime permission dialog: `POST_NOTIFICATIONS not required on sdk=31`, and `CHANGE_NETWORK_STATE` is install-time. |
| 2 | Start from a background context | **Measured twice, and the timing matters.** 6 s after backgrounding: `background start ACCEPTED (no exception)` — inside Android's post-visibility grace window. 30 s after backgrounding: `android.app.ForegroundServiceStartNotAllowedException: startForegroundService() not allowed due to mAllowStartForeground false: service com.droidbridge.experiment.backgroundsession/.SessionService`. A 120 s probe produced no attempt at all (process was still alive but the scheduled start never fired). |
| 3 | Start from `BOOT_COMPLETED` | **Inconclusive — needs a retest.** The receiver is `exported="false"`, so `adb shell am broadcast` cannot deliver it (the shell attempt failed with a binder `SecurityException`); exporting it for a dev shortcut was rejected as a real risk surface. A real reboot was performed instead, but `persist.adb.tcp.port=5555` did **not** put adbd into TCP mode after boot, the handset left the Wi-Fi network (absent from ARP, no listener on port 5555 across the /24), and the Mac listener recorded no reconnect attempt before its idle timeout. |
| 4 | Survival matrix | **Not run.** Needs the device back on the network. |
| 5 | Doze forcing | **Not run.** |
| 6 | Notification behaviour | **Not run.** |
| 7 | Battery drain | **Not run.** |

A defect found and fixed during bring-up: a start request with a new host/port while a session was
running was ignored (`already running; new target …`), which silently kept a stale target for the whole
run. The service now restarts its worker when the target changes.

## Blocked on

The handset is off Wi-Fi after the reboot, so ADB, logcat and the counter reads are all unavailable, and
the Mac sees no reconnect. Bring the phone back with USB attached (then `adb tcpip 5555` can be re-armed)
or with Wireless debugging enabled, and tests 3–7 can proceed.

## Not yet claimed

Nothing about Doze, overnight survival, Wi-Fi toggling, Mac sleep/wake, notification dismissal, or battery
drain. Until the matrix runs, the honest statement is only this: a `connectedDevice` foreground service
can be started from a visible activity without a runtime prompt, and a background start is rejected once
the post-visibility grace window has passed.
