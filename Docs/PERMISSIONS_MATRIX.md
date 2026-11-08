# Permissions and Entitlements Matrix

This file is the source of truth for permission minimization. The agent must compare this matrix against the actual Android manifest and macOS entitlements at every release.

**Research status:** updated 2026-11-08 from the research pass in `RESEARCH_AND_FEASIBILITY.md`. No manifest or entitlements file exists yet, so every row below is a *plan*, not an observed diff. The first real audit happens in Phase 0 (empty manifest) and every phase after that.

## 1. Android permission candidates

| Permission / capability | Purpose | Required? | Researched facts (2026-11-08) | Policy |
|---|---|---:|---|---|
| `INTERNET` | Network sockets | Yes for LAN networking | Normal permission. For `targetSdk` ≤ 36 it implicitly grants local-network access; that grant is temporary and blocked once the target SDK is raised to 37 (R-01). | Declare. Never present as sufficient for API 37+ LAN access. |
| `ACCESS_LOCAL_NETWORK` | Direct LAN access, **and any NSD advertisement**, on Android 17/API 37+ | Conditional | Runtime permission in the `NEARBY_DEVICES` group. Required for `NsdManager.registerService()` unconditionally, and for discovery without a picker. Apps in the same `NEARBY_DEVICES` group with an existing grant are not prompted again. Android documents a permission-reset-counter strategy and an `ANDROID_NETWORK_BLOCKED_REASON_LNP` blocked-reason code (R-01, R-02). | **Preferred order:** (1) no broad permission — Mac advertises, Android selects through the NSD picker and dials out (ADR-010); (2) request this permission only if P02 proves the picker path cannot sustain the session, with a rationale screen and denial handling. |
| NSD picker (`DiscoveryRequest.FLAG_SHOW_PICKER`) | Select a specific peer without broad LAN access | Primary path | Not a permission. Grants per-service access that persists across reboots, is re-checkable with `checkPermissionForService()` and reusable via `FLAG_USER_APPROVED_ONLY`; may be revoked by the user or for storage reasons (R-02). | Prefer over the runtime permission. Show the picker only on user action. Re-check the grant before every reconnect. |
| `FOREGROUND_SERVICE` | Foreground service base permission | Required for mirroring and for the background clipboard session | Required since Android 14 alongside a type-specific permission (R-04). | Declare once either feature exists. |
| `FOREGROUND_SERVICE_CONNECTED_DEVICE` | Foreground service type for the persistent LAN session | Required for background sync | Type `connectedDevice` covers "interactions with external devices that require … a network connection"; no timeout is documented and it is not blocked from `BOOT_COMPLETED` (R-20, ADR-023). | Declare with the clipboard background feature; declared on the Play Console app-content page (R-21). |
| `CHANGE_NETWORK_STATE` | Satisfies the `connectedDevice` FGS runtime prerequisite | Declared, never exercised | The `connectedDevice` type requires **declaring** one of `CHANGE_NETWORK_STATE` / `CHANGE_WIFI_STATE` / `CHANGE_WIFI_MULTICAST_STATE` / `NFC` / `TRANSMIT_IR`, or holding a granted Bluetooth/UWB runtime permission. It is a normal permission, so declaring it produces no prompt (R-20). | Declare only this prerequisite. Do **not** use it as a substitute for asking the user anything, and do not add Bluetooth permissions to satisfy the type instead. |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | MediaProjection foreground service | Required for mirroring | Must be combined with `android:foregroundServiceType="mediaProjection"`; a missing type throws `MissingForegroundServiceTypeException` (R-04). | Only with the mirroring feature. |
| MediaProjection consent | Screen capture authorisation | Per session | Not a manifest permission. Single-use token: one `getMediaProjection()` per consent intent, one `createVirtualDisplay()` per `MediaProjection`, else `SecurityException` (R-04). | Always fresh consent; never cache or replay. |
| `POST_NOTIFICATIONS` | Foreground service notification visibility | Declare; request in context | Not required to launch a foreground service, but without it the notification is hidden from the drawer and the service is only visible in the FGS Task Manager; Android 13+ FGS notifications are dismissible while the service continues (R-21). | Declare from the first FGS. Request when the user first enables background sync or starts mirroring, never at first launch; the app's own UI remains the authoritative session indicator. |
| `BLUETOOTH_CONNECT` | Optional Bluetooth connection | Optional, non-core | Relevant only to optional Bluetooth. Android's `BluetoothHidDevice` is the reverse direction from what the product wants (R-13). | Request only after the user enables the optional Bluetooth feature. Bluetooth HID input is NO-GO (ADR-016). |
| `BLUETOOTH_SCAN` | Optional Bluetooth scanning | Optional, non-core | — | Only if actual discovery needs it, only after opt-in. |
| `BLUETOOTH_ADVERTISE` | Optional Bluetooth advertising | Optional, non-core | — | Only if the chosen role needs it, only after opt-in. |
| `ACCESS_WIFI_STATE` / `CHANGE_WIFI_MULTICAST_STATE` | Wi-Fi state / multicast lock | Evaluate | Current `NsdManager` documentation states the multicast lock is not needed for foreground discovery/advertising on recent SDK extension levels (R-02 source page). | Do not add pre-emptively (it is a normal permission today but is a dead permission that will age badly); add only if a measured test shows discovery failing without it. |
| `WAKE_LOCK` | Keep CPU awake during streaming | Evaluate | Doze ignores wake locks, so a wake lock cannot defeat network suspension (R-20). | Add only after a measured need in P08, and never as a Doze workaround. |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Exempt the app from Doze | **No** | Would keep the socket alive through Doze but is not justifiable for a consumer clipboard/mirroring app and invites Play policy scrutiny (R-20). | Do not add. Accept best-effort liveness plus reconnect (ADR-023). |
| `RECORD_AUDIO` | Audio playback capture | No (MVP) | Playback capture also requires MediaProjection consent, same user profile, and subject-app opt-in; call audio can never be captured (R-15) and Android 17 adds background-audio hardening (R-16). | Do not add. Separate owner approval required for any audio track. |

