# P02 — Android 17 local networking: the picker path

**Status: the picker path, sustained duplex and network recovery are validated on the target
device — Samsung Galaxy S24 Ultra, Android 17 / API 37, One UI 9.0 (2026-11-09). The Mac advertiser
restart test is complete and exposed a required one-time re-approval when the Mac's service instance
changes. One lifecycle test remains open: Mac sleep/wake.** Router restart, a Mac IP change,
guest-Wi-Fi client isolation and a Mac interface change could not be exercised in this environment
and are recorded as unavailable rather than guessed at.

The first S24 Ultra run established first contact (picker, `resolveService`, TCP connect, 26-byte
echo, approved-only rediscovery). This run adds the parts that were missing: a 60-second sustained
duplex test, a real network-loss recovery test, process recreation, reboot, inbound connections,
and an investigation of `ACCESS_LOCAL_NETWORK` enforcement.

| Field | Value |
|---|---|
| Date | 2026-11-09 |
| Device | Samsung Galaxy S24 Ultra, **SM-S928B** (`e3q`, `e3qxins`) |
| Android version | **17** |
| API level | **37** |
| One UI | **9.0** (`ro.build.version.oneui=90000`) |
| Build | `CP2A.260605.016.S928BXXS6EZJ1` |
| Security patch | 2026-10-05 |
| Verified boot | `green` |
| Build type | `user` |
| USB connected | **yes** for the sessions recorded here |
| USB debugging enabled | **yes** |
| Wireless debugging enabled | **no** |
| Device lock state | unlocked (`deviceLocked=0`) |
| Wi-Fi | `wlan0` 192.168.31.246/24 |
| Bluetooth | on (not used by this prototype) |
| Mac | MacBookAir10,1, 192.168.31.109, macOS 27.0.1, arm64 |
| Mac advertiser | `_droidbridge-dev._tcp`, name `DroidBridge`, port 61169 (ephemeral) |
| Experiment app | `com.droidbridge.experiment.discovery` 0.1.0, `minSdk=37` |

The final wireless-only, debugging-disabled gate was **not re-run for P02**. The project-level
USB-disconnected run in `PROGRESS_LOCAL.md` / `P03-live-handshake-evidence.md` §7 Run P3 is P03
evidence. This report records USB state honestly rather than borrowing it.

## The picker path still works

The app was reinstalled for this run, which reset its runtime permission to **NOT GRANTED**. With
that clean state, **picker discovery was started without requesting the permission**:

```
pick … topResumedActivity=com.google.android.connectivity.resources/.NsdPickerActivity
     "Choose a device to connect"
     "P02 discovery wants to connect to a device on your local network"
     listed peers: DroidBridge, DroidBridge-P03-Mac (stale advertiser from the P03 run)
```

Selecting `DroidBridge` produced:

```
onServiceFound: name='DroidBridge' type='_droidbridge-dev._tcp.' host=null hostAddresses=[] port=0
onServiceResolved: host=192.168.31.109 port=61169
```

The empty address in `onServiceFound` is by design: the picker grants the peer, and resolution is a
separate step. A design that assumed `onServiceFound` gives a connectable endpoint would fail at
the first connection attempt.

**The picker is real, it is the OS's own UI (`com.google.android.connectivity.resources`), and it
mediates peer selection.** That remains the single most important P02 finding.

## A — sustained duplex (60 s)

The Mac's `ServiceAdvertiser` echoes every byte verbatim. `EchoClient` sends
`P02 <seq> <sendNanos>` every 250 ms and compares the echoed line exactly, so the reply proves the
return path on the same socket. It records round-trip latency from the phone's own monotonic clock.

| Measurement | Value |
|---|---|
| Duration | 60,196 ms |
| Sent | **198** |
| Received | **198** |
| Mismatches | **0** |
| Errors | **0** |
| Connect failures | **0** |
| TCP connections | **1** |
| RTT minimum | **8,019 µs** (8.0 ms) |
| RTT maximum | **327,197 µs** (327 ms) |
| RTT average | **51,618 µs** (51.6 ms) |

The Mac side logged `inbound connection from 192.168.31.246`, `connection ready`, repeated echoes,
and `peer closed connection` at the end. The connection was stable for the full minute; the latency
spikes are consistent with the debug build, ADB polling and the Mac's echo loop rather than
disconnects.

## B — network change and recovery

### Real sustained loss (airplane mode)

