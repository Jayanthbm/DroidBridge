# P14 — Background session persistence (clipboard while mirroring is off)

**Status:** **ADR-023's premise holds on the target device and fails on the OnePlus.** On the **S24 Ultra (Android 17 / API 37)** the `connectedDevice` foreground service kept the TLS session alive while the app was backgrounded (10 min), with the screen off (`mWakefulness=Dozing`, 5 min), across a Wi-Fi off/on cycle, and across a real reboot (`BOOT_COMPLETED` → reconnected within 450 ms). On the **OnePlus Nord (Android 12)** the identical build was frozen within ~40 s of backgrounding despite a valid foreground service, so background sync there is foreground-only. **Deep Doze and overnight were measured on 2026-11-22/23** (handset unplugged, 8 h 17 m, 21 automatic reconnects, rows G–I pass in the section below).

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
| 4d | Deep Doze (unplugged) | **PASS, measured 2026-11-22/23** — 935 heartbeats over 8 h 17 m; 210 freezes of 20-45 s survived; long Doze windows of 80, 45 and 30 min, each ended by an automatic reconnect | not run |
| 4e | Overnight, 8 h | **PASS, measured 2026-11-22/23** — 21 automatic reconnects, all authenticated on the stored pin with no prompt or tap | **Not run** |
| 5 | Doze forcing | **Not needed** — `force-idle` refuses while charging, so the row ran as a real unplug (see 4d) | not run |
| 6 | Notification behaviour | **Not run** — `POST_NOTIFICATIONS` was not granted (the prompt cannot be answered without a click) and the service ran regardless, with the notification suppressed | **Not run** |
| 7 | Battery drain | **Measured** — 100 % → 75 % across the 8 h 17 m overnight run | **Not run** |

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

Still open: the 30-minute awake-endurance row, notification dismissal/denied behaviour, and a repeat of
the overnight under a different network. Deep Doze and overnight themselves are measured above.

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

### Notification-permission-denied row — measured (2026-11-22)

**Pass.** With `POST_NOTIFICATIONS` revoked (`granted=false`) the foreground service still starts and the
session still runs:

```
autostart requested from visible activity
startForegroundService accepted (origin=activity-autostart)
startForeground succeeded for connectedDevice
connecting to 192.168.31.109:59628
state=connected
```

The notification is suppressed rather than fatal: `dumpsys notification` reports the package with
`importance=NONE userSet=true` while the `p14-session` channel keeps `mImportance=2`. Android does not
refuse the FGS, and heartbeats continued with the screen off.

The earlier "blocked" note was a tooling problem, not a product one: the session could only be started
by tapping, and the conditioned device UI wedged on a stale `NotificationShade`. The service is
`exported=false` and `run-as` cannot reach the activity manager, so the fix is to use the harness extras
the activity already accepts:

```
adb shell am start -n com.droidbridge.experiment.backgroundsession/.MainActivity \
  --es host <ip> --ei port <port> --es pin <mac-pin> --ez autostart true
```

That starts the session with no UI interaction, which is how every future P14 row should be driven.

## Run staged 2026-11-22 23:06 — conditions recorded before unplugging

The deep-Doze and overnight rows need the phone **unplugged, screen off, stationary, Wi-Fi on**; while
it charges, `deviceidle` refuses to leave `ACTIVE`. Everything below was verified with the cable still
attached, so the unplug is the only remaining action and the first gap in the log is the start of the
measurement.

