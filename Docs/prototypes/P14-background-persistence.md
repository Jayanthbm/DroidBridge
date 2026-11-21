# P14 — Background session persistence (clipboard while mirroring is off)

**Status:** **ADR-023's premise holds on the target device and fails on the OnePlus.** On the **S24 Ultra (Android 17 / API 37)** the `connectedDevice` foreground service kept the TLS session alive while the app was backgrounded (10 min), with the screen off (`mWakefulness=Dozing`, 5 min), across a Wi-Fi off/on cycle, and across a real reboot (`BOOT_COMPLETED` → reconnected within 450 ms). On the **OnePlus Nord (Android 12)** the identical build was frozen within ~40 s of backgrounding despite a valid foreground service, so background sync there is foreground-only. Deep Doze (unplugged) and overnight rows remain unmeasured because the handset was charging over USB for these runs.

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
| Android device | **S24 Ultra `SM-S928B`, Android 17 / API 37** (target device) and OnePlus Nord AC2001, Android 12 / API 31, build `AC2001_11_F.24` (secondary) |
| Mac | MacBook Air `MacBookAir10,1`, Apple M1, macOS 27.2.1 build `26B131` |
| Mac listener | `DroidBridge --listen --expect-pin <android-pin> --seconds 32400` (P03 harness, TLS 1.3, mutual pinning) |
| Peer pins | Mac `2178541711aede…` / Android `ad84d9f0d5ef…`; both verified, no first-pairing prompt |
| USB connected | Yes during the reported rows (`adb tcpip 5555` also armed) |
| USB debugging enabled | Yes (`adb_enabled=1`) |
| Wireless debugging enabled | No (`adb_wifi_enabled=0`) |

Baseline session: `state=connected`, `HEARTBEAT n=1 / PONG n=1` at 21:44:48, steady 15 s cadence.

## Test results

| # | Test | S24 Ultra (Android 17) | OnePlus Nord (Android 12) |
|---|---|---|---|
| 1 | Start from a visible activity | **PASS** — `startForeground succeeded for connectedDevice`; `POST_NOTIFICATIONS` is a runtime prompt on API 37 and was not required for the service to run | **PASS** — no runtime prompt at all on API 31 |
| 2 | Start from a background context | as measured on the OnePlus (same code path): accepted 6 s after backgrounding inside the post-visibility grace window, rejected at 30 s with `ForegroundServiceStartNotAllowedException: startForegroundService() not allowed due to mAllowStartForeground false` | **REJECTED as expected** at 30 s |
| 3 | Start from `BOOT_COMPLETED` | **PASS** — `BOOT_COMPLETED received` → `startForegroundService accepted` → `startForeground succeeded` → `state=connected` → `HEARTBEAT n=1 / PONG n=1`, all within 450 ms of the broadcast | **PASS** — same sequence, within 500 ms |
| 4a | App backgrounded, screen on, 10 min | **PASS** — `HEARTBEAT n=3 → n=44` over ten minutes, `/proc/<pid>/cgroup` stayed `5:freezer:/` (never frozen) | **FAIL — process frozen.** Beats stopped at n=8 within ~40 s; `isForeground=true`, `dumpsys activity oom` showed `fg +50 F/S/FGS`, `state: cur=FGS`, yet `/proc/<pid>/cgroup` → `6:freezer:/frozen`. Foregrounding → `/thaw`, beats resumed; backgrounding → `/frozen` again |
| 4b | Screen off (charging), 5 min | **PASS** — `mWakefulness=Dozing`, `HEARTBEAT n=6 → n=26`, no stalls | not run (already frozen when backgrounded) |
| 4c | Wi-Fi off/on | **PASS** — connection failures with backoff while `wifi_on=0` and wlan0 had no address, `RECONNECT` attempts, then `state=connected` and beats resumed from n=7 in the **same process** after Wi-Fi returned | **PASS** — `RECONNECT n=6`, reconnected 17 s after Wi-Fi returned |
| 4d | 60 min Doze (unplugged) | **Not run** — the handset was charging over USB; `dumpsys deviceidle force-idle` reported `Unable to go deep idle; stopped at INACTIVE` | **Not run** |
| 4e | Overnight, 8 h | **Not run** | **Not run** |
| 5 | Doze forcing | **Blocked by charging state** (see 4d) | not run |
| 6 | Notification behaviour | **Not run** — `POST_NOTIFICATIONS` was not granted (the prompt cannot be answered without a click) and the service ran regardless, with the notification suppressed | **Not run** |
| 7 | Battery drain | **Not run** | **Not run** |

### Device comparison, same build

The only difference between the two results in 4a is the platform's background management. On the S24 the
process stayed out of the freezer for the whole ten minutes while the app was backgrounded; on the OnePlus
it was frozen within ~40 s even though the platform accepted the foreground service (`state: cur=FGS`).
AOSP keeps a started foreground service uncached, so the OnePlus behaviour is an OEM deviation
(OxygenOS), not a manifest or permission defect — and it is exactly the kind of per-device divergence
P14 exists to find. Product consequence is recorded in ADR-043: background sync is allowed on devices
that behave like the S24, and must degrade to foreground-only where the OEM freezes a valid FGS, without
asking the user for a battery-optimisation exemption.

