# P02 — LAN discovery: the API 37 picker path

**Status: verified on the target device.** The Android 17 system picker appears, lists the Mac, and
hands back a peer that resolves and connects. The Mac's own listener accepts the connection and
echoes bytes. This was the prototype that could not run on the OnePlus; it runs here.

| Field | Value |
|---|---|
| Date | 2026-11-09 |
| Device | Samsung Galaxy S24 Ultra, **SM-S928B** (`e3q`) |
| Android version | **17** |
| API level | **37** |
| One UI | **9.0** (`ro.build.version.oneui=90000`) |
| Build | `CP2A.260605.016.S928BXXS6EZJ1` |
| Security patch | 2026-10-05 |
| Verified boot | `green` |
| Build type | `user` |
| USB connected | yes |
| USB debugging enabled | yes |
| Wireless debugging enabled | **no** |
| Device lock state | unlocked (`deviceLocked=0`) |
| Wi-Fi | `wlan0` 192.168.31.246/24 |
| Bluetooth | on (not used by this prototype) |
| Mac | 192.168.31.109, macOS 27.0.1, arm64 |
| Experiment app | `com.droidbridge.experiment.discovery` 0.1.0 |

## The result

The picker path works end to end. The sequence, in order:

| Step | Observation |
|---|---|
| Build `DiscoveryRequest` with `FLAG_SHOW_PICKER` | `flags=2`, built without throwing |
| `discoverServices(request, executor, listener)` | returned without throwing |
| `onDiscoveryStarted` | fired — picker becomes visible |
| System picker | **`NsdPickerActivity`**: "Choose a device to connect" / "P02 discovery wants to connect to a device on your local network" |
| Peers listed | `ControlTest2` (a stale leftover) and `DroidBridge-P02-Mac` (published by this run) |
| User selects `DroidBridge-P02-Mac` | `onServiceFound: name='DroidBridge-P02-Mac' type='_droidbridge-dev._tcp.' host=null port=0` |
| `resolveService` | `onServiceResolved: host=192.168.31.109 port=47999` |
| TCP connect from the phone | Mac logged `inbound connection from 192.168.31.246` → `connection ready` |
| Duplex echo | 26 bytes sent, echoed verbatim, received back on the phone |

**The picker is real, it is the OS's own UI (`com.google.android.connectivity.resources`), and it
mediates peer selection.** That is the single most important P02 finding, and it is now measured
rather than assumed.

### `onServiceFound` carries no address — and that is by design

The service record arrives with `host=null port=0`. This is not a bug: the picker grants the peer,
and resolving is a separate step that yields the address. A design that assumed `onServiceFound`
gives a connectable endpoint would fail at the first connection attempt. The two-step
discover-then-resolve flow is mandatory, and the experiment records them as separate events for
exactly this reason.

### `FLAG_USER_APPROVED_ONLY` works, and this is the product-critical flag

After the user approved `DroidBridge-P02-Mac` once through the picker, a later run with
`FLAG_USER_APPROVED_ONLY` found the same peer **with no picker shown at all**:

```
start: requesting discovery with FLAG_USER_APPROVED_ONLY
approved-only discoverServices returned without throwing
approved-only onDiscoveryStarted: _droidbridge-dev._tcp
approved-only onServiceFound: 'DroidBridge-P02-Mac' port=0
```

That is the difference between a usable reconnect and asking the user to re-approve on every launch.
Approve once, reconnect silently thereafter.

## The correction that matters most: the Mac was never blocked

The previous session concluded that Bonjour and inbound connections were blocked at the host level
by Little Snitch, and that a minimal non-sandboxed control binary failed identically. **That
conclusion was wrong, and the record is corrected here.**

What was actually measured this session, with no changes to Little Snitch, the firewall, or any VPN
configuration:

| Test | Result |
|---|---|
| Phone → Mac, plain Python TCP listener on 47999 | **connected** (`CONNECTED from ('192.168.31.246', 54230)`) |
| Phone → Mac, the real `DroidBridge.app` listener on 61113 | **connected** (`connection ready: 192.168.31.246:56584`) |
| Phone → Mac, duplex echo through the app | **26 bytes round-tripped verbatim** |
| Mac advertises `_droidbridge-dev._tcp` via `NWListener` | **`advertising ... port=61113`**, and the phone's picker listed it |
| Mac advertises via `dns-sd -R` | registered and active, listed in the picker |