`cmd wifi set-wifi-enabled disabled` is not sufficient on this firmware: it disables Wi-Fi for
about two seconds and the device re-enables it (`wifi_on` returned to 1). That produced a useful
*transient* recovery result (575/574 messages, 1 error, 2 connections), but not a sustained outage.
Airplane mode was used for the real test:

```bash
adb shell cmd connectivity airplane-mode enable
adb shell cmd connectivity airplane-mode disable
```

During a 180-second echo run, airplane mode was enabled at ~12 s, held for ~30 s, then disabled.

| Measurement | Value |
|---|---|
| Duration | 180,233 ms |
| Sent / received | **475 / 474** |
| Mismatches | **0** |
| Errors | **1** |
| Connect failures | **2** |
| TCP connections | **2** |
| RTT min / max / avg | 6,157 µs / 249,066 µs / 30,378 µs |

The recovery sequence in the logs:

```
19:58:05  ECHO disconnected: SocketException: Software caused connection abort
19:58:05  reconnect: approved-only rediscovery started
19:58:25  reconnect: no approved peer within 20000ms
19:58:27  reconnect: approved-only rediscovery started
19:58:44  approved-only onServiceFound: 'DroidBridge'
19:58:44  resolve-blocking resolved: host=192.168.31.109 port=61169
19:58:44  reconnect: endpoint=192.168.31.109:61169
```

`FLAG_USER_APPROVED_ONLY` found the Mac again **with no picker**, and the app reconnected without
re-approval. This is the recovery path the product needs; the outage was observed, not simulated.

### What is not tested

- **Mac IP address change:** not practical without administrator control of the Mac's network
  configuration. The code re-resolves through NSD instead of caching an IP, and the Mac service
  restart test (below) exercises re-resolution, but a real DHCP address change was not observed.