| Item | Value at start |
|---|---|
| Mac listener | `--listen --port 59670 --expect-pin b710a0b7…` (P14's own identity), 24 h window, under `caffeinate -dimsu -t 57600` |
| Listener log | `/tmp/p14-night.log`, **every line timestamped** by a wrapper, because the harness itself prints no times and gap durations are the measurement |
| Heartbeat | phone `PING` every 15 s; the Mac answers each one and logs `FRAME type=ping seq=N`, so a missing sequence number is an undelivered heartbeat |
| Mac heartbeat sink | `P03: FRAME type=ping …` + `SENT pong …`, one pair per 15 s |
| Phone app | `com.droidbridge.experiment.backgroundsession`, `SessionService` foreground, `isForeground=true`, `foregroundServiceType=0x10` (connectedDevice) |
| Battery exemption | **none** — `dumpsys deviceidle whitelist` does not list the app (ADR-043 forbids one) |
| Wake locks | none held by the experiment |
| Battery | 100 %, USB powered at start |
| Wi-Fi | enabled, `192.168.31.197/24`; host/port/pin are persisted in SharedPreferences, so a process restart resumes against the same listener |
| Screen | `screen_off_timeout=30000`, `stay_on_while_plugged_in=0` |
| Doze | `mState=ACTIVE` (still charging at staging time) |
| USB debugging | enabled (needed to stage); wireless debugging disabled — the run itself needs no ADB |

Gaps are summarised the next morning with `python3 /tmp/p14-gaps.py /tmp/p14-night.log`, which prints
every gap longer than three missed heartbeats with its wall-clock start, end, duration and the number of
heartbeats the radio did not deliver.

## Deep Doze and overnight — measured 2026-11-22 23:10 → 2026-11-23 07:23

Run: S24 Ultra `SM-S928B`, Android 17 / API 37, unplugged at **23:10:24** (`status=discharging plug=none`
in batterystats), screen off (`display_state_changed state=DOZE`), stationary, Wi-Fi on. No battery
exemption, no wake lock, ADB not used during the run. Evidence: `/tmp/p14-night.log` (timestamped
harness output, 935 pings, 22 authentications) plus `dumpsys batterystats` as an independent check.

| # | Row | Result | Evidence |
| --- | --- | --- | --- |
| G | **Deep Doze: heartbeats continue, in bursts** | **PASS** | 935 pings over 8 h 17 m; median inter-heartbeat **15.0 s**; 210 stalls of 20–45 s (median 29 s, max 30 s) where the app was frozen but the session survived — no reconnect, sequence numbers continuous |
| H | **Deep Doze: the session recovers without user action** | **PASS** | **21 automatic reconnects**, each preceded by ~36 s of silence and followed by a new authenticated session (`AUTHENTICATED (stored pin matched; no user action needed)`); no prompt, no tap, no code |
| I | **Overnight: Doze windows lengthen as the night deepens** | **PASS** | session lengths between authentications: 76 min, then 6, 27, 7, 4, 4, 5, 30, 17, 13, 14, 10, 8, 5, 22, 27, 49, 16 min, then the long windows — **80 min** (04:44 → 06:04), **45 min** (06:04 → 06:50), **30 min** (06:50 → 07:20) |
| J | **Overnight: silence is Doze, not a dead app** | **PASS** | 221 min of the 497 min window had no heartbeat at all (44 %), yet every window ended with the phone reconnecting on its own and the foreground service still running (`isForeground=true` after the night) |
| K | **Overnight: cost is bounded and reported** | **measured** | battery 100 % → **75 %** over 8 h 10 m (≈3.0 %/h) with the foreground service, Wi-Fi and 21 reconnects; backoff grew to 60 s inside the deepest windows (`state=reconnecting in 60s`), which is why the 80-minute window recovered promptly rather than retry-storming |

**What this does and does not establish.** It establishes that a `connectedDevice` foreground service
with a real protocol heartbeat survives a full night of deep Doze on this device class: the app is
never killed, the session always comes back, and nothing needs the user. It does **not** establish a
stable single session — Doze freezes the app for ~30 s at a time and the session drops roughly hourly
(21 times), so "connected overnight" means *reconnectable overnight*: the product must treat reconnect
as routine and keep it cheap, idempotent and invisible. That is exactly what ADR-043's heartbeat-age
stall detection buys, and the cost is visible here as the reconnect count rather than as a silent lie
about being connected.

Corroboration, not assumption: `dumpsys batterystats` shows `status=discharging plug=none` from 23:10,
screen `DOZE`/`DOZE_SUSPEND` transitions through the night, and level 100 → 75 by 07:20; the Mac log
shows every ping and pong with a wall-clock timestamp, so silence cannot be confused with a logging gap.

## 2026-11-24: Phase 6 battery/perf review — no changes

- **Discovery is event-driven**: `NsdManager` discovery + resolve, no polling loop. It stays live for
  the whole process life by design (reconnect re-resolve and peer-vanished detection both need it);
  the cost is mDNS multicast reception only, and Doze suspends the radio anyway (the 2026-11-23
  measurement: 3%/h with reconnects).
- **Clipboard poll (Mac)**: 1 s `changeCount` timer, started on session ready and stopped on session
  stop; each tick is one integer read in the common case. Negligible.
- **No wake locks, no battery-optimisation exemption** (ADR-043 unchanged; the manifest has neither).
- **Keepalive**: one PING/PONG every 15 s on the socket — trivial bytes, and it is what keeps the
  session honest (two missed intervals → reconnect).

Conclusion: nothing worth changing; the review is recorded as the Phase 6 battery/perf item.

## Screen-off without a foreground service — measured 2026-11-26 (the complement to row C)

Row C measures the case background sync cares about: the `connectedDevice` service is running, so the
process is not frozen and the session holds. The opposite case surfaced by accident during the v1
acceptance sweep and is worth keeping, because it is what a user sees before they enable background sync.

Setup: S24 Ultra `RZCY81TKBSV`, Android 17, Debug app; USB **disconnected**, USB debugging **off**,
wireless debugging **off**. A session was connected with **no share running** and **background sync not
enabled**, so no foreground service owned it. The phone was left alone on the dashboard at 17:58:32 (last
activity: a `clipboard.accept`). Its `screen_off_timeout` is 30000 ms, so the display went off around
17:59:0x.

- Mac heartbeats were healthy to the second: `ping:5` 17:58:47, `ping:6` 17:59:02, `ping:7` 17:59:17.
- **17:59:31.951** `closed: receive error … NWError 54 - Connection reset by peer`. A reset, not a timeout:
  the phone closed its own side (with the Mac's last ping unread in the buffer, so Android's stack answered
  RST). The Mac's socket had answered a ping 14 s earlier.
- The phone then ran its normal reconnect budget (10 attempts, ADR-051). **None of those attempts reached
  the Mac** — the listener was listening throughout and logged not one incoming connection for six
  minutes. With the display off the app had no usable network, so every attempt failed before the LAN.
- **~18:02 (estimated from the budget)** it expired into the terminal `GAVE_UP`: `state: stopped`,
  "the connection kept dropping, so retrying stopped; tap Try again when the Mac is back".
- **18:12:54**, after the owner tapped **Try again** (which routes through the picker and auto-connects),
  `verify: pin matched 3cac0163…` → `connection ready … authenticated=true` → `READY … code=836 347`,
  then `ping:1/3/4` at 15 s. No code comparison, no re-pairing.

Reading: this does not contradict row C, it delimits it. Screen-off survival is a property of the
`connectedDevice` foreground service, not of the app in general. Before background sync is enabled, an idle
session cannot survive the display going off on this device, and the give-up copy misattributes the cause —
"when the Mac is back" while the Mac never left and the phone's own radio was asleep. Two follow-ups, both
product decisions rather than measurements, were taken the same day: the retry budget is now held until the
device can reach the network and the copy names the real cause (ADR-061, device verification pending).
