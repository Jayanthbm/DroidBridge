# P14 — Background session persistence (clipboard while mirroring is off)

**Status:** **In progress, with a negative result for the backgrounded case on the OnePlus.** Tests 1, 2, 3 and the Wi-Fi-toggle case are measured. Backgrounding the app **freezes the process and stops the session even though it holds a valid `connectedDevice` foreground service** — measured directly from the cgroup and from the OOM-adj state. The Doze, overnight, notification and battery rows are therefore not reachable on this device and remain unmeasured. **ADR-023 is challenged: on this handset the degradation path (foreground-only sync) applies unless the user exempts the app from battery optimisation, which we refuse to require.** The S24 Ultra — the actual target device — has not been run, and One UI may behave differently.

**Related:** ADR-023, ADR-043, R-20, R-21, R-37.

## Objective

Prove or disprove ADR-023: a `connectedDevice` foreground service keeps a TLS session usable in normal use, and characterise exactly where it stops working.

## Implementation

`android/experiments/background-session/` — a separate installable app, applicationId
`com.droidbridge.experiment.backgroundsession`, never depended on by `:app`.

It reuses the production session rather than a stand-in: `SecureSession` from `:core:network` (TLS 1.3,
mutual authentication, pinned peer) and `KeystorePinStore` from `:core:security`. A pass therefore says
something about the real client, not about a toy socket.

- `SessionService` — foreground service, type `connectedDevice`, started only from the activity or
  `BOOT_COMPLETED`. Sends a real `PING` every 15 s and requires the `PONG` before the next beat, so a
  half-open socket is detected rather than assumed alive. Reconnects with 2/4/8/16/32/60 s backoff;
  every attempt and outcome is logged. A main-thread supervisor refreshes the notification every 5 s and
  restarts the worker if no heartbeat has been sent for 50 s. No wake lock, no battery-optimisation
  exemption, no silent retry loop.
- `ServiceNotifications` — persistent `IMPORTANCE_LOW` notification showing state, counters **and the age
  of the last heartbeat**, so a stale session is never presented as reachable.
- `BootReceiver` — `BOOT_COMPLETED`, only when background sync was previously enabled.
- `MainActivity` — manual Start/Stop plus launch-extra harness (`host`, `port`, `pin`, `autostart`,
  `backgroundProbeMs`).
- `SessionLog` — in-memory ring buffer mirrored to logcat under `P14Session`.

Manifest permission set: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `POST_NOTIFICATIONS`,
`CHANGE_NETWORK_STATE` (declared, never requested), `RECEIVE_BOOT_COMPLETED`, `INTERNET`. Deliberately
absent: any Bluetooth permission, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `WAKE_LOCK`.

## Environment

| Item | Value |
|---|---|
| Android device | OnePlus Nord AC2001, Android 12 / API 31, build `AC2001_11_F.24` — **not the target device** |
| Mac | MacBook Air `MacBookAir10,1`, Apple M1, macOS 27.2.1 build `26B131` |
| Mac listener | `DroidBridge --listen --expect-pin <android-pin> --seconds 32400` (P03 harness, TLS 1.3, mutual pinning) |
| Peer pins | Mac `2178541711aede…` / Android `ad84d9f0d5ef…`; both verified, no first-pairing prompt |
| USB connected | Yes during the reported rows (`adb tcpip 5555` also armed) |
| USB debugging enabled | Yes (`adb_enabled=1`) |
| Wireless debugging enabled | No (`adb_wifi_enabled=0`) |

Baseline session: `state=connected`, `HEARTBEAT n=1 / PONG n=1` at 21:44:48, steady 15 s cadence.

## Test results

| # | Test | Result |
|---|---|---|
| 1 | Start from a visible activity | **PASS.** `startForegroundService accepted (origin=activity-autostart)` → `startForeground succeeded for connectedDevice`. No runtime dialog: `POST_NOTIFICATIONS not required on sdk=31`; `CHANGE_NETWORK_STATE` is install-time. |
| 2 | Start from a background context | **Rejected, with a timing nuance.** 6 s after backgrounding: accepted (inside the post-visibility grace window, `mAllowStartForeground` still true). 30 s after: `android.app.ForegroundServiceStartNotAllowedException: startForegroundService() not allowed due to mAllowStartForeground false: service …/.SessionService`. A 120 s probe produced no attempt at all. |
| 3 | Start from `BOOT_COMPLETED` | **PASS.** After a real reboot: `BOOT_COMPLETED received; background sync enabled=true` → `startForegroundService accepted` → `startForeground succeeded for connectedDevice` → `state=connected` → `HEARTBEAT n=1 / PONG n=1`, all within 500 ms of the broadcast. `connectedDevice` is not blocked from boot, as R-20 predicted. |
| 4a | App backgrounded, screen on | **FAIL — process frozen.** Heartbeats ran to n=8 and stopped when the app was backgrounded; the process stayed alive with `isForeground=true`, OOM adj `fg +50 … state: cur=FGS set=FGS`, and `/proc/<pid>/cgroup` → `6:freezer:/frozen`. Bringing the activity forward moved it to `freezer:/thaw` and heartbeats resumed (n=10, n=11); backgrounding again returned it to `/frozen` and beats stopped at n=13. |
| 4b | Wi-Fi off/on, app in foreground | **PASS.** `FAIL SessionException: TCP connect … ConnectException` → `RECONNECT n=6` → `state=connected` 17 s after Wi-Fi returned → heartbeats resumed through n=21. |
| 4c–4e | Screen off / 60 min Doze / overnight | **Not run.** The app is already frozen within ~40 s of backgrounding on this device, so these rows cannot distinguish anything here. |
| 5 | Doze forcing | **Not run.** Superseded by the freeze result above on this device. |
| 6 | Notification behaviour | **Not run** (partially exercised: the notification was present and foreground the whole time; dismissal and permission-denied behaviour untested). |
| 7 | Battery drain | **Not run.** |

### Why the freeze result is an OEM behaviour, not a manifest mistake

`dumpsys activity oom` reports the process as `fg +50 F/S/FGS` with `state: cur=FGS set=FGS`, i.e. the
platform recognises a valid foreground service with the `connectedDevice` type, and `startForeground`
raised no exception. The process was nonetheless placed in the `/frozen` cgroup as soon as the app left
the foreground. On AOSP a started foreground service keeps the process out of the cached freezer, so this
is device-specific background management (OxygenOS), not a permission or type error in the experiment.

The consequence for the product is the same either way: with the constraints we hold (no
`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, no wake lock), background clipboard sync **does not survive
backgrounding on this handset**. Per ADR-023 that triggers the documented degradation path — background
sync is foreground-only on devices that behave this way — unless S24 Ultra measurements show otherwise.

## Defects found and fixed during bring-up

1. A start request carrying a new host/port was ignored while a session was running, so a long run kept
   the stale target silently. The service now restarts its worker when the target changes.
2. The notification was refreshed from inside the synchronised log write, which deadlocked the worker
   against the main thread: the session froze after a few beats with no error and the service still
   advertised "connected". Notification updates now happen on a main-thread supervisor, the notification
   shows the heartbeat age, and the watchdog restarts a worker that has not beaten for 50 s.

## Not yet claimed

Nothing about Doze, overnight survival, notification dismissal, or battery drain. On this device the
backgrounded case is a measured **no**; whether a valid `connectedDevice` FGS is frozen on stock Android
or on One UI is untested, and that is the row that decides ADR-023.
