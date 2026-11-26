# Permissions and Entitlements Matrix

This file is the source of truth for permission minimization. The agent must compare this matrix against the actual Android manifest and macOS entitlements at every release.

**Research status:** updated 2026-11-08 from the research pass in `RESEARCH_AND_FEASIBILITY.md`. **First real audit: 2026-11-09 (Phase 0)** — see §6 for the measured diff against the built artifacts. Rows in §1 and §3 remain the *plan* for later phases; §6 records what actually shipped.

## 1. Android permission candidates

| Permission / capability | Purpose | Required? | Researched facts (2026-11-08) | Policy |
|---|---|---:|---|---|
| `INTERNET` | Network sockets | **Yes — declared in `:app` since Phase 2 (2026-11-22)** | Normal permission. For `targetSdk` ≤ 36 it implicitly grants local-network access; that grant is temporary and blocked once the target SDK is raised past 36 (R-14). | Declared. Phase 2 added it with the LAN session: NSD browsing and the TLS socket are the only uses. |
| `ACCESS_LOCAL_NETWORK` | Direct LAN access, **and any NSD advertisement**, on Android 17/API 37+ | **Declared in `:app` since Phase 2 (2026-11-22); requested in context, never at launch** | Runtime permission in the `NEARBY_DEVICES` group. Required for `NsdManager.registerService` and, on API 37, for resolving a discovered service. Below API 37 it does not exist and the request is a no-op — which is what the OnePlus (Android 12) run exercised. | Declared for the product LAN session after P14 measured that its absence surfaces as `SocketTimeoutException` on API 37 (ADR-023). Still not used for advertising: Android connects outbound (ADR-010). |
| NSD picker (`DiscoveryRequest.FLAG_SHOW_PICKER`) | Select a specific peer without broad LAN access | Primary path | Not a permission. Grants per-service access that persists across reboots, is re-checkable with `checkPermissionForService()` and reusable via `FLAG_USER_APPROVED_ONLY`; may be revoked by the user or for storage reasons (R-02). | Prefer over the runtime permission. Show the picker only on user action. Re-check the grant before every reconnect. |
| `FOREGROUND_SERVICE` | Foreground service base permission | Required for mirroring and for the background clipboard session | Required since Android 14 alongside a type-specific permission (R-04). | Declare once either feature exists. |
| `FOREGROUND_SERVICE_CONNECTED_DEVICE` | Foreground service type for the persistent LAN session | Required for background sync | Type `connectedDevice` covers "interactions with external devices that require … a network connection"; no timeout is documented and it is not blocked from `BOOT_COMPLETED` (R-20, ADR-023). | Declare with the clipboard background feature **and** on the Play Console app-content page with a description, an impact statement, the "Continuous Data Transfer to an External Device" use case and a demo video (R-26, §1A). |
| `CHANGE_NETWORK_STATE` | Satisfies the `connectedDevice` FGS runtime prerequisite | Declared, never exercised | The `connectedDevice` type requires **declaring** one of `CHANGE_NETWORK_STATE` / `CHANGE_WIFI_STATE` / `CHANGE_WIFI_MULTICAST_STATE` / `NFC` / `TRANSMIT_IR`, or holding a granted Bluetooth/UWB runtime permission. It is a normal permission, so declaring it produces no prompt (R-20). | Declare only this prerequisite. Do **not** use it as a substitute for asking the user anything, and do not add Bluetooth permissions to satisfy the type instead. |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | MediaProjection foreground service | Required for mirroring | Must be combined with `android:foregroundServiceType="mediaProjection"`; a missing type throws `MissingForegroundServiceTypeException` (R-04). | Only with the mirroring feature, and declared on the Play Console app-content page with its use case and demo video (R-26, §1A). |
| MediaProjection consent | Screen capture authorisation | Per session | Not a manifest permission. Single-use token: one `getMediaProjection()` per consent intent, one `createVirtualDisplay()` per `MediaProjection`, else `SecurityException` (R-04). | Always fresh consent; never cache or replay. |
| `POST_NOTIFICATIONS` | Foreground service notification visibility | Declare; request in context | Not required to launch a foreground service, but without it the notification is hidden from the drawer and the service is only visible in the FGS Task Manager; Android 13+ FGS notifications are dismissible while the service continues (R-21). | Declare from the first FGS. Request when the user first enables background sync or starts mirroring, never at first launch; the app's own UI remains the authoritative session indicator. |
| `BLUETOOTH_CONNECT` | Optional Android Bluetooth feature | Optional, non-core | Android's `BluetoothHidDevice` is the reverse role from the desired Mac-to-phone HID direction (R-13). | Request only after the user enables a specific Android-side Bluetooth feature; do not request it for P16/P17 unless the actual design proves it necessary. |
| Android Bluetooth runtime permissions for receiving standard Mac HID input | P16/P17 feasibility | POC only | The Mac is evaluated as standard HID device paired through Android system Bluetooth settings. Do not add Android Bluetooth permissions merely to receive OS-level keyboard/pointer input; verify on target. | Bluetooth remains opt-in and is enabled only by explicit user action. |
| `BLUETOOTH_SCAN` | Optional Bluetooth scanning | Optional, non-core | — | Only if actual discovery needs it, only after opt-in. |
| `BLUETOOTH_ADVERTISE` | Optional Bluetooth advertising | Optional, non-core | — | Only if the chosen role needs it, only after opt-in. |
| `ACCESS_WIFI_STATE` / `CHANGE_WIFI_MULTICAST_STATE` | Wi-Fi state / multicast lock | Evaluate | Current `NsdManager` documentation states the multicast lock is not needed for foreground discovery/advertising on recent SDK extension levels (R-02 source page). | Do not add pre-emptively (it is a normal permission today but is a dead permission that will age badly); add only if a measured test shows discovery failing without it. |
| `WAKE_LOCK` | Keep CPU awake during streaming | Evaluate | Doze ignores wake locks, so a wake lock cannot defeat network suspension (R-20). | Add only after a measured need in P08, and never as a Doze workaround. |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Exempt the app from Doze | **No** | Would keep the socket alive through Doze but is not justifiable for a consumer clipboard/mirroring app and invites Play policy scrutiny (R-20). | Do not add. Accept best-effort liveness plus reconnect (ADR-023). |
| `RECORD_AUDIO` | Audio playback capture | **Yes — declared in `:app` since 2026-11-26** (owner-approved that day, ADR-057) | Playback capture also requires MediaProjection consent, same user profile, and subject-app opt-in; call audio can never be captured (R-15, measured in P12) and Android 17 adds background-audio hardening (R-16). | The product ships media playback audio only: usage set is `USAGE_MEDIA`/`USAGE_GAME`/`USAGE_UNKNOWN`, so calls and notifications are never captured. The permission is requested in the share flow and a denial continues with screen-only mirroring. A Play Console microphone declaration is required. |
| `FOREGROUND_SERVICE_MICROPHONE` | `microphone` foreground-service type prerequisite | **Yes — declared in `:app` since 2026-11-26** (ADR-057) | Required since Android 14 for a service that captures audio while backgrounded (R-04). | Needed only because the mirroring service captures playback audio; if audio is off the service never uses the type's capability but still declares it, so the type is declared on the Play Console page. |