## 2. Android capabilities explicitly excluded by research

| Capability | Why excluded | Evidence |
|---|---|---|
| Accessibility Service | Ceiling of remote input; prohibited by `AGENTS.md` | ADR-004, ADR-015 |
| Default IME / custom keyboard | Only way to read the clipboard in the background (R-09); changes the user's keyboard and privacy model | ADR-013 |
| `KNOX_REMOTE_CONTROL` / Knox SDK | `signature` protection; Knox API 38 requires DO/PO or an authorized scope; DeX injection APIs absent on Android 16+ | R-14, ADR-017 |
| Notification listener | Call/OTP content access; prohibited | R-17, ADR-019 |
| Default dialer / `ROLE_DIALER` | Changes the user's dialer; out of scope | R-17, ADR-019 |
| SMS / `RECEIVE_SMS` | OTP extraction; prohibited; Android 17 additionally delays standard SMS OTPs for 3 hours | R-17 |
| Contacts / call log / phone state | Not needed by any MVP feature | `AGENTS.md` |
| Broad storage permissions | No file-clipboard feature in MVP | `AGENTS.md` |
| Root / system-signature permissions | Prohibited | `AGENTS.md` |
| Developer Options "disable screen share protections" | Removes a user protection; developer-only escape hatch; invalid as acceptance evidence | R-07, ADR-020 |

## 3. macOS permissions, entitlements and privacy gates