So the host is not blocking anything, Bonjour publication works, and the app's listener works. The
earlier diagnosis was an inference from a failing test, not a measurement of the host, and it sent
the previous session looking at Little Snitch when the answer was elsewhere. Nothing in this session
required disabling a security control, which is the strongest evidence that none was in the way.

The likely explanation for the earlier failure is the one thing that differs between then and now:
the test is being run against a device on the same subnet with a resolved address, and the earlier
attempts used a `nc` invocation that connected but never reached `.ready` because the peer sent no
data and the connection was torn down by the timeout. That is a test-harness artifact, not a network
block. It is recorded as the probable cause, not as a proven one.

## The permission finding, stated precisely

`ACCESS_LOCAL_NETWORK` is a runtime permission on API 37, and it is requested and granted normally:

- Before the request: `ACCESS_LOCAL_NETWORK: NOT GRANTED`.
- After the request: `ACCESS_LOCAL_NETWORK: GRANTED`, and the picker path works.

What was **not** established is that the permission is enforced for this API surface. After
`pm revoke`, the state reads `granted=false` while `discoverServices` still returns without throwing,
the picker still appears, resolve still returns `192.168.31.109:47999`, and
`FLAG_USER_APPROVED_ONLY` still finds the peer. A fresh install that had never been approved found
nothing on the approved-only path, which is consistent with the picker grant being the real gate
rather than the permission.

**Honest conclusion:** the picker grant is what mediates access, and the permission's enforcement
could not be demonstrated on this build. This is recorded as an open question rather than a claim
either way, because the difference matters: if the permission is not enforced, a production app must
not rely on it as a security boundary, and the picker grant becomes the only real control. Resolving
it needs a test that distinguishes "the OS exempts this call" from "the revoke did not take effect
for an already-running process", which is a separate experiment.

**Product consequence, which is not in doubt either way:** DroidBridge must keep the picker as the
consent mechanism. It is the OS-mediated control, it is visible to the user, and it is what the
approved-only flag keys off. `ACCESS_LOCAL_NETWORK` is declared because the API requires it, not
because it can be trusted to gate anything.

## What was built

`android/experiments/discovery/` — a separate installable app, own `applicationId`, never depended
on by `:app`, so `ACCESS_LOCAL_NETWORK` cannot reach the production manifest. This is the AGENTS.md
isolation rule made structural rather than a convention.

`DiscoveryProbe` runs one measurement per action and logs a single greppable line per event. Three
states are kept distinct because they are three different product problems: permission refused,
picker shown, peer discovered. A run that reaches "picker shown" but never "discovered" is a real
and reportable outcome, and collapsing the states would hide it.

Error codes are translated to their `NsdManager` constant names. A report that says `errorCode=3`
makes the next person look it up; `FAILURE_ALREADY_ACTIVE` does not.

## Not yet done

- **60-second sustained duplex.** The echo was verified (26 bytes round-tripped), but not held open
  for a full minute with continuous traffic. The transport that will actually do this is P03's TLS
  session, so the sustained test belongs there rather than in a `nc` harness.
- **Wi-Fi change, address change, reboot.** Not exercised. These need the session layer to be
  meaningful.
- **Mac → Android inbound under the picker grant.** Not tested. The experiment is Android-dials-out
  only, which is Path A per ADR-010.
- **`AdvertisingRequest` (Android advertising).** The API exists (`AdvertisingRequest.Builder`) but
  was not exercised; Path A does not need it.

## Reproducing

```bash
cd android
./gradlew :experiments:discovery:assembleDebug
adb install -r experiments/discovery/build/outputs/apk/debug/discovery-debug.apk
adb shell am start -n com.droidbridge.experiment.discovery/.DiscoveryActivity

# On the Mac, publish a peer the picker can list:
dns-sd -R "DroidBridge-P02-Mac" "_droidbridge-dev._tcp" local 47999

# Or run the real advertiser:
./macos/build/Build/Products/Debug/DroidBridge.app/Contents/MacOS/DroidBridge --advertise-on-launch

# Then in the app: grant the permission, "Discover with picker", select the Mac, "Resolve last found".
adb logcat -d -s P02Discovery
```

Note: on this One UI build the notification shade repeatedly steals input focus during scripted
runs. A `KEYCODE_POWER` off/on cycle reliably restores the activity. This is a test-harness
annoyance, not an app defect.
