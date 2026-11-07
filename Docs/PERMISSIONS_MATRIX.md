# Permissions and Entitlements Matrix

This file is the source of truth for permission minimization. The agent must compare this matrix against the actual Android manifest and macOS entitlements at every release.

## 1. Android permission candidates

| Permission / capability | Purpose | Required? | Policy |
|---|---|---:|---|
| `INTERNET` | Network sockets | Yes for LAN networking | Normal permission; not sufficient alone for Android 17 target LAN access |
| `ACCESS_LOCAL_NETWORK` | Direct LAN access on Android 17/API 37+ | Conditional | Prefer NSD system picker if it fully meets requirements; otherwise request with clear rationale |
| `FOREGROUND_SERVICE` | Foreground service base permission | Required for applicable service | Declare only as needed |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | MediaProjection foreground service | Required for mirroring | Only when mirroring feature exists |
| MediaProjection consent | Screen capture | Per session | System prompt; not a manifest runtime permission |
| `BLUETOOTH_CONNECT` | Optional Bluetooth connection | Optional | Request only after user enables Bluetooth feature |
| `BLUETOOTH_SCAN` | Optional Bluetooth scanning | Optional | Request only if actual discovery requires it |
| `BLUETOOTH_ADVERTISE` | Optional Bluetooth advertising | Optional | Request only if role requires it |
| `RECORD_AUDIO` | Possible audio/call experiment | Not MVP | Must not add without separate approval |
| `POST_NOTIFICATIONS` | Foreground service notification visibility on applicable versions | Evaluate | Explain mirroring session notification |
| `WAKE_LOCK` | Keep CPU active during streaming if required | Evaluate | Add only after measured need |
| `ACCESS_WIFI_STATE` / `CHANGE_WIFI_MULTICAST_STATE` | Wi-Fi/NSD support depending implementation | Evaluate | Verify actual API requirements |
| Accessibility service | Remote input | Prohibited | Never add |
| SMS / RECEIVE_SMS | OTP extraction | Prohibited for MVP | Never add |
| Notification listener | OTP extraction | Prohibited | Never add |
| Root/system signature permission | Input injection | Prohibited | Never add |
| Call log / phone state / default dialer role | Calling | Not MVP | Separate future decision only |

Important Android 17 note:
`ACCESS_LOCAL_NETWORK` is enforced for apps targeting API 37+. Verify current SDK docs and actual device permission UI. Test NSD picker route first; do not falsely claim the app has zero LAN permission if direct socket operations are gated.

## 2. macOS permissions

| Permission / key | Purpose | Required? |
|---|---|---:|
| `NSLocalNetworkUsageDescription` | Explain LAN usage | Yes |
| `NSBonjourServices` | Declare service types browsed | If Bonjour is used |
| Keychain access | Store paired identity | Yes, scoped |
| Screen Recording | Capture Mac display | No, not for viewing Android |
| Accessibility | System-wide UI control | No |
| Input Monitoring | Global input observation | No for MVP |
| Microphone | Call/audio experiment | No for MVP |

## 3. Permission audit gate

Before adding any permission:
1. Identify the exact feature.
2. Link official documentation.
3. Confirm API-level need.
4. Check whether a system-mediated or narrower alternative exists.
5. Add user-facing rationale.
6. Update this matrix.
7. Obtain owner approval for any permission outside the approved list.

At every phase, run a manifest/entitlement diff and report newly added permissions.
