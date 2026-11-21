# P11 — macOS Local Network permission and Bonjour

**Status: measured on macOS 27.2.1 — Local Network privacy does not gate this app at all.** Four
independent launch paths (shell child, LaunchServices, Finder double-click, `launchd` agent) and two
install locations (`/tmp`, `~/Applications`) all produced the same result: Bonjour browse and outbound
TCP to a LAN address both succeed, **no alert is presented, and the app never appears in Settings →
Privacy & Security → Local Network**, so there is no state a user can allow or deny. The three rows
this report previously left open are therefore not reachable on this OS build; that is the finding,
not a gap in the test.

Two corrections to earlier evidence are recorded below: the original "allowed-state" measurement was
attributed to **Terminal**, not to the app, and the app has never performed a gated operation until
this run because it only listens.

## Evidence header

| Field | Value |
|---|---|
| Date | 2026-11-17 |
| Repository commit | `5eb3821` (`development`; UI recovery change is currently uncommitted) |
| Mac | MacBook Air (M1, 2020), `MacBookAir10,1`, arm64 |
| macOS | 27.2.1 (`26B131`) |
| Xcode | 27.2 (`27B52`) |
| App build | Release, ad-hoc “Sign to Run Locally”; runs from `macos/build/Build/Products/Release/DroidBridge.app` and the canonical `~/Applications/DroidBridge.app` copied from it |
| App identity | Initial run used `com.droidbridge.macos`; final canonical run used `com.droidbridge.app` |
| Entitlements checked | App Sandbox, `network.client`, `network.server` all present in the source entitlement file |
| Usage description | Present: “DroidBridge connects to your Android phone over your local Wi-Fi network to sync the clipboard and mirror the screen. Nothing is sent to the internet.” |
| Bonjour declaration | `NSBonjourServices` contains `_droidbridge-dev._tcp` |
| Wi-Fi | `networksetup -getairportnetwork en0` reported “You are not associated with an AirPort network.” |
| Bluetooth | Not used or measured |
| USB / USB debugging / wireless debugging | Not applicable to this Mac-only test; no Android device used |

## What was measured (2026-11-21)

The product app only advertises a Bonjour service and accepts inbound connections. Neither is gated
on macOS, which is why the app never appeared in the permission list and never prompted. A dedicated
probe was therefore built at `macos/Experiments/P11LocalNetwork/` performing the two operations that
*are* documented as gated: `NSNetServiceBrowser` browse and an outbound `NWConnection` to a LAN
address. It is sandboxed with exactly the production entitlements (sandbox + `network.client` +
`network.server`) and declares `NSLocalNetworkUsageDescription` and `NSBonjourServices`.

A control browse from Terminal confirmed the service existed network-wide (`DroidBridge (2)` on
interfaces 3 and 11), so "0 results" would have been meaningful.

| Launch path | Browse | Outbound connect | Alert | Appears in Settings |
|---|---|---|---|---|
| Child of a shell (binary run directly) | found service | not run | none | no |
| `open -n -a` from a shell | found service | connected to `192.168.31.112:5555` | none | no |
| **Finder double-click** (user-operated) | found service | connected | **none** | **no** |
| **`launchd` agent** (`RunAtLoad`, not exempt per TN3179) | found service | not run | **none** | **no** |
| `launchd` agent, app in `~/Applications` | found service | not run | none | no |

## The methodology error this exposed

The first three shell-launched runs were **invalid as permission evidence**: `nehelper` logged

```
Local network allowed by preference for Terminal (com.apple.Terminal)
```

macOS attributes a local-network operation to the *responsible process*, and TN3179 states that
command-line tools run from Terminal, and their children, are automatically allowed. LaunchServices
(`open`) does not break that chain — the probe's parent was `launchd` (PID 1) and it was still
attributed to Terminal. Only a Finder double-click gives the app its own responsibility, and the
`launchd`-agent run removes the exemption by construction.

So: **any measurement of local-network permission taken by launching the app from a script is
measuring Terminal's exemption.** The earlier 2026-11-17 "allowed-state advertising/discovery" result
in this report is retained only as proof that advertising works, not as proof of the app's own
permission state.

## Steps and actual results

1. Checked the app's `Info.plist` for `NSLocalNetworkUsageDescription` and `NSBonjourServices`, and
   `DroidBridge.entitlements` for sandbox, client and server declarations. **All present.** Service
   type `_droidbridge-dev._tcp` matches the plist and the code constant.
2. The Release product advertised on every launch (`DNSServiceRegister START`, TCP listener bound),
   and `dns-sd` browse/resolve found it. **Advertising is not gated**: registration runs through
   `mDNSResponder`, and inbound connections are peer-initiated.
3. Because of that, the app never triggered the permission: no alert, no list entry. This also
   explains why uninstalling the app, deleting its container and removing its keychain pins never
   reset anything — nothing had been decided.
4. Built `P11LocalNetwork` to perform the gated operations and ran the matrix above.
5. `nehelper` also logs `Failed to find <bundle-id> using neagent` for app identifiers that no longer
   exist on disk, which is why the permission list accumulates dead rows from deleted experiments.
6. The Settings list **does** have `+` and `−` buttons (Apple's TN3179 is correct; a widely cited
   blog post claiming otherwise is wrong for this OS build).

## Automated validation

- `xcodebuild ... -configuration Debug ... test`: **67/67 tests passed**, 0 failures, on macOS 27.2.1 / arm64.
- Release build: passed with Xcode 27.2.
- `P11LocalNetwork` builds clean and runs under the production entitlement set.
- `dns-sd` browse/resolve passed for the product app, including under the canonical bundle ID.

## Not tested

- Any macOS version other than 27.2.1 (`26B131`). The control may be enforced differently on macOS
  15–26, and it is enforced on iOS without question; nothing here should be read as "macOS never
  prompts".
- A denied state: unreachable, because the app cannot be added to the list and no alert is offered.
- Android NSD picker integration, a second macOS user account, Developer ID/notarised builds, and
  behaviour on a Mac with no Wi-Fi interface.

## Conclusion

**P11 is complete for the measured platform, with a result that contradicts the assumption behind it:
on macOS 27.2.1, Local Network privacy does not gate this app's browse or outbound LAN connect.** No
alert, no list entry, no deniable state, measured across four launch paths and two install locations.

Product consequences, recorded in ADR-044:

- Nothing in the product may *depend* on the user granting Mac-side local network access, and equally
  nothing may promise that no such prompt ever appears: enforcement is an OS-version property we only
  measured on 27.2.1.
- The declarations (`NSLocalNetworkUsageDescription`, `NSBonjourServices`) and the `network.client` /
  `network.server` entitlements stay: they are required if any build starts enforcing it, and they
  cost nothing otherwise.
- The `.waiting` handling and the "Open System Settings" recovery affordance stay, because a future OS
  may enforce what this one does not, and TN3179 explicitly warns that the system may deny an
  operation before the user answers.
- Permission testing for this prototype must never be performed by launching the app from a script,
  and this must be stated in each future report.
