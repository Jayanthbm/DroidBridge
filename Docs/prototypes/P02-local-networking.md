# P02 — Android 17 local networking: picker vs broad permission

**Status: blocked on hardware, partially verified.** The Mac half of Path A is built and its TCP
half is proven. Bonjour publication and the picker path cannot be verified on this host and device.
None of the decisive P02 questions (U1, U2, U16) are answered.

| Field | Value |
|---|---|
| Date | 2026-11-09 |
| Device | OnePlus Nord CE (AC2001), Android 12, API 31, build AC2001_11_F.24 |
| Device Wi-Fi | 192.168.31.64/24 |
| Mac | macOS 27.0.1 (26A434), arm64, 192.168.31.109/24 |
| USB connected | yes |
| USB debugging enabled | yes |
| Wireless debugging enabled | no |
| Device lock state | locked (PIN) |

## Why this prototype cannot be completed on this hardware

P02 is an **Android 17 (API 37)** prototype. The mechanism it tests does not exist on Android 12,
and this was verified against the installed SDK rather than assumed:

| API surface | android-34 | android-37.0 |
|---|---|---|
| `NsdManager` | present | present |
| `NsdManager.ServiceInfoCallback` | present | present |
| `NsdManager.DiscoveryListener` | present | present |
| `android.net.nsd.DiscoveryRequest` | **absent** | present |

`android.net.nsd.DiscoveryRequest` — the class that carries `FLAG_SHOW_PICKER`,
`FLAG_USER_APPROVED_ONLY` and `FLAG_NO_PICKER` — exists only in the API 37 platform. The flags were
read out of the API 37 `android.jar` constant pool, not from documentation:

```
FLAG_NO_PICKER, FLAG_SHOW_PICKER, FLAG_USER_APPROVED_ONLY
```

`Manifest.permission.ACCESS_LOCAL_NETWORK` is declared `since="37.0"` in the platform's
`data/api-versions.xml`.

So the entire picker path — the thing P02 exists to evaluate — is unreachable on API 31. The
OnePlus cannot run this prototype. It needs the S24 Ultra on Android 17, which is not attached.

## What was built

The Mac half of Path A step 1 (`macos/Core/ServiceAdvertiser.swift`,
`macos/Core/ServiceAdvertisement.swift`, `macos/Features/Advertisement/AdvertisementView.swift`):

- a `NWListener` on an ephemeral port, advertising `_droidbridge-dev._tcp` with a TXT record
  carrying `v=1` and a random `sid`;
- an accept handler that echoes received bytes verbatim, so a duplex check compares what was sent
  with what came back rather than trusting a fixed reply;
- a UI showing the service type, port, session id and every connection event.

`--advertise-on-launch` starts advertising without a click, so the prototype can be exercised from
a shell. Default behaviour is unchanged.

## Verified

**10 macOS unit tests pass**, including:

- the service type string matches `NSBonjourServices` in the real app `Info.plist` (read from the
  host bundle, so a change to one without the other fails the test rather than the device);
- the service type is a legal Bonjour name (≤ 15 characters);
- the TXT record encodes as length-prefixed `key=value` pairs in deterministic order;
- session ids do not collide across 64 draws.

**TCP duplex echo, loopback:** three round trips, every reply byte-identical to what was sent. The
listener binds `*:61110` (all interfaces).

**Inbound connection from the phone:** the listener accepted a connection from `192.168.31.64`
(the phone) and logged it. The phone can reach the Mac — `ping` from the phone to
`192.168.31.109` returns 0% loss — so the network path and subnet are correct.

## Not verified, and the blocker

**The inbound connection never completed.** The listener logged `inbound connection from
192.168.31.64:38794`, then `connection failed: Network.NWError error 60 - Operation timed out`. The
TCP handshake did not finish.

**Bonjour never published.** The listener reports `.ready` and logs
`advertising type=_droidbridge-dev._tcp port=61110`, but `dns-sd -B _droidbridge-dev._tcp local`
does not list it. The browse harness was validated with a control: a service registered by
`dns-sd -R` on the same type *is* listed, so the browse is working and the app's registration is
genuinely not reaching mDNS.

Both symptoms are consistent with the macOS **Local Network** permission not being granted to this
build. No `kTCCServiceLocalNetwork` request appears in the `tccd` log for the app, and no prompt was
shown — the app was launched from a terminal, which is a known way to suppress that prompt.

### What was ruled out

- **Not the app's code.** A minimal non-sandboxed Swift binary with `NSBonjourServices` in its
  `Info.plist` also fails to publish, with `POSIXErrorCode(rawValue: 22)`. The same binary outside
  a bundle fails identically. So the failure is not in `ServiceAdvertiser`.
- **Not the sandbox entitlements.** The signed app carries exactly `app-sandbox`,
  `network.client`, `network.server`, `get-task-allow` (Debug). `network.server` is present.
- **Not the network.** The Mac reaches the gateway and the internet; the phone reaches the Mac.
- **Not the browse harness.** The `dns-sd` control works. (An earlier null result was a harness
  bug: `dns-sd` loses buffered output on `SIGTERM` and needs `SIGINT`.)

### What was found in the host environment

- **Little Snitch 6.2.3** network extension is activated and enabled.
- **Urban VPN Desktop** (`com.urban-vpn.mac`) is installed as a VPN configuration (currently
  disconnected).
- The macOS application firewall is enabled, block-all disabled, stealth mode off.

Any of these can interfere with local-network traffic and with mDNS. This is a host-environment
question that needs the owner: granting Local Network permission is a GUI action in System Settings,
and disabling a network filter is the owner's decision, not something an agent should do silently.

## Decisive questions still open

| ID | Question | Status |
|---|---|---|
| U1 | Does the NSD picker grant support a full-duplex session with the Mac as listener, across reboot, Wi-Fi change and address change? | **unanswered** — needs Android 17 |
| U2 | Does `registerService()` fail without `ACCESS_LOCAL_NETWORK` on One UI 9, and how does the prompt read? | **unanswered** — needs One UI 9 |
| U16 | Does the picker path require declaring `ACCESS_LOCAL_NETWORK` at all? | **unanswered** — needs Android 17 |

The remaining Path A steps (picker appears, select the Mac, 60 s duplex, `FLAG_USER_APPROVED_ONLY`
rediscovery, reboot, address change, grant revocation, inbound-under-grant) all need the API 37
picker and are therefore also unanswered.

## Defect found and fixed

**SwiftUI builds `TabView` content lazily.** `AdvertisementModel` was a `@StateObject` inside
`AdvertisementView`, so it was not constructed until the tab was first selected — which meant
`--advertise-on-launch` silently did nothing and the advertiser never started. The model is now
owned by the `App` and passed in as `@ObservedObject`. This is worth remembering for every future
tab in this app: anything that must run at launch cannot live inside a tab's own view.

## Reproducing

```bash
cd macos && xcodegen generate
xcodebuild -project DroidBridge.xcodeproj -scheme DroidBridge -configuration Debug test

APP=~/Library/Developer/Xcode/DerivedData/DroidBridge-*/Build/Products/Debug/DroidBridge.app
"$APP/Contents/MacOS/DroidBridge" --advertise-on-launch 2>&1 | grep advertise
# then, from the phone:
adb shell "echo hi | toybox nc -w 5 192.168.31.109 <port>"
```

To unblock: grant DroidBridge Local Network access in System Settings → Privacy & Security →
Local Network, and confirm whether Little Snitch is filtering it.