| Item | Type | Purpose | Required? | Researched facts |
|---|---|---|---:|---|
| `NSLocalNetworkUsageDescription` | Info.plist string (TCC prompt) | Explain LAN usage | Yes | Any direct unicast/multicast or Bonjour use requires it; first local-network operation shows the system alert, recorded per user account and changeable in System Settings → Privacy & Security → Local Network (R-10). |
| `NSBonjourServices` | Info.plist array | Service types the app registers/browses | Yes, once the Mac advertises | Registering, browsing **and** resolving all require local-network access; a mismatched type fails silently (R-10, ADR-022). |
| Local Network TCC privilege | User consent state | Gate for all LAN I/O | Yes | Can be `undetermined`; operations are denied until the user answers. App must handle denial explicitly (R-10). |
| `NSPasteboard` programmatic access | User consent state (`NSPasteboard.accessBehavior`, macOS 15.4+) | Reading the general pasteboard | Conditional | Default is *ask upon programmatic access*; user-originated paste-related access is always allowed; per-app setting can be `ask`, `alwaysAllow`, `alwaysDeny` (R-11). Not an entitlement, but functionally a permission in the product design (ADR-014). |
| Keychain | Entitlement / API | Store this Mac's identity key/certificate and the paired Android pin | Yes, scoped | Generated per install (ADR-024): non-exportable P-256 key + self-signed certificate + the peer's SPKI pin. Use the app's own Keychain access group; no iCloud keychain synchronisation of pairing material. |
| App Sandbox network client | Entitlement | Dial out to Android | Only if sandboxed distribution is used | `com.apple.security.network.client`. |
| App Sandbox network server | Entitlement | Accept the Android connection (Mac is the listener under ADR-010) | Only if sandboxed distribution is used | `com.apple.security.network.server`. |
| Screen Recording | TCC permission | Capture the Mac display | No | Not needed to view Android's stream; only for a separately approved Mac-capture feature. |
| Accessibility | TCC permission | System-wide UI control | No | Never for this product. |
| Input Monitoring | TCC permission | Global keyboard/mouse observation | No | Not needed for MVP (view-only mirroring). |
| Microphone | TCC permission | Audio | No | MVP has no audio (ADR-018). |
| Bluetooth | TCC permission | Optional Bluetooth | No | Not required; Bluetooth HID input is NO-GO (ADR-016). |

## 4. Configuration that is not a permission but has security impact

| Item | Where | Requirement |
|---|---|---|
| Peer-certificate pinning | Android TLS code (pinning `X509TrustManager`) | Required by ADR-024. A static `<trust-anchors>` entry is **not** used because the peer certificate is only known after pairing, and the file is fixed at build time. Certificate transparency is not applied to custom trust anchors (R-03); the programmatic-pinning equivalent must be confirmed in P13. |
| Keystore identity key | Android Keystore (`KeyGenParameterSpec`) | Key must include `PURPOSE_SIGN`, otherwise the generated self-signed certificate has an invalid signature (R-22). No permission is involved. |
| Certificate transparency setting | Same file | Leave at the platform default. **Never** disable CT globally or per-domain to work around a TLS failure. |
| ECH / `domainEncryption` | Same file | Leave at default. ECH only applies where the library and server support it; a local IP connection must not depend on it (R-03). |
| Trust-all `TrustManager` / hostname-verifier override | Android TLS code | Forbidden. Peer identity comes from pinning, not from disabling verification. |
| `allowAudioPlaybackCapture` | Android manifest | Not set (no audio). |
| `FLAG_SECURE` on DroidBridge's own sensitive windows | Android UI | Required for pairing-code and diagnostics screens (R-08). |

## 5. Permission audit gate

Before adding any permission:
1. Identify the exact feature.
2. Link official documentation, with the date checked.
3. Confirm the API-level need and whether a narrower/system-mediated alternative exists (this is what moved `ACCESS_LOCAL_NETWORK` from "required" to "fallback" in ADR-010).
4. Add user-facing rationale copy.
5. Update this matrix.
6. Obtain owner approval for any permission outside the approved list.

At every phase, run a manifest/entitlement diff and report newly added permissions. A phase report that does not state the permission diff is incomplete.