### API 37 requires `ACCESS_LOCAL_NETWORK`

The first S24 runs failed every connection with `TCP connect to 192.168.31.109:59571 failed:
SocketTimeoutException`, which looks exactly like a dead listener. The listener was fine: `nc` reached it
from both the Mac and the phone's shell. Android 17 gates local-network access behind
`ACCESS_LOCAL_NETWORK`; once the experiment declared it and it was granted, the session connected
immediately. Android 12 does not enforce this, which is why the OnePlus never needed it. **Any API 37
build that talks to a LAN address needs this permission, and its absence presents as a timeout.**

## 2026-11-21 — autonomous re-run on the S24 Ultra (rows A–F green)

Run unattended with USB connected, USB debugging on and wireless debugging off. The app was driven by
`am start` extras (permitted: launching test apps) rather than by injected touch, and every value below
comes from the app's own log or from `dumpsys`.

| Row | Measurement |
|---|---|
| A — start from a visible activity | `startForeground succeeded for connectedDevice`, no runtime prompt |
| B — backgrounded 5 min | heartbeats `2 → 22` (15 s cadence), `/proc/<pid>/cgroup` = `5:freezer:/`, 0 stalls |
| C — screen off 5 min | `mWakefulness=Dozing`, heartbeats `22 → 42`, still `5:freezer:/` |
| D — Wi-Fi off/on | `RECONNECT n=2,3` then `state=connected`; 0 false stalls (the tuned watchdog held) |
| E — reboot | `BOOT_COMPLETED received; background sync enabled=true` → `startForegroundService accepted` → `startForeground succeeded` → `state=connected` → `HEARTBEAT n=1 / PONG n=1`, 0.3 s after the broadcast |
| F — notification | title `DroidBridge background session`, text `connected · 52 heartbeats · 52 pongs · 3 reconnects · last heartbeat 1s ago`, importance 2, `ONGOING_EVENT|NO_CLEAR` |

Two operational notes that cost time and are now documented:

1. **The app requires the peer pin up front** (`start refused: host, port and pin are all required`). A
   fresh install therefore cannot pair, because nobody can confirm the numeric-comparison code in an
   unattended run. The working recipe is: attempt once to learn the Android identity pin from the
   failure, restart the listener with `--expect-pin <android-pin>`, then connect — the Mac then reports
   `authenticated=true` and skips pairing.
2. **The Mac receiver must hold AppKit automatic termination.** It was being ended silently
   (`_kLSApplicationWouldBeTerminatedByTALKey`) between measurement phases, which looks exactly like a
   protocol failure. `--p08-mirror`, `--advertise-on-launch` and `--listen` now disable automatic and
   sudden termination for the life of the process.

Still open, and only for these reasons: **deep Doze and overnight** need the handset unplugged and idle
(the S24 refuses `force-idle` while charging: `Unable to go deep idle; stopped at INACTIVE`), and the
30-minute/notification-denied rows need a session that lasts longer than this run.

## Defects found and fixed during bring-up

1. A start request carrying a new host/port was ignored while a session was running, so a long run kept
   the stale target silently. The service now restarts its worker when the target changes.
2. The notification was refreshed from inside the synchronised log write, which deadlocked the worker
   against the main thread: the session froze after a few beats with no error and the service still
   advertised "connected". Notification updates now happen on a main-thread supervisor, the notification
   shows the heartbeat age, and a watchdog restarts a worker that has not beaten for 50 s.
3. **The first watchdog was too aggressive**: during a genuine Wi-Fi outage the worker is legitimately
   silent for longer than the threshold while it backs off, so the watchdog fired
   (`worker stalled: no heartbeat for 50678ms`) and replaced a healthy worker; the process was also
   replaced in that episode. The stall check now applies only while the session claims to be `connected`.
   Re-verified: during a 75 s outage there were 0 false stalls and 3 reconnect attempts, and the session
   resumed in the same process once Wi-Fi returned.

## Observations to follow up

- On the S24, the first `store.remember(...)` after a clean install threw
  `IllegalStateException: could not persist the pin for 'p14-mac'` and succeeded on the next attempt. The
  session recovered by itself, but a first-use Keystore failure in `KeystorePinStore` needs its own look
  before pairing depends on it.
- `POST_NOTIFICATIONS` is a runtime permission on API 37. The service starts and runs without it; the
  notification is simply suppressed. Test 6 must therefore distinguish "service runs" from "user can see
  and act on it".

## Not yet claimed

Deep Doze (unplugged), overnight survival, notification dismissal and permission-denied behaviour, and
battery drain. The S24 rows that did run used a charging handset over USB.
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