- **Router restart:** not performed (shared network; would disrupt the owner's environment).
- **Guest Wi-Fi / client isolation:** no guest network was available to test.
- **Mac interface change** (Ethernet/Wi-Fi switch): not available on this Mac setup.

## C — device and service lifecycle

### Android process recreation — passed

`adb shell am force-stop` then relaunch. New process, permission still **NOT GRANTED**:

```
approved-only onServiceFound: 'DroidBridge' port=0
onServiceResolved: host=192.168.31.109 port=61169
ECHO connected to 192.168.31.109:61169
ECHO progress sent=1 received=1 … rttLastUs=10264
```

No picker was shown. The approval survives process death.

### Android reboot — passed

`adb reboot`, wait for `sys.boot_completed=1`, then relaunch. New process:

```
approved-only onServiceFound: 'DroidBridge' port=0
onServiceResolved: host=192.168.31.109 port=61169
ECHO connected … rttLastUs=11374
ECHO progress sent=20 received=20 … mismatches=0 errors=0
```

No picker. The approval survives reboot, and discovery after boot is fast.

### Mac advertiser restart — complete, with a required re-approval

The Mac advertiser was killed during an echo and re-advertised on a new ephemeral port:

```
20:35:42  advertising type=_droidbridge-dev._tcp port=61172 sid=544f8f14…
20:35:46  dns-sd -L resolves DroidBridge to Jayanths-MacBook-Air.local.:61172,
          TXT sid=544f8f14… v=1
20:36:0x  clean relaunch advertises port 61173
```

The phone was then reconnected to the network (its DHCP address had changed from 192.168.31.246 to
192.168.31.123). Starting an echo with **no cached endpoint** caused approved-only rediscovery to
run for 60 s and find **nothing**:

```
21:19:33  reconnect: approved-only rediscovery started
21:19:53  reconnect: no approved peer within 20000ms
21:19:54  reconnect: approved-only rediscovery started
21:20:14  reconnect: no approved peer within 20000ms
… four attempts, no peer
```

The picker, started immediately afterwards, listed **`DroidBridge (2)`** — the same Mac, new service
instance — and listing it required a fresh approval. After that approval:

```
21:22:09  onServiceFound: name='DroidBridge (2)'
21:22:15  onServiceResolved: host=192.168.31.109 port=61173
21:22:21  ECHO connected to 192.168.31.109:61173
```

A subsequent force-stop/relaunch then found `DroidBridge (2)` with approved-only and connected to
`:61173` without a picker. **So the new approval works; the old one did not carry over.**

**Product finding:** on this build the picker approval is tied to the approved service instance, not
merely to the service name/type. A Mac app restart with a new ephemeral port/session id forces one
re-approval. Reconnect is automatic only after that. This matters for the product's "restart the Mac
and it reconnects" promise: either the Mac needs a stable service identity (or a stable port) that
the OS continues to accept, or the UX must present the re-approval as expected after a Mac restart.
This is recorded as a limitation, not hidden by the successful re-approval.

### Mac sleep/wake — **not completed**

Two attempts were made; neither produced a system sleep:

1. During a 180-second echo the owner was asked to sleep the Mac for ~10 seconds. The echo continued
   uninterrupted (587/587 messages, 0 errors, 1 connection) and `pmset -g log` recorded **no sleep
   event**.
2. A second attempt coincided with the Mac **restarting** (boot time 21:25:28) rather than sleeping.
   The phone saw the socket die at 21:24:57, retried approved-only rediscovery for ~90 seconds with
   no peer, and the run ended 282/280 with 2 errors. There is no sleep event to cite, so this is not
   sleep/wake evidence.

Mac sleep/wake is therefore recorded as **not completed**, not as a pass. The accidental restart did
provide a useful cold-restart recovery result (below), but it is a different event.

### Mac cold restart — recovery after reboot

The unplanned Mac restart exercised a full Mac outage:

- The phone's echo disconnected at 21:24:57 (Mac shutting down) and spent ~90 s attempting
  approved-only rediscovery while the Mac was down; all attempts found no peer.
- The Mac booted at 21:25:28. The advertiser had to be started again; it advertised a new ephemeral
  port **64615**.
- Approved-only discovery did **not** find the new instance until the picker was used and the new
  `DroidBridge` instance was approved. After approval, resolve returned `192.168.31.109:64615` and
  the echo connected (first RTT 10.5 ms, 16/16 messages shortly after).

This confirms the same limitation as the advertiser restart: a Mac restart invalidates the prior
instance approval and requires one picker approval. It also confirms the Android side survives the
Mac outage without crashing and does not silently claim a reconnection it does not have.

## D — inbound connection behavior (U1)

The experiment added a `ServerSocket` that echoes lines, plus an option to advertise it with
`AdvertisingRequest`. The Mac connects with a normal TCP client; no hidden API is involved.

| State | Result |
|---|---|
| Clean boot, `ACCESS_LOCAL_NETWORK` **not granted** | listener bound on port 43257, but the Mac's connect **timed out**; no accept logged |
| After granting the permission | Mac connected, sent `granted-inbound`, received it verbatim; Android logged `INBOUND accepted from 192.168.31.109` |
| `pm revoke` without reboot | inbound still connected (3/3 attempts); the kernel/network allow state had not been withdrawn |
| After a reboot, permission still denied | inbound was blocked again |

So inbound works, but it is **gated by `ACCESS_LOCAL_NETWORK`**, and revocation does not take
effect until a reboot on this build. That makes the permission a real control for listening
sockets, but not one that can be relied on to revoke access immediately.

**Architecture note:** this does not change ADR-010. Android still dials out as the primary path;
the inbound result documents that a Mac-initiated reconnect is technically possible only when the
permission is held, and would add a listening surface the product does not currently need.

## E — permission behavior, stated precisely

| Question | Measured answer |
|---|---|
| Is `ACCESS_LOCAL_NETWORK` required for the picker path? | **No.** Picker appeared, listed the Mac, resolved and connected with the permission NOT granted. |
| Is it required for approved-only rediscovery? | **No.** Process recreation and reboot both rediscovered without it. |
| Is it required for outbound TCP to a picker-approved peer? | **No.** The 60 s echo ran with it NOT granted. |
| Is it required for Android NSD advertising? | **Yes.** Without it: `SecurityException: Missing local network permission`. With it: `ADVERTISE registered name='DroidBridge-Android-P02'`. |
| Is it required for inbound connections? | **Yes**, on a clean boot. Inbound was blocked without it and worked after grant. Revocation was not applied until reboot. |
| Does `checkPermissionForService()` report the picker grant? | **No reliable answer on this build.** It returned `SERVICE_PERMISSION_DENIED` both before and after the runtime permission was granted, while discovery/approved-only/connect all worked. It cannot be used as the sole grant indicator. |

The permission prompt text was recorded:

> Allow P02 discovery to find, connect to and determine the relative position of nearby devices?

**Product consequence:** keep the system picker and its user approval as the visible consent
mechanism. Do not rely on `ACCESS_LOCAL_NETWORK` as the only security boundary, and do not assume
that revoking it takes effect for a running process. Advertising and listening need it; the
picker path does not.

## F — network environment

Performed: airplane-mode loss/recovery, transient Wi-Fi toggle, same-subnet verification, Mac
service advertisement. Not performed/available: guest Wi-Fi and client isolation, router reboot,
Mac interface change, Mac DHCP address change. These are listed as unavailable, not passed.

## What was built

`android/experiments/discovery/` — a separate installable app, own `applicationId`, never depended
on by `:app`, so `ACCESS_LOCAL_NETWORK` cannot reach the production manifest. This is the AGENTS.md
isolation rule made structural rather than a convention. The module now declares `minSdk=37`
because `DiscoveryRequest`, `AdvertisingRequest` and the permission only exist from API 37; that is
honest, not a compatibility fallback.

New and changed pieces:

- `DiscoveryProbe`: stops a previous discovery before starting a new one (the first run could hit
  `FAILURE_ALREADY_ACTIVE`), uses `hostAddresses` instead of the deprecated `host`, and adds
  blocking resolve, approved-only rediscovery, and `checkPermissionForService`.
- `EchoClient`: sustained ping-pong duplex, RTT statistics, disconnection detection and
  approved-only re-discovery. Runs on its own thread; never blocks the UI.
- `InboundProbe`: `ServerSocket` echo listener, Wi-Fi address detection, and
  `AdvertisingRequest` registration for the permission experiment.
- `EchoPayload`: the wire framing kept pure so it can be unit-tested.
- UI: 60 s / 180 s echo controls, inbound/advertise controls, live counters, permission refresh,
  and a log panel.
- Manifest/resources: API 37 `minSdk`, icon, data-extraction rules, no redundant labels.
- `scripts/verify-all.sh` now includes the discovery experiment, so CI cannot silently skip P02.

## Automated tests

`EchoPayloadTest` (7 tests) asserts the payload format, exact-echo matching, sequence/timestamp
mismatch rejection, null/truncated replies, and malformed parsing. These are framing tests; they
are **not** a substitute for the physical socket results above. The discovery module builds and
lints clean (`minSdk=37`, `warningsAsErrors`); the full verification gate is recorded in
`PROGRESS_LOCAL.md`.

## Acceptance status

| Item | Status |
|---|---|
| Picker path and resolve | Pass (S24 Ultra) |
| 60 s sustained duplex | Pass |
| Wi-Fi/airplane loss and recovery without re-approval | Pass |
| Android process recreation | Pass |
| Android reboot | Pass |
| Mac sleep/wake | **Open — neither attempt produced a sleep event** |
| Mac advertiser restart | **Pass with required re-approval**; old approval did not carry to the new instance |
| Mac IP change / router restart / guest Wi-Fi / interface change | Not available in this environment |
| Inbound under picker approval | Investigated; works only with `ACCESS_LOCAL_NETWORK`, revocation delayed to reboot |
| Permission enforcement | Investigated; picker path exempt, advertising/inbound gated |

P02 is not closed while Mac sleep/wake is open. The primary product path — picker approval,
approved-only reconnect, sustained duplex, recovery after a real outage, process recreation,
Android reboot and Mac restart with re-approval — is validated.

## Reproducing

```bash
cd android
./gradlew :experiments:discovery:assembleDebug \
          :experiments:discovery:testDebugUnitTest \
          :experiments:discovery:lintDebug
adb install -r experiments/discovery/build/outputs/apk/debug/discovery-debug.apk
adb shell am start -n com.droidbridge.experiment.discovery/.DiscoveryActivity

# On the Mac:
macos/build/Build/Products/Debug/DroidBridge.app/Contents/MacOS/DroidBridge --advertise-on-launch

# Then in the app: grant the permission if testing advertising/inbound, discover with the picker,
# select DroidBridge, resolve, and start Echo 60s or Echo 180s (recovery).
adb logcat -d -s P02Discovery P02Echo P02Inbound
```

Evidence tags: `P02Discovery` (picker, approved-only, resolve, reconnect), `P02Echo`
(`ECHO progress`, `ECHO_SUMMARY`, disconnect/reconnect), `P02Inbound` (`INBOUND`, `ADVERTISE`).