## 1A. Google Play declarations that accompany these permissions

These are not manifest permissions, but a submission without them is a policy failure. Each declaration must match the manifest: a used-but-undeclared type and a declared-but-unused type are both enforcement risks (R-26).

| Declaration | When it is required | What Play requires |
|---|---|---|
| Foreground service type `connectedDevice` | Whenever the background-sync service ships | Description of the functionality; user impact if deferred/interrupted; use case "Continuous Data Transfer to an External Device"; **a demo video showing the user pairing and connecting to the device inside the app**, not in system settings. |
| Microphone declaration (`RECORD_AUDIO` + `microphone` FGS type) | Whenever media audio ships | The Play Console "Microphone" declaration: what is recorded (audio from other apps' media playback), when (only while the owner is sharing a screen with audio enabled), that it is played live on the owner's own Mac, and that nothing is stored or sent to a server. The data-safety form stays "no collection" because the audio is transient and peer-to-peer. |
| Foreground service type `mediaProjection` | Whenever mirroring ships | Same four items; use case "Media or Content Projection and streaming or recording with MediaProjection API"; video showing the user starting the projection to an external display. |
| `ACCESS_LOCAL_NETWORK` | Only if the runtime-permission fallback is taken (from 2027-08-25, for API 37+ targets) | A declaration that the permission is needed for core functionality because device pickers are insufficient, plus a matching statement in the store description. The picker-first design avoids this entirely. |
| Data safety form | Every release | Declared data collection and sharing. DroidBridge has no cloud endpoint and no telemetry, so the honest answer is "none collected" — which must stay true in code (ADR-003). |

**Maintenance:** Play's use-case list changes over time (geofencing stops being an approved FGS use case on 2027-01-27), so the declaration is re-checked at every submission, not written once.

## 2. Android capabilities explicitly excluded by research

| Capability | Why excluded | Evidence |
|---|---|---|
| Accessibility Service | Not part of input path | Prohibited by `AGENTS.md`; Bluetooth HID P16/P17 is the evaluated route | ADR-004, ADR-015, R-32 |
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
| Local Network TCC privilege | User consent state | Gate for local-network operations | Yes | Can be `undetermined`; operations are denied until the user answers. Under ADR-010 the trigger is **Bonjour advertising**, not accepting the Android connection: TN3179 exempts listening/accepting inbound TCP but requires access for every Bonjour operation (R-28). The state is tracked by code signature, so an ad-hoc rebuild can ask again (R-27). App must handle denial explicitly (R-10). |
| `NSPasteboard` programmatic access | User consent state (`NSPasteboard.accessBehavior`, macOS 15.4+) | Reading the general pasteboard | Conditional | Default is *ask upon programmatic access*; user-originated paste-related access is always allowed; per-app setting can be `ask`, `alwaysAllow`, `alwaysDeny` (R-11). Not an entitlement, but functionally a permission in the product design (ADR-014). |
| App Sandbox | Entitlement | Confine the app to declared resources | **Yes, from the first build** | `com.apple.security.app-sandbox`. Not required for Developer ID distribution but required for the Mac App Store, and it is the least-privilege baseline; enabling it late turns late sandbox violations into a redesign (ADR-026). It is an *unrestricted* entitlement, so it needs no provisioning profile (R-28). |
| App Sandbox network server | Entitlement | Listen for the Android connection (Mac is the listener under ADR-010) | **Yes** | `com.apple.security.network.server`. For TCP the network entitlements restrict only the *initiation* of a connection, so one accepted socket carries clipboard and video in both directions. |
| App Sandbox Bluetooth | Entitlement | Publish a Bluetooth Classic HID SDP record and open outbound L2CAP channels to the paired phone (Phase 5, ADR-050). P17 proved the code path unsandboxed; the sandboxed combination is measured: SDP publication succeeds (2026-11-24, `published: yes`) | **Yes, from Phase 5** | `com.apple.security.device.bluetooth`.
| App Sandbox network client | Entitlement | Send Bonjour/mDNS responses over UDP | **Yes** | `com.apple.security.network.client`. Bonjour is UDP, where the entitlements restrict data flow as well as initiation — an app with only the server entitlement "can receive, but not send". Advertising therefore needs **both**, even though the Mac never dials the phone (R-28). |
| Hardened Runtime | Signing option (not an entitlement) | Required for notarisation | **Yes** | `codesign -o runtime`, or the Xcode capability. Apple's notary service rejects a build without it, and without a secure timestamp (R-27). |
| Developer ID provisioning profile | Embedded profile | Authorize the `keychain-access-groups` claim used by ADR-025 | **Yes**, because of the keychain choice | `Contents/embedded.provisionprofile`. Only *restricted* entitlements need one; App Sandbox and Hardened Runtime do not. A development profile must be replaced by a **distribution** profile at release, and the bundle re-signed afterwards. |
| Developer ID Application certificate + secure timestamp | Signing identity | Sign distribution builds | **Yes** for any release | Only the Apple Developer Program **Account Holder** can create the certificate. An unnotarised build is blocked by Gatekeeper with only the System Settings → Privacy & Security → Open Anyway override (R-27). |
| Keychain | Entitlement / API | Store this Mac's identity key/certificate and the paired Android pin | Yes, scoped | Data protection keychain (`kSecUseDataProtectionKeychain`) under the app's own `keychain-access-groups` entry (ADR-025): non-exportable P-256 key + self-signed certificate + the peer's SPKI pin. No iCloud synchronisation (`kSecAttrSynchronizable` off). |
| Screen Recording | TCC permission | Capture the Mac display | No | Not needed to view Android's stream; only for a separately approved Mac-capture feature. |
| Accessibility | TCC permission | System-wide UI control / upstream direct-input capture | No | Never request for DroidBridge. The reference repo's optional direct-input/event-tap feature is excluded; POCs send only events initiated inside DroidBridge UI. |
| Input Monitoring | TCC permission | Global keyboard/mouse observation | No | Not needed for MVP (view-only mirroring). |
| Microphone | TCC permission | Audio | No | The Mac plays audio decoded from the session (`AVAudioEngine`); it never records from a Mac microphone, so no TCC microphone grant is requested (ADR-057). |
| `com.apple.security.temporary-exception.files.absolute-path.read-write` | Signing entitlement (measurement only) | Let the P10 luma harness write its recording where the measurement can read it | **No in any shipped or acceptance build** | Declared only in `macos/App/DroidBridge.measurement.entitlements`, used only for instrumented harness runs; the path is a single `/private/tmp/p10-rec/` directory. The production target keeps the audited three-key set (`DroidBridge.entitlements`, ADR-026) and must not be signed with this file. Recorded here because an entitlement that exists in the repository must be visible in the permission matrix even when it never ships.
| `com.apple.security.device.bluetooth` | macOS App Sandbox entitlement | BLE/Classic HID POC | Present only in isolated P16 target | Added to `P16BLEHID.entitlements` with app sandbox; the P16 target also declares `NSBluetoothAlwaysUsageDescription`. It is absent from the production DroidBridge target. The system authorization flow and actual HID pairing remain untested; Bluetooth activity must follow explicit opt-in.

## 4. Configuration that is not a permission but has security impact

| Item | Where | Requirement |
|---|---|---|
| Peer-certificate pinning | Android TLS code (pinning `X509TrustManager`) | Required by ADR-024. A static `<trust-anchors>` entry is **not** used because the peer certificate is only known after pairing, and the file is fixed at build time. Certificate transparency is not applied to custom trust anchors (R-03); the programmatic-pinning equivalent must be confirmed in P13. |
| Keystore identity key | Android Keystore (`KeyGenParameterSpec`) | Key must include `PURPOSE_SIGN`, otherwise the generated self-signed certificate has an invalid signature (R-22). No permission is involved. |
| Certificate transparency setting | Same file | Leave at the platform default. **Never** disable CT globally or per-domain to work around a TLS failure. |
| ECH / `domainEncryption` | Same file | Leave at default. ECH only applies where the library and server support it; a local IP connection must not depend on it (R-03). |
| Trust-all `TrustManager` / hostname-verifier override | Android TLS code | Forbidden. Peer identity comes from pinning, not from disabling verification. |
| `allowAudioPlaybackCapture` | Android manifest | Not set, so the platform default applies. It governs whether *other* apps may capture DroidBridge's own audio, and DroidBridge plays no media of its own; the shipped feature captures other apps' playback instead (ADR-057). |
| `codesign --deep` | macOS signing | Never used for signing. Sign inside-out: Apple documents that `--deep` applies one option set to every item (leaking the app's entitlements onto nested code) and misses code in data locations (R-27). |
| `com.apple.security.get-task-allow` | macOS entitlements | Must not be `true` in a shipped build — the notary service rejects it. Xcode strips it on export; manual signing must not add it back (R-27). |
| Signed-build identity for TCC | macOS local network privilege | Local network privacy tracks the program by its **code signature**. Ad-hoc builds can lose the recorded decision when the signature changes; that is expected in development, and a re-prompt is never "fixed" by weakening the prompt or the entitlement (R-27, R-28). |
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

## 6. Measured audit — Phase 0 (2026-11-09)

First audit against real build artifacts rather than intent. Method: `aapt2 dump permissions` and
`aapt2 dump badging` on the debug APK, and `codesign -d --entitlements :-` on the signed Release
bundle. Full narrative in `Docs/prototypes/PHASE0-repository-and-environment.md` §6.

### 6.1 Android manifest diff

P16's isolated receiver (`com.droidbridge.experiment.bluetoothhid`) was separately audited on
2026-11-20. Its source declares no `uses-permission`; the built APK contains only
`com.droidbridge.experiment.bluetoothhid.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, an AndroidX-injected
app-private `signature` permission with no Bluetooth capability. It adds no Android Bluetooth,
Accessibility, network, storage, clipboard or notification permission. This is not the production app.

| Permission | Declared in source | Present in built APK | Verdict |
|---|---:|---:|---|
| *(none from §1)* | — | — | Correct: no feature exists yet |
| `com.droidbridge.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | no | **yes** | Injected by AndroidX Core. App-private `signature` permission; grants no access to anything and cannot be requested by other apps. Not a matrix candidate; recorded so the diff is not reported as "zero". |

No permission from §1 is declared. No new permission was added in Phase 0, so no owner approval is
outstanding for this phase.

### 6.2 Android manifest security settings

| Setting | Value | Matrix reference |
|---|---|---|
| `allowBackup` | `false` | §4 — no clipboard history, no cloud |
| `dataExtractionRules` | all domains excluded (cloud backup and device transfer) | §4 |
| `fullBackupContent` | `false` | §4 |
| `exported` | `true` on the launcher activity only | §4 |
| `debuggable` | debug builds only; release has no signing config | §4 |

### 6.3 macOS entitlements diff

Read from the signed **Release** bundle:

| Entitlement | Present | Required by | Verdict |
|---|---:|---|---|
| `com.apple.security.app-sandbox` | yes | ADR-026 | Correct |
| `com.apple.security.network.client` | yes | ADR-026 (Bonjour is UDP; client gates sending) | Correct |
| `com.apple.security.network.server` | yes | ADR-026 (Mac is the listener, ADR-010) | Correct |
| `com.apple.security.device.bluetooth` | yes | ADR-050 (Phase 5: Classic HIDP device role) | Correct — classic device role measured working under the sandbox (2026-11-24) |
| `com.apple.security.get-task-allow` | **no** | must be absent in a shipped build (§4) | Correct — see note |
| `com.apple.security.cs.*` | no | must never be added (§3) | Correct |
| `keychain-access-groups` | no | needed only with the Developer ID profile (ADR-025) | Correctly deferred to Phase 2 |

**Note on `get-task-allow`:** Xcode injects it into any ad-hoc/development-signed build, so the
first Release build of this project carried it. That is a real defect against §4, not a cosmetic
one. Fixed with `CODE_SIGN_INJECT_BASE_ENTITLEMENTS: NO` on the Release configuration only, and
re-verified by reading the entitlements back out of the rebuilt bundle. Debug keeps it, because a
build without it cannot be debugged.

### 6.4 macOS Info.plist privacy keys

| Key | Value | Matrix reference |
|---|---|---|
| `NSLocalNetworkUsageDescription` | present, explains LAN-only clipboard and mirroring | §3 |
| `NSBonjourServices` | `["_droidbridge-dev._tcp"]` | §3, ADR-022 |

Nothing advertises yet, so the Local Network prompt is not triggered in Phase 0. That behaviour is
P11.

### 6.5 Signing state

Ad-hoc (`Signature=adhoc`, `TeamIdentifier=not set`). Hardened runtime is requested in the build
settings but Xcode disables it for ad-hoc signing. Notarisation not attempted. **This build is not a
release candidate** and must not be described as one.

### 6.6 Measured audit — Phase 6 (2026-11-24)

Method: `aapt2 dump permissions` on the freshly built `:app` debug APK and `codesign -d --entitlements :-`
on the Debug bundle at `~/Applications/DroidBridge.app` (signed with the stable measurement identity,
no measurement-entitlements file).

Android (`:app` merges `:feature:*` transitively):

| Permission | Present | Required by | Verdict |
|---|---:|---|---|
| `INTERNET` | yes | Phase 2 LAN session | Correct |
| `ACCESS_LOCAL_NETWORK` | yes | API 37 grant (ADR-023) | Correct |
| `FOREGROUND_SERVICE` | yes | mirroring | Correct |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | yes | mirroring | Correct |
| `FOREGROUND_SERVICE_CONNECTED_DEVICE` | no in `:app` | background clipboard sync (experiment module only) | Correct — not yet in the product graph |
| `POST_NOTIFICATIONS` | yes | FGS notification (carries Stop) | Correct |
| `CHANGE_NETWORK_STATE` | no in `:app` | `connectedDevice` type prerequisite (R-20) | Correct — arrives with the background-sync feature |
| `RECEIVE_BOOT_COMPLETED` | no in `:app` | experiment module only (P14, not in the `:app` graph) | Correct — documented in ADR-023 |
| `WAKE_LOCK` | **no** | ADR-043 stands | Correct |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | **no** | — | Correct |
| `RECORD_AUDIO` | **yes** | media playback audio in the mirroring service (owner-approved 2026-11-26, ADR-057) | Correct — usage set excludes calls and notifications |
| `FOREGROUND_SERVICE_MICROPHONE` | **yes** | `microphone` type prerequisite for the same service | Correct — declared with the audio feature |
| any BLUETOOTH_* | **no** | opt-in row unchanged | Correct |
| SMS/contacts/call-log/storage/notification-listener | **no** | prohibited | Correct |

macOS (production bundle):

| Entitlement | Present | Verdict |
|---|---:|---|
| `app-sandbox`, `network.client`, `network.server`, `device.bluetooth` | yes | The audited four-key set; Bluetooth measured sufficient for the Classic HIDP role (2026-11-24) |
| `get-task-allow` | yes (Debug) | Correct for a debug build; Release strips it (project.yml) |
| `com.apple.security.cs.*` / temporary-exception.* | no | Correct |

Permission diff vs Phase 5: **one approved addition** — `RECORD_AUDIO` plus the `microphone` FGS
type, owner-approved on 2026-11-26 to ship media playback audio (ADR-057). No other new permission
or entitlement was added in Phase 6; the
revocation and UX work reuses existing capabilities. The `RECEIVE_BOOT_COMPLETED` row is present in
the table because the P14 background-session experiment is part of the debug build's graph; it is
documented and owner-approved (ADR-023), and the product release split stays per ADR-023.

Measured correction (same audit): `:feature:clipboard` declares only the permissions it needs for its
foreground card (`FOREGROUND_SERVICE`-free LAN usage), and the `connectedDevice` FGS type plus
`RECEIVE_BOOT_COMPLETED` exist only in the `:experiments:background-session` module, which is **not**
in the `:app` graph. The measured `:app` APK permission set is exactly:

`INTERNET`, `ACCESS_LOCAL_NETWORK`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PROJECTION`,
`FOREGROUND_SERVICE_MICROPHONE`, `POST_NOTIFICATIONS`, `RECORD_AUDIO` (owner-approved 2026-11-26,
ADR-057; + the injected app-private
`com.droidbridge.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`).

So the rows for `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `CHANGE_NETWORK_STATE` and
`RECEIVE_BOOT_COMPLETED` above describe the *experiment* manifests, not the product APK; the product
diff vs Phase 5 is the single owner-approved audio addition recorded in ADR-057.
