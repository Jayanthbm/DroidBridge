# Research and Feasibility Register

**Research baseline:** 2026-11-07 (initial pack) — **last research pass: 2026-11-08**
**Target Android:** Samsung Galaxy S24 Ultra, India region, Android 17 / One UI 9 (owner-reported, not yet verified on device)
**Target macOS:** owner's Mac; exact model and macOS build still to be recorded (P00)
**Scope of this document:** feasibility research only. No application features, prototypes or device tests have been run. Everything below is documentation evidence and must be re-checked before implementation.

## 0. Status of this research stage

| Item | State |
|---|---|
| Official-source review of every high-risk dependency in `Docs/` | Complete for the items listed in §2 |
| Prototype code written | None |
| Physical-device evidence | None — nothing in this register is device-verified |
| Register entries with a GO / CONDITIONAL / NO-GO conclusion | All entries in §2 |
| Items still needing a prototype before a build decision | Listed in §3 |

**Confidence scale used below**

- **High** — stated explicitly in current official documentation for the exact target version.
- **Medium** — official documentation covers a nearby version or leaves an implementation detail open; needs a prototype.
- **Low** — vendor/official documentation is silent; conclusion rests on clearly-labelled secondary reports; prototype mandatory before any claim.

**Decision scale**

- **GO** — supported by public, documented APIs within the project's constraints; build it after the normal phase gate.
- **CONDITIONAL** — supported only on some paths, with user friction, or with product-visible limitations. Build only after the named prototype passes and the owner accepts the limitation.
- **NO-GO** — not achievable within the constraints (no Accessibility, no ADB/runtime debugging, no root, no privileged/OEM signature APIs, no bypasses), or achievable only in a degraded form the owner has not accepted.

## 1. Executive feasibility summary

| Capability | Decision | Basis |
|---|---|---|
| Android screen capture (MediaProjection) | **GO** | Documented consent flow; capture-only, no input injection |
| Android 17 local network access | **CONDITIONAL** | `ACCESS_LOCAL_NETWORK` required for targetSdk ≥ 37; NSD picker is the least-privilege route but has a hard limit on advertising (§2 R-02) |
| Full-duplex LAN session on Android 17 | **CONDITIONAL** | Only viable zero-broad-permission shape is "Mac advertises, Android selects via picker and connects out" — must be prototyped |
| Clipboard session while mirroring is off (background) | **CONDITIONAL** | Needs a `connectedDevice` foreground service with a visible notification; Doze can still suspend the network, so reconnect-on-resume, not an always-on promise (R-20) |
| Mac → Android text clipboard | **GO** with limits | `setPrimaryClip` is unrestricted; Android 15+ system clipboard UI is user-visible and cannot be suppressed |
| Android → Mac text clipboard | **CONDITIONAL** | `getPrimaryClip()` returns `null` unless the app has input focus or is the default IME — no IME/input-method requirement is allowed |
| Automatic two-way background clipboard | **NO-GO** (as a default) | Android focus restriction plus macOS pasteboard access prompt; manual/foreground baseline only |
| macOS pasteboard monitoring | **CONDITIONAL** | macOS 15.4+ `NSPasteboard.accessBehavior` can prompt or deny programmatic reads (§2 R-13) |
| Local screen streaming to a native Mac window | **GO** | MediaProjection → MediaCodec → local transport → VideoToolbox/AVSampleBufferDisplayLayer |
| H.264 pipeline in practice (encode → AVCC → display) | **GO**, prototypes required | Surface encoding removes colour conversion from our code entirely; MediaCodec emits Annex-B so one start-code→length-prefix conversion is mandatory for CoreMedia; the display layer decodes without a VT session, but its enqueue API is deprecated in favour of a macOS 27 replacement (§2 R-29–R-31) |
| Protected content stays protected (`FLAG_SECURE`, DRM) | **GO** (as a guarantee of *non*-capture) | Capture returns blank/unavailable; Android 15+ screenshare protections also redact notifications and password fields (§2 R-08) |
| Arbitrary remote touch/keyboard into other Android apps | **NO-GO** under current constraints | No public injection API; Knox requires signature permission plus device-owner/profile-owner or authorized scope; Bluetooth HID API direction is reversed (§2 R-14..R-16) |
| Mac acting as a Bluetooth HID peripheral for Android | **NO-GO** (pending contrary prototype evidence) | CoreBluetooth refuses to publish the HID-over-GATT service on macOS; classic HID transport is owned by `bluetoothd` (§2 R-14) |
| Samsung Knox remote injection | **NO-GO** for a consumer app | `KNOX_REMOTE_CONTROL` is `signature`-protected; since Knox API 38 non-DO/PO callers need an authorized scope; DeX injection APIs are absent on Android 16+ |
| Audio playback capture (media) | **CONDITIONAL** | Requires `RECORD_AUDIO` + MediaProjection consent + same user profile + app opt-in via capture policy |
| Call audio / cellular call capture | **NO-GO** | Playback capture cannot capture `USAGE_VOICE_COMMUNICATION`; Telecom roles are out of scope |
| Call notifications / answer-reject | **NO-GO** for MVP | Requires Telecom integration/role or a notification listener, both outside the approved permission set |
| Bluetooth as optional supplement | **CONDITIONAL**, non-core | Must never gate Wi-Fi features; HID direction problem unresolved |
| Android release on Google Play | **GO** with defined release work | API 36 is the floor since 2026-08-31, so `targetSdk 37` is above it, not below; two FGS types each need a description, impact statement, use-case choice and a demo video; `ACCESS_LOCAL_NETWORK` would additionally need a Play declaration from 2027-08-25 (§2 R-25, R-26) |
| macOS distribution to the owner | **CONDITIONAL** | Direct distribution requires a paid Apple Developer Program membership, a Developer ID certificate, hardened runtime, secure timestamp and notarisation; unnotarised apps are blocked by Gatekeeper with only an "Open Anyway" override (§2 R-27) |
| macOS App Sandbox with a network listener | **GO** | `app-sandbox` + `network.client` + `network.server`; both network entitlements are needed because Bonjour advertising is UDP multicast, and none of them requires a provisioning profile (§2 R-28) |

**Recommended MVP scope after this research:** pairing + LAN transport, Mac→Android clipboard, Android→Mac manual clipboard, view-only screen mirroring with honest protected-content and notification-redaction messaging. Remote input, audio, calls and Bluetooth HID stay research tracks.

## 2. Findings register

Each entry follows the required evidence format. "Checked" is the date the cited page was read.

---

### R-01 — Android 17 local network access is a runtime permission

- **Checked:** 2026-11-08
- **Target:** Android 17, API level 37, targetSdk 37
- **Sources (official):**
  - https://developer.android.com/privacy-and-security/local-network-permission
  - https://developer.android.com/about/versions/17/behavior-changes-17
  - https://developer.android.com/blog/posts/android-17-is-here
  - https://developer.android.com/reference/android/net/nsd/NsdManager
- **Finding (positive):** Android 17 (API 37) introduces the `ACCESS_LOCAL_NETWORK` runtime permission. Apps targeting API 37+ are blocked from the local network by default and must either (a) use system-mediated privacy-preserving pickers or (b) declare and request `ACCESS_LOCAL_NETWORK` at runtime.
- **Finding (negative / constraint):** `INTERNET` alone is no longer sufficient for targetSdk 37+. The permission sits in the existing `NEARBY_DEVICES` group, so a user who already granted a nearby-devices permission is not prompted again. Apps targeting ≤ 36 receive an implicit grant through `INTERNET`; that is temporary and blocked once the target SDK is raised. Android documents a *permission reset counter* strategy for apps the user previously refused, and an `ANDROID_NETWORK_BLOCKED_REASON_LNP` blocked-reason code for diagnosing blocked traffic.
- **Permission/role requirement:** `ACCESS_LOCAL_NETWORK` (runtime, `NEARBY_DEVICES` group) **or** a picker grant.
- **Limitations:** Enforcement is mandatory; there is no opt-out. Enterprise admins can pre-grant via `DevicePolicyManager.setPermissionGrantState()`; DroidBridge is not a managed app and must not assume that.
- **Alternatives:** pickers (see R-02) or lowering targetSdk (rejected — Play target-API requirements and security posture).
- **Decision:** **CONDITIONAL** — target API 37 is acceptable only because R-02 offers a least-privilege route.
- **Prototype:** P02.

---

### R-02 — Advertising a local service always requires the broad permission; the NSD picker only grants *discovery of a chosen peer*

- **Checked:** 2026-11-08
- **Target:** Android 17 / API 37; `NsdManager` (DNS-SD over mDNS)
- **Sources (official):**
  - https://developer.android.com/reference/kotlin/android/net/nsd/NsdManager
  - https://developer.android.com/reference/android/net/nsd/DiscoveryRequest
  - https://developer.android.com/privacy-and-security/local-network-permission
- **Finding (positive):** `NsdManager.discoverServices()` / `registerServiceInfoCallback()` with `DiscoveryRequest.FLAG_SHOW_PICKER` shows a system picker; once the user selects a service, the app may connect to the addresses returned by `NsdServiceInfo.getHostAddresses()` **without** `ACCESS_LOCAL_NETWORK`. The grant persists across reboots, can be re-checked with `checkPermissionForService()`, and previously approved services can be rediscovered without UI using `DiscoveryRequest.FLAG_USER_APPROVED_ONLY`. The grant can still be revoked by the user or dropped for storage reasons.
- **Finding (negative / decisive):** the official sample for `NsdManager.registerService()` (advertising) carries the inline note *"Note this always requires the ACCESS_LOCAL_NETWORK permission."* `discoverServices()` without a picker also documents `Requires ACCESS_LOCAL_NETWORK`. So on a targetSdk-37 build, **the side that advertises and listens needs the broad runtime permission**, regardless of the picker.
- **Architectural consequence:** the zero-broad-permission shape is to make **macOS the advertiser and listener** (Bonjour `NWListener`) and have **Android select the Mac through the NSD picker and connect outbound**, using one duplex TLS connection for both clipboard and mirroring. That matches the media direction (Android pushes H.264 to the Mac) and avoids the runtime permission entirely.
- **Open questions this does not settle:** whether the picker grant also covers *inbound* connections to the Android app (unverified); behavior when the Mac changes address or port (must re-resolve with `registerServiceInfoCallback`, never cache IPs); whether One UI 9 shows the picker as documented; UX cost of the picker on every new Mac.
- **Decision:** **CONDITIONAL** — direction inversion (Mac advertises, Android dials out via picker) is the primary design; `ACCESS_LOCAL_NETWORK` is the documented fallback if P02 shows the picker path cannot sustain the required session.
- **Prototype:** P02 (both paths, duplex, reconnect, revocation, address change).

---

### R-03 — Certificate Transparency is on by default for targetSdk 37, but not applied to custom trust anchors

- **Checked:** 2026-11-08
- **Target:** Android 17 / API 37, TLS clients
- **Sources (official):**
  - https://developer.android.com/privacy-and-security/security-config
  - https://developer.android.com/about/versions/17/behavior-changes-17
- **Finding (positive):** "Certificate transparency verification isn't performed on connections that use custom trust anchors." A locally pinned certificate installed as a custom CA/trust anchor therefore is not subject to public-CT-log enforcement. Android 17 also defines an implicit localhost configuration.
- **Finding (negative / watch-out):** CT is enabled by default for apps targeting API 37 (it was opt-in on API 36). Any future use of a public CA or a public hostname would fall under CT rules. ECH is also enabled by default for API 37 and is configured through `<domainEncryption>`; ECH only activates when both the networking library and the server support it, otherwise ECH GREASE is sent.
- **Implication for DroidBridge:** the pairing design must use an app-owned certificate (self-signed per device, pinned after user-confirmed pairing) declared as a custom trust anchor in `network_security_config.xml`. Do **not** rely on the system trust store, a public CA, or a hostname-based identity. Do not disable CT globally to "make TLS work"; that would be an unneeded weakening.
- **Confidence:** High for the documented statements; Medium for the exact behavior on One UI 9 until tested.
- **Decision:** **GO** with the pinned-custom-anchor design, subject to prototype P13.
- **Prototype:** P13.

---

### R-04 — MediaProjection consent is single-use and bound to a foreground service

- **Checked:** 2026-11-08 (re-verified from 2026-11-07 baseline)
- **Target:** Android 14+ (API 34+), applies to Android 17 / API 37
- **Sources (official):**
  - https://developer.android.com/media/grow/media-projection
  - https://developer.android.com/media/platform/av-capture
  - https://developer.android.com/reference/android/media/projection/MediaProjectionManager
  - https://developer.android.com/about/versions/14/changes/fgs-types-required
- **Finding (positive):** Capture is available with explicit user consent; a `VirtualDisplay` renders into an app-owned `Surface`, which can feed a `MediaCodec` encoder.
- **Finding (negative / constraint):** Since Android 14 the token is one-shot: passing a consent `Intent` to `getMediaProjection()` more than once, or calling `createVirtualDisplay()` more than once on the same `MediaProjection`, throws `SecurityException`. The app manifest must declare `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PROJECTION` and a service with `android:foregroundServiceType="mediaProjection"`; a missing type throws `MissingForegroundServiceTypeException`. The service must be started before the projection is used.
- **Limitations:** a fresh consent prompt is required for every session; the consent cannot be cached across restarts; process death kills the session.
- **Decision:** **GO**.
- **Prototype:** P01, repeated start/stop with fresh consent.

---

### R-05 — Projection stops for reasons outside the app's control; the app must handle `onStop()`

- **Checked:** 2026-11-08
- **Target:** Android 15 QPR1+ / Android 17
- **Sources (official):**
  - https://developer.android.com/media/grow/media-projection
  - https://developer.android.com/about/versions/15/behavior-changes-all
- **Finding:** the system stops projection when the user taps the status-bar screen-sharing chip, when the screen is locked, when another projection session starts, and when the app process is killed. Apps must register `MediaProjection.Callback`; without it `createVirtualDisplay()` throws `IllegalStateException`. A stopped projection cannot create a new virtual display.
- **Implication:** the mirroring UX must be "session visibly ends" rather than "reconnect silently", and the Mac viewer must show a clear ended state. The foreground-service notification and the system chip are the user's stop controls and must not be fought.
- **Decision:** **GO**, with a product requirement to surface all stop reasons.
- **Prototype:** P01, P08.

---

### R-06 — Selected-app sharing exists and changes the feature's shape

- **Checked:** 2026-11-08
- **Target:** Android 14+ (app screen sharing), Android 15+ (partial sharing in the system UI)
- **Sources (official):** https://developer.android.com/media/grow/media-projection , https://developer.android.com/about/versions/15/summary
- **Finding:** Android 14+ lets the user share a single app window instead of the whole display; it excludes the status bar, navigation bar and notifications. Apps can opt out for whole-display capture via `createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())`, but manufacturers may override an abusive opt-out. `onCapturedContentResize()` and `onCapturedContentVisibilityChanged()` let the app follow the captured region's size and visibility.
- **Limitation:** a whole-phone mirroring experience is exactly the experience Android is moving away from; OEM overrides exist and the user always has the final choice.
- **Decision:** **CONDITIONAL** — support both whole-display and selected-app capture, follow resize callbacks, never assume the user picked whole-display.
- **Prototype:** P01, P08.

---

### R-07 — Android 15+ screenshare protections redact notifications and password fields

- **Checked:** 2026-11-08
- **Target:** Android 15+ (all apps), Android 17
- **Sources (official):** https://developer.android.com/about/versions/15/behavior-changes-all
- **Finding:** during screen sharing, notification content is hidden unless the posting app supplies `setPublicVersion()`; sensitive content such as password input is hidden from remote viewers; activities from apps that posted an OTP-bearing notification are hidden when launched. Developers can disable these protections only through Developer Options (a testing escape hatch, not a product feature); the system screen recorder is exempt because recordings stay on-device.
- **Implication:** a mirroring product must state plainly that notifications, password fields and OTP screens may appear blank or redacted. This is a **feature of the OS**, not a bug, and must not be worked around.
- **Decision:** **GO** (accepted limitation), documented in product copy.
- **Prototype:** P10.

---

### R-08 — `FLAG_SECURE` remains the only supported way to exclude content

- **Checked:** 2026-11-08
- **Target:** all supported versions
- **Sources (official):** https://developer.android.com/about/versions/17/behavior-changes-17 , https://developer.android.com/reference/android/view/WindowManager.LayoutParams
- **Finding:** Android 17 deprecates `ContentCaptureManager.setContentCaptureEnabled()`; an app that wants to keep its window out of system capture must use `FLAG_SECURE`. Secure windows are excluded from screenshots and non-secure displays, so MediaProjection receives blank/unavailable pixels.
- **Rule for DroidBridge:** treat blank or missing regions as "protected", never attempt recovery, never cache frames from protected regions, and use `FLAG_SECURE` on DroidBridge's own sensitive windows (pairing code, diagnostics containing identifiers).
- **Decision:** **GO** (guarantee of non-capture).
- **Prototype:** P10.

---

### R-09 — Android clipboard reads are gated by focus or IME role; writes are not

- **Checked:** 2026-11-08
- **Target:** Android 10+ (background read restriction), Android 17
- **Sources (official):**
  - https://developer.android.com/reference/android/content/ClipboardManager
  - https://developer.android.com/develop/ui/views/touch-and-input/copy-paste
  - https://developer.android.com/privacy-and-security/risks/secure-clipboard-handling
- **Finding (positive):** `setPrimaryClip(ClipData)` has no special permission and works from the app's own process; the receiving direction (Mac → Android) is technically unblocked.
- **Finding (negative):** `getPrimaryClip()` and `getPrimaryClipDescription()` "return `null`" when the app is not the default IME and does not have input focus. Since Android 12 the system shows a toast when an app reads the clipboard. Android 13+ shows a copy preview and supports marking a clip sensitive so its content is not previewed.
- **Implication:** Android → Mac sync is a **foreground, user-initiated action** in the guaranteed baseline. No default-IME requirement (it would change the user's keyboard and privacy model) and no Accessibility fallback are permitted, so "always-on background read" is **NO-GO**.
- **Open questions:** whether the app regains clipboard read rights while the DroidBridge activity is visible behind the mirroring foreground service; whether the grant survives screen-off; auto-clear timing on One UI 9.
- **Decision:** **CONDITIONAL** — manual/foreground send only.
- **Prototype:** P04, P05.

---

### R-10 — macOS local network privacy gates both Bonjour and direct sockets

- **Checked:** 2026-11-08
- **Target:** macOS (Local Network privacy since macOS 15; documented in TN3179)
- **Sources (official):**
  - https://developer.apple.com/documentation/technotes/tn3179-understanding-local-network-privacy
  - https://developer.apple.com/documentation/bundleresources/information-property-list/nslocalnetworkusagedescription
  - https://developer.apple.com/documentation/bundleresources/information-property-list/nsbonjourservices
- **Finding:** the first local-network operation from an app triggers a system alert; the decision is recorded per user account and can be changed later in System Settings → Privacy & Security → Local Network. Registering, browsing and resolving Bonjour services all require local network access. `NSLocalNetworkUsageDescription` is required (direct unicast/multicast included) and `NSBonjourServices` must list the browsed/advertised service types. The state can be *undetermined* (operation denied until the user answers), so the app must handle denials and offer a path back.
- **Implication for the R-02 direction inversion:** the Mac is now the advertiser, so the Mac app must declare `NSBonjourServices` for its own service type and handle the local-network prompt before it can be discovered.
- **Refinement (2026-11-08, distribution pass):** TN3179's operation table distinguishes the two operations this design mixes. "Listening for and accepting incoming TCP connections" requires **no** local-network access; "All Bonjour operations require local network access" (register, browse, resolve). So the Mac's prompt is triggered by **advertising**, and the accepted Android connection does not depend on the privilege state. This also means a user who revokes the privilege breaks discovery while an already-established session may keep running — the app must re-check, not infer (R-28).
- **Decision:** **GO** with explicit denied-state UX.
- **Prototype:** P02, P03, P11.

---

### R-11 — macOS pasteboard access can prompt the user (macOS 15.4+), which changes the clipboard design

- **Checked:** 2026-11-08
- **Target:** macOS 15.4+ (`NSPasteboard.AccessBehavior`)
- **Sources (official):**
  - https://developer.apple.com/documentation/appkit/nspasteboard/accessbehavior-swift.enum
  - https://developer.apple.com/documentation/appkit/nspasteboard/accessbehavior-swift.enum/default
  - https://developer.apple.com/documentation/appkit/nspasteboard/accessbehavior-swift.enum/ask
  - https://developer.apple.com/documentation/appkit/nspasteboard/changecount
- **Finding (positive):** the general pasteboard's documented default behavior is *ask upon programmatic access*; access that is both user-originated and paste-related is always allowed and produces no notification. Apps appear in a per-app System Settings entry once they have triggered the alert, and the state can be `ask`, `alwaysAllow` or `alwaysDeny`. Apple also documents non-reading detection APIs so an app can examine *types* on the pasteboard without triggering the read alert.
- **Finding (negative / risk):** automatic Mac → Android clipboard sync reads the pasteboard **without** a paste-related user action, which is exactly the case the alert targets. If the user chooses deny, monitoring is dead until they change System Settings. `changeCount` is documented as incrementing on change; polling it is the standard cheap primitive, but it is **not documented** whether polling alone avoids the access alert.
- **Secondary evidence (clearly labelled, unverified):** community reports suggest `changeCount` polling does not itself trigger the alert, that a first real read does, and that behaviour may differ for unbundled helper processes. Do not treat as fact.
- **Implication:** clipboard sync must define three states — *not yet authorised*, *allowed*, *denied* — with an in-app explanation and a deep link to the Local Network/pasteboard settings, plus a manual "paste now" control that works even when background monitoring is denied.
- **Decision:** **CONDITIONAL** — prototype P06 decides whether automatic Mac → Android sync can ship by default.
- **Prototype:** P06.

---

### R-12 — macOS cannot present itself as a Bluetooth HID peripheral through public APIs

- **Checked:** 2026-11-08
- **Target:** macOS 10.9+ CoreBluetooth peripheral role; current macOS
- **Sources (official):**
  - https://developer.apple.com/documentation/corebluetooth/cbperipheralmanager
  - https://developer.apple.com/documentation/corebluetooth/cbperipheralmanager/startadvertising(_:)
  - https://developer.apple.com/documentation/corebluetooth
- **Secondary sources (labelled):** multiple independent reports that `CBPeripheralManager.add()` rejects the HID-over-GATT service `0x1812` with `CBError.uuidNotAllowed` (code 8, "The specified UUID is not allowed for this operation."), that macOS reserves the HID service for the system, and that `bluetoothd` owns the classic HID L2CAP PSMs 17/19.
- **Finding:** Apple documents the peripheral role generally (macOS can publish GATT services and advertise), but does **not** document or support publishing the HID service. The consistent secondary evidence is a platform-level refusal, not a configuration problem, so a custom GATT service cannot produce system-level keyboard/mouse input on Android.
- **Limitation:** even a successful HID keyboard would only type into a focused field — it would not inject arbitrary touch, and it would not set the Android clipboard.
- **Decision:** **NO-GO** for "Mac as a Bluetooth HID peripheral for Android" unless prototype P09 produces contrary on-device evidence (which is not expected).
- **Prototype:** P09 (expected result: `uuidNotAllowed`).

---

### R-13 — Android's own Bluetooth HID API is the reverse direction

- **Checked:** 2026-11-08 (re-verified from baseline)
- **Target:** Android 12+ (`BLUETOOTH_CONNECT` model), Android 17
- **Sources (official):** https://developer.android.com/reference/android/bluetooth/BluetoothHidDevice
- **Finding:** `BluetoothHidDevice` makes **Android** the HID peripheral for a host (for example, a phone acting as a keyboard for a PC). It does not let an external device drive Android. `BLUETOOTH_CONNECT` is required for the relevant operations and on modern target SDKs; registration is tied to the app being in the foreground and can be unregistered when it is not.
- **Implication:** this API is not an input path for DroidBridge. It is only relevant if the product ever wanted Android controlling the Mac.
- **Decision:** **NO-GO** for Mac → Android input via this API. Optional Bluetooth remains non-core.
- **Prototype:** P11 (documentation confirmation only; no input claim).

---

### R-14 — Knox RemoteInjection is not available to an ordinary consumer app

- **Checked:** 2026-11-08
- **Target:** Knox SDK API level 41 (Knox SDK dated 2026-07-20); Android 15/16+
- **Sources (official):**
  - https://docs.samsungknox.com/devref/knox-sdk/reference/com/samsung/android/knox/remotecontrol/RemoteInjection.html
  - https://docs.samsungknox.com/dev/knox-sdk/api-reference/restricted-api-methods/
- **Finding (negative):** `injectKeyEvent`, `injectPointerEvent` and `injectTrackballEvent` require `com.samsung.android.knox.permission.KNOX_REMOTE_CONTROL`, which is documented as **signature-level** protection. Since Knox API level 38, a caller that is neither Device Owner nor Profile Owner additionally needs the `AUTHORIZATION_REMOTE_CONTROL` scope granted through `ApplicationPolicy.setAuthorizedScopes()` — i.e. an MDM/EMM-issued authorisation. The DeX-specific injection methods (`injectKeyEventDex`, `injectPointerEventDex`) are deprecated and documented as **not available on Android 16 and higher**.
- **Implication:** eligibility requires Samsung/MDM signing and managed-device status. Shipping that would require the user's phone to be managed and DroidBridge to be a signed Knox partner app — incompatible with a consumer product and with the project's prohibition on privileged OEM interfaces.
- **Decision:** **NO-GO**.
- **Prototype:** none required; do not build on Knox.

---

### R-15 — Audio playback capture is bounded; call audio is impossible

- **Checked:** 2026-11-08
- **Target:** Android 10+ (`AudioPlaybackCaptureConfiguration`), Android 17
- **Sources (official):**
  - https://developer.android.com/media/platform/av-capture
  - https://developer.android.com/reference/android/media/AudioPlaybackCaptureConfiguration
- **Finding (positive):** capturing other apps' media playback is supported via `AudioRecord` + `AudioPlaybackCaptureConfiguration`, but only when the capturing app has `RECORD_AUDIO`, has an approved MediaProjection consent, is in the same user profile, and the *playing* app allows capture (`USAGE_MEDIA`/`USAGE_GAME`/`USAGE_UNKNOWN` and `ALLOW_CAPTURE_BY_ALL`, with `android:allowAudioPlaybackCapture` and target-SDK rules).
- **Finding (negative):** all other usages "CAN NOT be captured" — `USAGE_VOICE_COMMUNICATION` (cellular/VoIP call audio) is excluded by design. There is no public path to cellular call audio without privileged or system roles.
- **Decision:** **NO-GO** for call audio. Playback (media) audio is **CONDITIONAL** and out of MVP scope; it needs `RECORD_AUDIO`, which is not approved for the MVP.
- **Prototype:** none in MVP; P12 only if the owner approves an audio research track.

---

### R-16 — Android 17 adds background-audio hardening on top of capture rules

- **Checked:** 2026-11-08
- **Target:** Android 17 / API 37
- **Sources (official):**
  - https://developer.android.com/about/versions/17/behavior-changes-17
  - https://developer.android.com/about/versions/17/changes/bg-audio
- **Finding:** for apps targeting API 37, background audio interactions (playback, audio-focus requests, volume APIs) require a running foreground service that either has while-in-use capability or is paired with the exact-alarm permission and `USAGE_ALARM` streams.
- **Implication:** any future audio mirroring must run inside the mirroring foreground service (which already exists) rather than from a background worker; it also strengthens the case to keep audio out of the MVP.
- **Decision:** **CONDITIONAL** (future track only).

---

### R-17 — Call notifications and call control need roles/listeners the project forbids

- **Checked:** 2026-11-08
- **Target:** Android 17; Telecom framework
- **Sources (official):**
  - https://developer.android.com/reference/android/telecom/package-summary
  - https://developer.android.com/reference/android/telecom/TelecomManager
  - https://developer.android.com/reference/android/telecom/ConnectionService
  - https://developer.android.com/reference/android/app/role/RoleManager
- **Finding:** answering/rejecting calls and reading incoming-call details requires Telecom integration as the default dialer (`RoleManager.ROLE_DIALER` plus `TelecomManager`), a `ConnectionService` owned by the calling app, or a notification listener for call notifications. The first changes the user's dialer, the third is on the project's prohibited list, and the middle does not apply to calls originated by other apps.
- **Decision:** **NO-GO** for MVP. Remains a separately-approvable future product decision.
- **Note:** Android 17 extends SMS/OTP protections (standard SMS OTPs are withheld from non-recipient apps for three hours), confirming there is no "read the OTP from SMS" path either — and that path is prohibited anyway.

---

### R-18 — Transport: TLS/TCP framing first; WebRTC is an alternative, not a default

- **Checked:** 2026-11-08
- **Target:** Android 17 client; macOS Network.framework
- **Sources:**
  - Official: https://developer.apple.com/documentation/network , https://developer.android.com/privacy-and-security/security-config , https://developer.android.com/reference/javax/net/ssl/SSLSocket
  - Secondary (labelled): W3C WebRTC issue #3109 — on macOS 26+, any `RTCPeerConnection` use triggers the local-network prompt because of mDNS candidate obfuscation; IETF mDNS ICE candidate drafts describe the obfuscation.
- **Finding:** a plain mutually-authenticated TLS connection over TCP with length-prefixed messages satisfies every MVP requirement (clipboard + H.264 stream) without a media stack, without ICE/STUN/TURN, and without the mDNS/candidate machinery that interacts with macOS local-network privacy. WebRTC would add a large dependency and a second privacy permission surface before any measured benefit.
- **Limitation:** no built-in congestion control, jitter buffer or adaptive bitrate; these must be measured and, if needed, hand-rolled (simple pacing + keyframe-on-loss is usually enough for a LAN screen stream).
- **Decision:** **GO** for TLS/TCP as the MVP transport. WebRTC is retained as a measured alternative in P08 only if latency/quality targets fail.
- **Prototype:** P03, P08 (with the side-by-side measurement kept as acceptance evidence).

---

### R-19 — Android 17 masks password fields typed from physical keyboards

- **Checked:** 2026-11-08
- **Target:** Android 17 / API 37
- **Sources (official):** https://developer.android.com/about/versions/17/behavior-changes-17
- **Finding:** when a physical input device is attached, API-37 apps get `show_passwords_physical`, which hides all password characters by default (the system still reveals the last typed character for touch input via `show_passwords_touch`).
- **Implication:** this is another reason the Bluetooth-HID-keyboard idea cannot deliver a general remote-input experience, and it must be stated in the remote-input research conclusion.
- **Decision:** informational; supports R-12/R-13 NO-GO.

### R-20 — Keeping a LAN session alive without MediaProjection

- **Checked:** 2026-11-08
- **Target:** Android 14+ (foreground service types), Android 15/17 timeout and boot rules
- **Sources (official):**
  - https://developer.android.com/develop/background-work/services/fgs/service-types
  - https://developer.android.com/develop/background-work/services/fgs/timeout
  - https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
  - https://developer.android.com/about/versions/15/behavior-changes-15
  - https://developer.android.com/training/monitoring-device-state/doze-standby
- **Finding (positive):** the `connectedDevice` foreground service type is documented as *"Interactions with external devices that require a Bluetooth, NFC, IR, USB, or network connection"* — a paired Mac over Wi-Fi is squarely in scope. Its permission is `FOREGROUND_SERVICE_CONNECTED_DEVICE` (plus `FOREGROUND_SERVICE`). Its runtime prerequisite is satisfied by **declaring** at least one of `CHANGE_NETWORK_STATE`, `CHANGE_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE`, `NFC` or `TRANSMIT_IR` — all normal permissions with no prompt — *or* by holding a granted Bluetooth/UWB runtime permission, or a USB permission. Declaring `CHANGE_NETWORK_STATE` therefore satisfies the type with **zero runtime prompts**, with no additional capability being used.
- **Finding (positive):** no timeout is documented for `connectedDevice`. The 6-hours-per-24-hour limit applies only to `dataSync` and `mediaProcessing` (`Service.onTimeout`), and `connectedDevice` is **not** in the Android 15 list of types that `BOOT_COMPLETED` receivers may not launch (that list is `dataSync`, `camera`, `mediaPlayback`, `phoneCall`, `mediaProjection`, `microphone`). A `BOOT_COMPLETED` receiver is itself an exemption from the background-start restriction.
- **Finding (negative / constraint):** apps targeting Android 12+ cannot start a foreground service from the background without an exemption (user-visible transition, notification/widget/bubble interaction, exact alarm, boot, Companion Device Manager permissions, battery-optimisation off, `SYSTEM_ALERT_WINDOW` with a visible overlay on Android 15+, and a few others). So the persistent service must be started from the app UI or from boot — never from a background timer.
- **Finding (negative / constraint):** Doze suspends **network access** device-wide and ignores wake locks, resuming only during maintenance windows; App Standby defers background network for inactive apps, and a running foreground service keeps the app "active" for App Standby purposes but does **not** exempt it from Doze's device-level network suspension. A socket can therefore stall on an idle, stationary, unplugged phone and must be treated as reconnectable rather than permanent. `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is not an acceptable answer for a consumer app and is not pursued.
- **Alternatives considered:** `dataSync` — rejected (6 h/24 h cap, `BOOT_COMPLETED` restriction, and it misdescribes the work); `specialUse` — rejected (Play Console review of the declared subtype for a use case that `connectedDevice` already covers); Companion Device Manager — retained as a future option, since the official docs recommend it for continuous transfer to an external device and it provides its own background-start exemptions.
- **Implication:** "clipboard sync while mirroring is off" is real but **must be a visible, user-enabled background session** with a notification, and its liveness is best-effort across Doze. The app must reconnect on wake, on network change and on app resume, and must never claim the Mac is reachable when the socket is stale.
- **Decision:** **CONDITIONAL** — `connectedDevice` foreground service is the mechanism; correctness under Doze and across reboot is a prototype gate (P14).
- **Prototype:** P14.

---

### R-21 — Foreground-service notification visibility and Play declaration

- **Checked:** 2026-11-08
- **Target:** Android 13+ (`POST_NOTIFICATIONS`), Play Console FGS declaration (Android 14+)
- **Sources (official):**
  - https://developer.android.com/develop/ui/views/notifications/notification-permission
  - https://developer.android.com/about/versions/13/behavior-changes-all
  - https://developer.android.com/develop/background-work/services/fgs/service-types
- **Finding:** `POST_NOTIFICATIONS` is **not** required to launch a foreground service, but the app must still supply a notification. If the user denies the permission, the service keeps running and appears in the Foreground Services Task Manager instead of the notification drawer. On Android 13+ foreground-service notifications are dismissible by default, and the service continues after dismissal.
- **Implication:** the notification is the user's visibility and stop affordance, so the permission should be declared and requested in context when background sync is first enabled; the app's own UI must independently display session state, because the drawer notification is not guaranteed. Apps targeting Android 14+ must also declare each foreground service type in the Play Console app-content page.
- **Decision:** **GO** — declare `POST_NOTIFICATIONS`, request it in context, keep in-app session state authoritative.

---

### R-22 — Android identity: the Keystore generates the self-signed certificate for us

- **Checked:** 2026-11-08
- **Target:** Android 23+ (`KeyGenParameterSpec`), Android 24+ (`setEndpointIdentificationAlgorithm`), Android 17 at runtime
- **Sources (official):**
  - https://developer.android.com/reference/kotlin/android/security/keystore/KeyGenParameterSpec.Builder
  - https://developer.android.com/privacy-and-security/security-ssl
  - https://developer.android.com/reference/javax/net/ssl/SSLParameters
- **Finding (positive):** for an asymmetric key pair the Android Keystore **generates and stores a self-signed X.509 certificate** as part of key generation, because the `KeyStore` abstraction cannot hold a key pair without one. `KeyGenParameterSpec.Builder.setCertificateSubject()` (API 23+) plus the serial-number and validity setters control that certificate, so an app-owned identity costs no extra library and no OpenSSL.
- **Finding (negative / constraint):** the certificate is only validly signed when the key is authorised to sign — the class documentation notes that a key whose purposes exclude `PURPOSE_SIGN` gets a certificate with an invalid (placeholder) signature that will not verify. The identity key must therefore include `PURPOSE_SIGN` (ECDSA P-256 via `ECGenParameterSpec("secp256r1")`).
- **Finding (negative / constraint):** *"SSLSocket does not perform hostname verification"* — Android's own security guidance says it is the app's job, either via `HttpsURLConnection.getDefaultHostnameVerifier()` or by setting `SSLParameters.setEndpointIdentificationAlgorithm("HTTPS")` (API 24+) to have the handshake do it. DroidBridge does neither, because the peer is an IP literal with no meaningful DNS name: identity comes from the pinned certificate (R-24).
- **Implementation shape:** `KeyPairGenerator` with `AndroidKeyStore` → `KeyStore.getCertificate(alias)` for the public certificate → `SSLContext` initialised with a `KeyManager` over that alias; for the accepting side `SSLSocket.setNeedClientAuth(true)` to require the peer's certificate.
- **Limitation:** the private key is non-exportable (by design), so the identity cannot be copied to another device — re-pairing is required after a device change.
- **Decision:** **GO**.
- **Prototype:** P13.

---

### R-23 — macOS identity: no public certificate-creation API; use Apple's swift-certificates

- **Checked:** 2026-11-08
- **Target:** macOS 10.12+ (`SecIdentityCreate`), macOS 10.15+ (swift-certificates)
- **Sources (official):**
  - https://developer.apple.com/documentation/security/secidentitycreate(_:_:_:)
  - https://developer.apple.com/documentation/security/secidentitycreatewithcertificate(_:_:_:)
  - https://developer.apple.com/documentation/security/creating-an-identity
  - https://swiftpackageindex.com/apple/swift-certificates/1.21.0/documentation/x509/creating-certificates
  - https://swiftpackageindex.com/apple/swift-certificates/1.21.0/documentation/x509/certificate/init(version:serialnumber:publickey:notvalidbefore:notvalidafter:issuer:subject:extensions:issuerprivatekey:)
- **Finding (negative / decisive):** the Security framework gives no public API to **create** an X.509 certificate. It can generate keys (`SecKeyCreateRandomKey`), sign data, validate trust, and build an identity **from an existing certificate** (`SecIdentityCreate(allocator, certificate, privateKey)`, macOS 10.12+, which returns null if the key does not match the certificate; `SecIdentityCreateWithCertificate` searches a keychain). Certificate construction is not part of the public surface.
- **Finding (positive):** Apple's own open-source `swift-certificates` package (module `X509`, macOS 10.15+, depending on `swift-crypto` and `swift-asn1`) constructs a certificate directly: `Certificate(version:serialNumber:publicKey:notValidBefore:notValidAfter:issuer:subject:extensions:issuerPrivateKey:)`, and its documentation states this "can be used to construct a self-signed key by passing the private key for publicKey as the issuerPrivateKey argument". Version 1.21.x was building cleanly against Swift 6.1 on macOS as of 2026-10.
- **Implementation shape:** generate a P-256 key (swift-crypto or `SecKeyCreateRandomKey`), build a self-signed v3 certificate with `swift-certificates`, persist key + certificate in the app's Keychain, rebuild the identity with `SecIdentityCreate(cert, key)`, present it through `sec_protocol_options_set_local_identity(options, identity)` (macOS 10.14+).
- **Finding (negative / watch-out):** `sec_protocol_options_set_peer_authentication_required` — *"Clients default to true, whereas servers default to false"* — so the Mac listener must explicitly enable peer authentication, or it will accept a TLS handshake without requiring the Android client's certificate, silently making the pairing one-way.
- **Alternatives rejected:** TLS-PSK (`sec_protocol_options_add_pre_shared_key`) — Apple documents PSK as TLS 1.2 only and Android exposes no public PSK API, so it cannot be the cross-platform mechanism; hand-rolled ASN.1/X.509 DER generation — needless risk when Apple maintains an implementation; importing a PKCS#12 blob — needs a P12 producer anyway.
- **Consequence:** one Apple-maintained dependency set is accepted for the macOS app. This is a build-time dependency, not runtime telemetry or cloud use, so it does not conflict with ADR-003.
- **Decision:** **GO**.
- **Prototype:** P13.

---

### R-24 — Peer verification model: pin a key, not a hostname, and require mutual auth

- **Checked:** 2026-11-08
- **Target:** both platforms
- **Sources (official):** https://developer.android.com/privacy-and-security/security-ssl , https://developer.android.com/privacy-and-security/security-config , https://developer.apple.com/documentation/network/tls
- **Finding:** the peer address is an IP literal obtained from NSD, so hostname verification has nothing meaningful to check. Both platforms' default verification would also reject a self-signed certificate outright. The workable model is explicit pinning: after pairing, store the peer's public-key digest (SHA-256 of SubjectPublicKeyInfo) and require an exact match on every handshake, on both sides, with the pairing code confirming the identity in person the first time.
- **Design consequences:**
  - Pin the **SPKI**, not the whole certificate, so the certificate can be re-issued for the same key without a forced re-pairing; a *key* change is an identity change and must be surfaced as "device identity changed", never silently re-trusted.
  - Android: a pinning `X509TrustManager` (dynamic peer, so a static `<trust-anchors>` entry in `network_security_config.xml` is impossible — that file is fixed at build time).
  - macOS: `sec_protocol_options_set_verify_block` compares the peer certificate chain against the stored pin and fails closed.
  - No plaintext, no "continue anyway", no fallback to the system trust store.
- **CT nuance (medium confidence):** the documented Android 17 statement is that certificate-transparency verification is not performed for connections using **custom trust anchors** (R-03). A programmatic pinning `TrustManager` likewise never consults the platform CT verifier, but that is an inference from the trust path rather than a sentence in the documentation, so P13 must confirm the handshake succeeds on API 37 with CT at its default before the design is treated as settled.
- **Decision:** **GO** for the pinning model; the CT interaction is **CONDITIONAL** on P13.
- **Prototype:** P13.

---

### R-25 — Play's target-API floor is API 36, so API 37 is a choice; the local-network permission is itself now a declared permission

- **Checked:** 2026-11-08
- **Target:** Google Play submission rules as they stand on 2026-11-08; ADR-021 (`targetSdk = 37`)
- **Sources (official):**
  - https://support.google.com/googleplay/android-developer/answer/11926878
  - https://developer.android.com/google/play/requirements/target-sdk
  - https://developer.android.com/distribute/play-policies
  - https://support.google.com/googleplay/android-developer/answer/16558241
  - https://developer.android.com/guide/app-bundle
- **Finding (target API):** from **31 August 2026**, new apps and app updates must target Android 16 (**API 36**) or higher to be submitted; existing apps must target API 35+ to stay available to new users on devices running an OS above the app's target level. A one-off extension to 2026-11-01 existed and has passed, so the floor is in force today. The published table stops at API 36: **no date has been announced for API 37**, so by the annual pattern 37 would become the floor in 2027, but that is an expectation, not a published fact.
- **Consequence for ADR-021:** targeting 37 is *allowed* (it is above the minimum) but is **not required by Play today**. The cost of 37 is Android 17's enforced local-network model (R-01, R-02). That is still the right call — it avoids retrofitting a mandatory migration — but the register must not claim Play forced it.
- **Finding (new policy risk):** `ACCESS_LOCAL_NETWORK` is now a **restricted** permission. From **25 August 2027**, apps targeting API 37+ "must submit a Play Console declaration to use this permission", and it is "restricted to apps that need it for core functionality when device pickers are insufficient"; the core functionality must also be documented in the store description. The picker-first design in ADR-010 therefore avoids a **policy deliverable** as well as a runtime prompt. If P02 forces the runtime permission, the declaration becomes a release gate alongside the FGS declarations in R-26.
- **Finding (packaging):** new apps must be published as **Android App Bundles** (since August 2021), and **Play App Signing** is required for new apps — the developer holds an upload key and Google signs the generated APKs. Release signing work in Phase 9 must be planned around that split, not around a single self-managed APK key.
- **Related, for context only:** Android developer verification took effect 2026-09-30 for participating stores in Brazil, Indonesia, Singapore and Thailand; India is not listed in that entry yet, and Play-registered apps were verified automatically. It affects sideloading rather than a Play listing, so it changes nothing here.
- **Decision:** **CONDITIONAL** — target 37 stands (ADR-021); picker-first is preferred so that no permission declaration is needed; accepting the runtime permission would add a Play declaration, store-description justification and review risk.
- **Prototype:** P02 decides whether the declaration is needed at all (U16).

---

### R-26 — Each foreground-service type is a Play Console deliverable with its own demo video

- **Checked:** 2026-11-08
- **Target:** Android 14+ FGS types; Play Console App content declaration
- **Sources (official):**
  - https://support.google.com/googleplay/android-developer/answer/13392821
  - https://developer.android.com/develop/background-work/services/fgs/service-types
  - https://developer.android.com/about/versions/14/changes/fgs-types-required
  - https://support.google.com/googleplay/android-developer/answer/13315670
  - https://developer.android.com/distribute/play-policies
- **Finding:** for **each** declared type Play requires (1) a description of the app functionality, (2) the user impact if the task is deferred or interrupted, (3) **a link to a video demonstrating the feature**, and (4) a use case chosen from a preset list. The declaration lives on the App content page, and the manifest declaration must match it: Play enforces the FGS policy through the Device and Network Abuse rules, so a used-but-undeclared type and a declared-but-unused type are both policy risks.
- **Finding (`connectedDevice`):** the matching use case is **"Continuous Data Transfer to an External Device"**, and the video "must show the user pairing and connecting to an external device directly inside the app… The video must show the in-app setup and subsequent device interaction, **not system settings**."
- **Finding (`mediaProjection`):** the matching use case is **"Media or Content Projection and streaming or recording with MediaProjection API"**; the video must show the user initiating capture or streaming of the screen to an external display. That is exactly DroidBridge's mirroring flow.
- **Review risk created by ADR-010:** the Android app's peer choice happens in the **system NSD picker**, not an in-app device list. The picker is launched from an in-app flow and is not *Settings*, so the requirement is satisfied in spirit, but a reviewer comparing the video against "in-app setup" is a real risk. Mitigation: the demo video must show the app's own UI → picker → an in-app "connected" state → the clipboard/mirroring effect, and the declaration description must state that the peer is chosen through the Android 17 system service picker as the least-privilege alternative to `ACCESS_LOCAL_NETWORK`.
- **Finding (list churn):** Play's use-case list changes — geofencing stops being an approved FGS use case on 2027-01-27. The declaration is therefore a *maintained* artefact, not a one-off form.
- **Decision:** **GO** — both types map to documented use cases, so the declaration is release work rather than a feasibility blocker. The `connectedDevice` review risk is tracked as U15.
- **Prototype:** none; verified by the Phase 9 submission.

---

### R-27 — macOS distribution outside the App Store requires a paid membership, hardened runtime and notarisation

- **Checked:** 2026-11-08
- **Target:** macOS app, direct distribution (Developer ID)
- **Sources (official):**
  - https://developer.apple.com/documentation/security/notarizing-macos-software-before-distribution
  - https://developer.apple.com/documentation/xcode/creating-distribution-signed-code-for-the-mac
  - https://developer.apple.com/documentation/security/customizing-the-notarization-workflow
  - https://developer.apple.com/documentation/security/resolving-common-notarization-issues
  - https://developer.apple.com/documentation/security/hardened-runtime
  - https://help.apple.com/xcode/mac/current/en.lproj/dev033e997ca.html
  - https://support.apple.com/en-us/102445
- **Finding (requirement):** beginning with macOS 10.15, software built after 2019-06-01 and distributed with Developer ID **must be notarised** to run. Apple's notary service requires: a valid signature on every executable, a **Developer ID** certificate, the **Hardened Runtime** enabled, and a **secure timestamp**. It also rejects `com.apple.security.get-task-allow` set to true, requires entitlements as ASCII XML with no BOM or comments, and requires linking against the macOS 10.9+ SDK.
- **Finding (signing mechanics):** sign **inside out** — innermost code first. Apple explicitly says **do not pass `--deep` when signing**: it applies one set of options to every item (so an app's entitlements leak onto a nested tool) and it only signs code in locations it expects. Never sign with `sudo`; never attach entitlements to frameworks or libraries.
- **Finding (workflow):** `xcrun notarytool submit <zip|dmg|pkg> --keychain-profile <profile> --wait`, then `xcrun stapler staple`. Stapling lets Gatekeeper validate without contacting Apple, which matters for an offline first launch. `notarytool` does not accept a bare `.app`.
- **Finding (failure path):** an app that is unnotarised or from an unidentified developer is blocked with only "Done" and "Move to Trash" buttons. The supported override is **System Settings → Privacy & Security → Open Anyway**, offered for about an hour after the blocked launch. The long-standing Control-click → Open bypass exists only in the older (macOS 11) user guide; current Apple instructions describe the Settings route only. "Tell the user to right-click Open" is therefore not an acceptable distribution plan.
- **Finding (who can sign):** only the **Account Holder** of an Apple Developer Program (or Enterprise) membership can create Developer ID certificates. DroidBridge therefore depends on a **paid membership** and on the owner either holding that role or having the Account Holder issue the certificate, plus notary credentials (an app-specific password or an App Store Connect API key) for `notarytool`.
- **Finding (identity stability):** TN3179 notes that local-network privacy tracks a program by its **code signature**, and recommends signing macOS programs with an Apple-issued identity so that the privilege state tracks reliably. Ad-hoc ("Sign to Run Locally") builds are fine for development but can lose the recorded TCC decision when the signature changes — expect re-prompts, and do not read them as a product bug (P15).
- **Decision:** **GO** for direct distribution, with a hard external dependency recorded: no notarisation without the paid program. This is a release-plan prerequisite, not a blocker on development. Mac App Store distribution stays a deferred option — it requires the same sandbox (R-28) but adds review and does not need notarisation.
- **Prototype:** P15.

---

### R-28 — A sandboxed listening Mac app needs both network entitlements; only restricted entitlements need a profile

- **Checked:** 2026-11-08
- **Target:** macOS App Sandbox, Hardened Runtime, Keychain, Local Network privacy
- **Sources (official):**
  - https://developer.apple.com/documentation/bundleresources/entitlements/com.apple.security.network.server
  - https://developer.apple.com/documentation/bundleresources/entitlements/com.apple.security.network.client
  - https://developer.apple.com/documentation/xcode/configuring-the-macos-app-sandbox
  - https://developer.apple.com/documentation/xcode/creating-distribution-signed-code-for-the-mac
  - https://developer.apple.com/documentation/technotes/tn3179-understanding-local-network-privacy
  - https://developer.apple.com/documentation/technotes/tn3137-on-mac-keychains
  - https://developer.apple.com/documentation/security/ksecusedataprotectionkeychain
- **Finding (network):** `com.apple.security.network.server` permits "listening for incoming network connections"; `com.apple.security.network.client` permits outgoing ones. For **TCP** the entitlements restrict only the *initiation* of a connection, not the flow of data — so one accepted socket is full duplex for clipboard and video. For **UDP** they restrict initiation *and* data flow: "an app with only the server entitlement enabled can receive, but not send".
- **Consequence:** Bonjour/mDNS is UDP multicast, and the advertiser must **send** responses as well as receive queries. Under ADR-010 the Mac advertises, so it must claim `app-sandbox` **plus both** network entitlements. Declaring only `network.server` would look correct for the TCP listener and still break discovery.
- **Finding (profiles):** Apple documents which entitlements may be claimed without a provisioning profile: `get-task-allow`, `application-groups`, **entitlements that enable and configure the App Sandbox**, and **entitlements that configure the Hardened Runtime**. Everything else is restricted — for example `keychain-access-groups`, which needs an embedded **distribution provisioning profile**. So the sandbox/network set needs **no profile**; the keychain choice does.
- **Finding (keychain fork, affects ADR-024):** the **legacy file-based keychain** uses ACLs and needs no entitlement. The **data protection keychain** (`kSecUseDataProtectionKeychain`) uses access groups, and TN3137 states those groups "must be authorized by a provisioning profile" and that the program "needs an app-like bundle structure in which to embed that profile". Storing the identity therefore forks: legacy keychain (no profile) or data protection keychain (one embedded Developer ID profile, no ACL prompts when the signing identity changes).
- **Finding (local network TCC refinement of R-10):** TN3179's operation table says **"Listening for and accepting incoming TCP connections — no"** local-network access required, while **"All Bonjour operations require local network access"** (register, browse, resolve). Under ADR-010 the Mac's prompt is therefore triggered by **advertising**, not by accepting the Android connection. The multicast entitlement is iOS-only — "The multicast entitlement isn't required on macOS".
- **Finding (what is auto-allowed):** macOS automatically grants local network access to launchd daemons, root processes and command-line tools. DroidBridge does **not** exploit that: moving the listener into a daemon purely to avoid the prompt would remove the user's control over a privacy permission, which is the same class of shortcut as the ones `AGENTS.md` prohibits.
- **Decision:** **GO** — enable App Sandbox from the first macOS build with `app-sandbox`, `network.client` and `network.server`; store the identity in the data protection keychain with an embedded Developer ID profile (ADR-025), falling back to the legacy keychain only if P15 shows the profile is unworkable.
- **Prototype:** P15.

---

### R-29 — Encoding is surface-fed, profile-sensitive, and governed by a hidden quality floor

- **Checked:** 2026-11-08
- **Target:** Android 14+ (API 34+) through Android 17 / API 37; AVC via `MediaCodec`
- **Sources (official):**
  - https://developer.android.com/reference/android/media/MediaCodec
  - https://developer.android.com/reference/android/media/MediaFormat
  - https://developer.android.com/reference/android/media/MediaCodecInfo.CodecCapabilities
  - https://developer.android.com/reference/android/media/MediaCodecInfo.VideoCapabilities
  - https://developer.android.com/reference/android/media/projection/MediaProjection.Callback
  - https://developer.android.com/media/grow/media-projection
- **Finding (surface mode removes the colour-format problem from our code):** the documented pipeline is `configure(format, null, null, CONFIGURE_FLAG_ENCODE)` → `createInputSurface()` → hand that surface to `createVirtualDisplay()`. MediaCodec states that "when using an input Surface, there are no accessible input buffers, as buffers are automatically passed from the input surface to the codec". DroidBridge therefore never allocates pixel buffers and never converts YUV: the colour format is the opaque hardware format behind the surface. `COLOR_FormatSurface` is the value Google's own surface-encoding sample sets in `KEY_COLOR_FORMAT`, and the chosen codec must advertise it in `CodecCapabilities.colorFormats` — a query, not a guess.
- **Finding (rate control):** `KEY_BIT_RATE`, `KEY_FRAME_RATE` and `KEY_I_FRAME_INTERVAL` (seconds between key frames) are the primary knobs; `KEY_BITRATE_MODE` selects VBR/CBR/CQ where `EncoderCapabilities.isBitrateModeSupported()` says so.
- **Finding (undocumented-looking but real constraint):** from API 31 "Android's Video MediaCodecs enforce a minimum quality floor… applied when the codec is in Variable Bitrate (VBR) mode; it is not applied when the codec is in Constant Bitrate (CBR) mode." For text-heavy screen content this makes CBR the predictable choice: VBR can be pushed *above* the requested average by the floor, which would silently blow a bitrate budget measured during prototyping.
- **Finding (profile and level, both easy to get wrong):** setting `KEY_PROFILE` is recommended, and if it is omitted "the encoder will choose a profile… device specific and may not be deterministic (could be ad hoc or even experimental)", and "the encoder may reject the encoding session" if the profile does not suit the input. Conversely `KEY_LEVEL` "cannot be used to constrain the encoder's output to a maximum encoding level" — encoders may target a different level if the parameters demand it. So level is a hint, never a cap, and the honest way to choose a resolution is `VideoCapabilities.areSizeAndRateSupported()` against the actual encoder.
- **Finding (latency and B-frames):** `KEY_LATENCY` requests encoder latency in frames; `KEY_MAX_B_FRAMES` (API 29) defaults to 0, meaning no B-frames, which is what keeps the stream in decode order and makes the Mac's timestamp handling trivial (R-31). `KEY_REPEAT_PREVIOUS_FRAME_AFTER` (microseconds, surface mode only) re-submits the last frame when nothing new arrives — without it a still screen can stop producing frames, and the Mac viewer looks frozen while the session is healthy.
- **Finding (async-mode trap):** with `setCallback()` set before `configure()`, `flush()` requires a following `start()` to resume receiving input, "even if an input surface was created". A naive restart path that flushes without restarting simply stops producing frames.
- **Finding (rotation and resize are a re-encode, not a scale):** `MediaProjection.Callback.onCapturedContentResize(width, height)` (API 34) fires on rotation or when the captured region changes; the documented response is to `virtualDisplay.resize(...)` **and** resize the output surface. With a surface-fed encoder that means recreating the encoder/surface and therefore a new format plus a fresh keyframe on the wire — it cannot be handled by stretching the old stream.
- **Decision:** **GO**.
- **Prototype:** P01 (encoder acceptance at panel resolution), P07/P08 (bitrate and quality measurement).

---

### R-30 — MediaCodec emits Annex-B; CoreMedia needs AVCC, so one conversion is mandatory

- **Checked:** 2026-11-08
- **Target:** Android AVC encoder output → CoreMedia on macOS 10.9+
- **Sources (official):**
  - https://developer.android.com/reference/android/media/MediaCodec
  - https://developer.apple.com/documentation/coremedia/cmvideoformatdescriptioncreatefromh264parametersets(allocator:parametersetcount:parametersetpointers:parametersetsizes:nalunitheaderlength:formatdescriptionout:)
  - https://developer.apple.com/videos/play/wwdc2014/513/
- **Finding (Android side):** Android documents the H.264 codec-specific data as `csd-0` = SPS and `csd-1` = PPS, and states that "each parameter set and the codec-specific-data sections… must start with a start code of `\x00\x00\x00\x01`". Encoders "create and return the codec specific data before any valid output buffer in output buffers marked with the `BUFFER_FLAG_CODEC_CONFIG`" flag, and for H.264 the buffers "can also concatenate all codec-specific data and submit it as a single codec-config buffer". So the encoder's native output is **Annex-B**: start-code-prefixed NAL units.
- **Finding (Apple side, the mismatch):** `CMVideoFormatDescriptionCreateFromH264ParameterSets` takes `parameterSetCount` that "must be at least 2", pointers to parameter sets that "can come from raw NAL units and must have any emulation prevention bytes needed", accepts only NAL types 7 (SPS), 8 (PPS) and 13 (SPS extension), and requires at least one SPS and one PPS. `nalUnitHeaderLength` is "size, in bytes, of the NALUnitLength field in an AVC video sample", and must be 1, 2 or 4. In other words the parameter sets are **bare NALs with no start code and no length prefix**, while every subsequent sample must be **length-prefixed** with the declared field size.
- **Consequence:** the formats are incompatible by construction, so exactly one conversion step is required. It belongs on the **producer** (Android), so the wire format has a single definition and a framing error is caught at its source:
  - At stream start (and after any format change), send SPS and PPS as raw NALs in a control message (`mirror.format`), not in-band.
  - For every access unit, emit each NAL as a 4-byte big-endian length followed by the NAL bytes, replacing the Annex-B start code. Four bytes is the conventional choice and is a value Apple accepts.
  - Leave emulation-prevention bytes (`0x00 0x00 0x03`) exactly as the encoder produced them. The conversion replaces a start code with a length; it is not a byte-level rewrite.
  - Split on start codes (`00 00 01` / `00 00 00 01`), never on a scan for zero bytes inside slice data.
- **Finding (timestamps):** the only timestamp available is `MediaCodec.BufferInfo.presentationTimeUs` (microseconds). CoreMedia wants `CMTime`; a `timescale` of 1,000,000 carries microseconds exactly. `CMSampleBufferCreate` documents that for in-order samples a single timing entry with `decodeTimeStamp = kCMTimeInvalid` is correct, and that "behavior is undefined if samples… have the same `presentationTimeStamp`" — so PTS must be strictly increasing, and a reconnect must rebase timestamps rather than resume a stale producer timeline (R-31).
- **Alternatives rejected:** sending Annex-B and building the format description on the Mac (the same work happens later, away from the code that produced the framing, and every framing bug becomes a Mac-side mystery); using a container (`MediaMuxer`/MP4) as the wire format (adds muxing, buffering and a file-oriented format to a live stream).
- **Decision:** **GO** — AVCC with a 4-byte length prefix on the media channel, SPS/PPS in a control message.
- **Prototype:** P07 (byte-level conversion test with a known stream), P08 (end-to-end).

---

### R-31 — The Mac renders with `AVSampleBufferDisplayLayer`, whose API is mid-transition

- **Checked:** 2026-11-08
- **Target:** macOS 10.8+ (layer), macOS 14+ (`sampleBufferRenderer`), macOS 27+ (`Receiver`)
- **Sources (official):**
  - https://developer.apple.com/documentation/avfoundation/avsamplebufferdisplaylayer
  - https://developer.apple.com/documentation/avfoundation/avsamplebuffervideorenderer
  - https://developer.apple.com/documentation/avfoundation/avsamplebuffervideorenderer/receiver
  - https://developer.apple.com/documentation/avfoundation/avsamplebufferdisplaylayer/requiresflushtoresumedecoding
  - https://developer.apple.com/documentation/coremedia/cmsamplebuffercreate(allocator:databuffer:dataready:makedatareadycallback:refcon:formatdescription:samplecount:sampletimingentrycount:sampletimingarray:samplesizeentrycount:samplesizearray:samplebufferout:)
- **Finding (no decoder session needed):** `AVSampleBufferDisplayLayer` "displays compressed or uncompressed video frames", so it performs H.264 decoding itself. View-only mirroring needs no `VTDecompressionSession`, no `CVPixelBuffer` handling and no colour-space work on the Mac: format description → sample buffer → enqueue.
- **Finding (the API is being replaced, and the replacement is not shipping):** in the current SDK the layer's `enqueue(_:)`, `isReadyForMoreMediaData`, `requestMediaDataWhenReady(on:using:)`, `requiresFlushToResumeDecoding`, `status`, `error` and `flush()` — and the whole `AVQueuedSampleBufferRendering` protocol — are marked deprecated, and the replacement is `AVSampleBufferVideoRenderer.Receiver`, documented as **macOS 27.0+**. On macOS 26 and earlier the deprecated members are the only available path. The rule for this project: use the available API, isolate it in one small adapter, and do **not** raise the deployment target to silence a deprecation warning. Which warnings actually appear must be recorded on the owner's Xcode (U20).
- **Finding (backpressure):** `isReadyForMoreMediaData` reports queue occupancy. Apple notes it "is safe to call `enqueue(_:)` when `isReadyForMoreMediaData` is false, but enqueuing more sample buffers than are required for timely rendering… is highly discouraged". So the receiver gates on readiness and drops or coalesces frames instead of unbounded queueing — relevant when the Mac is resizing a window or busy.
- **Finding (documented recovery path):** when the decoder may not run (backgrounding, GPU reset), `status` becomes `.failed` and `requiresFlushToResumeDecoding` becomes `true`; recovery requires calling `flush()` to return the status to `.unknown` before enqueuing again. Because the decoder has discarded state, the Mac must **request a keyframe** from Android rather than resuming with P-frames — this is a protocol requirement, not an implementation detail.
- **Finding (useful security lever):** the layer exposes `preventsCapture`, which keeps the mirror window out of screen capture. Setting it true means the mirrored phone screen cannot leak into a screen recording made on the Mac.
- **Finding (failure signal):** decode failures surface as the `AVSampleBufferDisplayLayerFailedToDecode` notification with an error payload (and `didFailToDecodeNotification` on the renderer). These must be logged without frame content and mapped to a viewer state.
- **Finding (timing):** one encoded frame per `CMSampleBuffer`, one timing entry, a duration of `1/fps` (or invalid), `decodeTimeStamp = .invalid`, and strictly increasing PTS in a 1/1,000,000 timescale taken from Android's `presentationTimeUs`. Duplicate or non-monotonic PTS is documented as undefined behaviour, so on reconnect the Mac rebases to its own clock.
- **Decision:** **GO**, **CONDITIONAL** on the deprecation churn: one adapter over the layer, a keyframe-on-recovery rule, and P07 asserting the behaviour on the owner's actual Xcode/macOS.
- **Prototype:** P07.

---

## 3. Uncertain areas that require prototypes

Nothing below has been tested. Every one is a gate before the corresponding build phase.

| # | Uncertainty | Why it matters | Prototype |
|---|---|---|---|
| U1 | Does the NSD picker grant support a full-duplex session where the **Mac** is the listener, across reboot, Wi-Fi change and address change? | Decides whether the app can ship with zero broad LAN permission | P02 |
| U2 | Does `registerService()` really fail without `ACCESS_LOCAL_NETWORK` on One UI 9, and how does the prompt read? | Confirms R-02 on the actual firmware | P02 |
| U3 | Does pinned custom-anchor TLS work on API 37 with CT on by default? | Core security design | P13 |
| U4 | Does the macOS pasteboard access alert fire on `changeCount` polling, on first `string(forType:)`, and per launch? | Decides whether automatic Mac → Android sync can be the default | P06 |
| U5 | On One UI 9, when exactly does `getPrimaryClip()` return non-null (visible activity, focused field, foreground service, screen off)? | Defines the honest Android → Mac workflow | P04 |
| U6 | Does `setPrimaryClip()` from a background/foreground-service context display the system clipboard UI, and does auto-clear break sync? | Defines the Mac → Android workflow | P05 |
| U7 | Is the HID service really refused on macOS (`uuidNotAllowed`)? Any supported alternative? | Final wording of the remote-input NO-GO | P09 |
| U8 | How do Android 15+ screenshare protections appear in a mirrored stream (notifications, password fields, OTP activities)? | Product copy and test expectations | P10 |
| U9 | MediaProjection + rotation/resize behavior on the S24 Ultra (foldable-class display metrics, high refresh) | Stream quality and viewer layout | P01/P08 |
| U10 | Does whole-display capture survive a 30-minute session with the screen off/locked? | Real-world usability | P08 |
| U11 | Does a `connectedDevice` foreground service actually keep a TLS socket usable through screen-off, Doze entry and a full night? | Decides whether "clipboard while mirroring is off" is a real feature or a foreground-only one | P14 |
| U12 | What keepalive interval and reconnect backoff keep the session alive without measurable battery cost? | Battery/thermal acceptance | P14 |
| U13 | Does a programmatic pinning `TrustManager` handshake cleanly on API 37 with CT/ECH at their defaults? | Confirms R-24's medium-confidence CT inference | P13 |
| U14 | Does a sandboxed, Developer ID-signed and notarised Mac build advertise Bonjour, accept the Android connection and read its keychain identity exactly like an ad-hoc debug build? | The sandbox is enabled from the first build; a violation discovered at release would force a redesign | P15 |
| U15 | Will a `connectedDevice` declaration pass review when the peer is chosen through the Android 17 system NSD picker rather than an in-app device list? | Decides whether the picker-first design costs a policy round-trip at submission | Play submission (Phase 9) |
| U16 | Does the picker-first path require declaring `ACCESS_LOCAL_NETWORK` in the manifest at all, given Play ties its declaration to *using* the permission? | Confirms that the chosen design avoids the 2027 declaration | P02 |
| U17 | After a Developer ID certificate renewal (or a team change), does the app still read its data-protection-keychain identity, or must the identity be regenerated and the pair re-created? | A keychain that fails closed after re-signing would silently force re-pairing for every user | P15 |
| U18 | Which encode size does the S24 Ultra's hardware encoder actually accept at 30 fps for screen content — 3120×1440, 2560×1440 or 1920×1080 — and which bitrate keeps text legible? | Decides the default quality profile and whether the panel resolution is usable at all | P01, P07 |
| U19 | Does the encoder keep one SPS/PPS pair for a whole session, or emit new parameter sets after rotation/resize, forcing a mid-stream format-description change? | Determines whether `mirror.format` is a stream-start message or a recurring one | P08 |
| U20 | Which sample-buffer display members compile without deprecation warnings on the owner's Xcode, is `Receiver` (macOS 27+) available, and what end-to-end latency does the layer add? | The API path is being replaced; building on the wrong one either fails to compile or bakes in a soon-dead call | P07 |

Detailed instructions for each prototype are in `Docs/PROTOTYPE_PLAN.md`.

## 4. What changed since the 2026-11-07 baseline

- Added: Android 17 CT-by-default and ECH behavior, and the fact that CT does not apply to custom trust anchors (R-03).
- Added: the advertising-vs-picker permission asymmetry in `NsdManager`, which changes the recommended connection direction (R-02).
- Added: macOS 15.4+ pasteboard access prompts, a risk the baseline did not record (R-11).
- Added: macOS HID-peripheral refusal evidence and the classic-HID ownership detail (R-12).
- Added: Knox authorization-scope requirement and DeX API removal on Android 16+ (R-14).
- Added: audio playback capture boundary conditions and call-audio impossibility, plus Android 17 background-audio hardening (R-15, R-16).
- Added: Android 15+ screenshare protections (notifications/passwords/OTP activities) (R-07) and physical-keyboard password masking (R-19).
- Added: the background-session mechanism — `connectedDevice` foreground service, its zero-prompt prerequisite, the absence of a timeout and its boot-start legality, plus Doze's device-wide network suspension (R-20).
- Added: foreground-service notification visibility rules and the Play Console FGS declaration (R-21).
- Added: concrete identity/TLS implementation — Keystore-generated self-signed certificate on Android (R-22), `swift-certificates` on macOS because the Security framework cannot create certificates (R-23), and the SPKI-pinning model including the server-side peer-authentication default (R-24).
- Added: Play's target-API floor (API 36 since 2026-08-31, so API 37 is a choice) and the new restricted-permission status of `ACCESS_LOCAL_NETWORK` with its 2027-08-25 declaration date (R-25).
- Added: the four-part Play Console FGS declaration, including the mandatory demo video, and the use cases that `connectedDevice` and `mediaProjection` map to — plus the review risk the system NSD picker creates for the `connectedDevice` video (R-26).
- Added: the macOS release chain — Developer ID, hardened runtime, secure timestamp, inside-out signing, `notarytool`/`stapler`, the `get-task-allow` rejection, and the fact that only the Account Holder can issue a Developer ID certificate (R-27).
- Added: the App Sandbox entitlement set for a listening Mac app, the unrestricted-versus-restricted entitlement rule, the keychain fork between the legacy and data-protection keychains, and TN3179's finding that accepting inbound TCP does not itself require local network access (R-28, refining R-10).
- Added: encoder configuration constraints — surface input, the API-31 minimum quality floor that applies in VBR but not CBR, profile rejection, `KEY_LEVEL` being a hint rather than a cap, B-frames defaulting to zero, and the async-mode `flush()`/`start()` trap (R-29).
- Added: the Annex-B→AVCC mismatch between MediaCodec's codec-specific data and `CMVideoFormatDescriptionCreateFromH264ParameterSets`, and the exact conversion rule that follows from it (R-30).
- Added: the macOS display path including the documented deprecation of the layer's enqueue API in favour of a macOS 27 replacement, the flush-to-recover rule and the keyframe consequence, and `preventsCapture` (R-31).
- Confirmed unchanged: MediaProjection consent/FGS rules (R-04, R-05), `FLAG_SECURE` (R-08), clipboard focus gate (R-09), macOS local-network privacy (R-10).

## 5. Negative findings worth repeating

- There is **no** supported way for a normal consumer app to inject touch into other apps, so "remote control" must not be advertised.
- There is **no** way to make Android clipboard reads work in the background without either input focus or the default-IME role, and the default-IME role is out of scope.
- There is **no** way to capture cellular call audio with public APIs.
- macOS pasteboard access is no longer guaranteed to be silent; assume a user-visible prompt.
- Local network permission on Android 17 is not avoidable for a device that advertises a service.
- macOS cannot distribute an unnotarised app to a normal user on a current system: the only override is System Settings → Privacy & Security → Open Anyway, and the Control-click bypass is gone. A paid Apple Developer Program membership is a hard prerequisite for releasing the Mac app.
- Only the Developer Program Account Holder can create a Developer ID certificate, so the release path depends on that person, not just on the developer writing the code.
- Play accepts an FGS type only with a demo video and a declaration; the picker-based pairing flow is a documented review risk for the `connectedDevice` type.

## 6. Source index

All URLs below were read on 2026-11-08 unless marked otherwise.

**Android**

- Local network permission — https://developer.android.com/privacy-and-security/local-network-permission
- Behavior changes, apps targeting Android 17 — https://developer.android.com/about/versions/17/behavior-changes-17
- Behavior changes, all apps on Android 17 / Android 15 — https://developer.android.com/about/versions/15/behavior-changes-all
- Android 17 announcement — https://developer.android.com/blog/posts/android-17-is-here
- Network security configuration (CT, ECH, custom anchors) — https://developer.android.com/privacy-and-security/security-config
- `NsdManager` — https://developer.android.com/reference/kotlin/android/net/nsd/NsdManager
- `DiscoveryRequest` — https://developer.android.com/reference/android/net/nsd/DiscoveryRequest
- Media projection guide — https://developer.android.com/media/grow/media-projection
- Capture video and audio playback — https://developer.android.com/media/platform/av-capture
- `MediaProjectionManager` — https://developer.android.com/reference/android/media/projection/MediaProjectionManager
- Foreground service types — https://developer.android.com/develop/background-work/services/fgs/service-types
- Foreground service timeouts — https://developer.android.com/develop/background-work/services/fgs/timeout
- Restrictions on starting an FGS from the background — https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- Android 15 foreground service and `BOOT_COMPLETED` changes — https://developer.android.com/about/versions/15/behavior-changes-15
- Doze and App Standby — https://developer.android.com/training/monitoring-device-state/doze-standby
- Notification runtime permission — https://developer.android.com/develop/ui/views/notifications/notification-permission
- Foreground service types (Android 14 requirements) — https://developer.android.com/about/versions/14/changes/fgs-types-required
- `ClipboardManager` — https://developer.android.com/reference/android/content/ClipboardManager
- Copy and paste guide — https://developer.android.com/develop/ui/views/touch-and-input/copy-paste
- Secure clipboard handling — https://developer.android.com/privacy-and-security/risks/secure-clipboard-handling
- `AudioPlaybackCaptureConfiguration` — https://developer.android.com/reference/android/media/AudioPlaybackCaptureConfiguration
- `KeyGenParameterSpec.Builder` (certificate fields) — https://developer.android.com/reference/kotlin/android/security/keystore/KeyGenParameterSpec.Builder
- Security with network protocols (custom `TrustManager`, `SSLSocket` hostname caution) — https://developer.android.com/privacy-and-security/security-ssl
- `SSLParameters.setEndpointIdentificationAlgorithm` — https://developer.android.com/reference/javax/net/ssl/SSLParameters
- `BluetoothHidDevice` — https://developer.android.com/reference/android/bluetooth/BluetoothHidDevice
- Telecom framework (`TelecomManager`, `ConnectionService`) — https://developer.android.com/reference/android/telecom/package-summary
- `RoleManager` — https://developer.android.com/reference/android/app/role/RoleManager
- `WindowManager.LayoutParams` (`FLAG_SECURE`) — https://developer.android.com/reference/android/view/WindowManager.LayoutParams
- `MediaProjection.Callback` (`onCapturedContentResize`, `onCapturedContentVisibilityChanged`) — https://developer.android.com/reference/android/media/projection/MediaProjection.Callback
- `MediaCodecInfo.VideoCapabilities` — https://developer.android.com/reference/android/media/MediaCodecInfo.VideoCapabilities
- Play target API level requirements (Play Console Help) — https://support.google.com/googleplay/android-developer/answer/11926878
- Meet Google Play's target API level requirement — https://developer.android.com/google/play/requirements/target-sdk
- Google Play policy timeline — https://developer.android.com/distribute/play-policies
- Permissions and APIs that access sensitive information (Local Network Permission) — https://support.google.com/googleplay/android-developer/answer/16558241
- Understanding foreground service and full-screen intent requirements (Play Console declaration) — https://support.google.com/googleplay/android-developer/answer/13392821
- Permissions for foreground services (Device and Network Abuse policy) — https://support.google.com/googleplay/android-developer/answer/13315670
- About Android App Bundles — https://developer.android.com/guide/app-bundle

**Apple**

- TN3179 Understanding local network privacy — https://developer.apple.com/documentation/technotes/tn3179-understanding-local-network-privacy
- `NSLocalNetworkUsageDescription` — https://developer.apple.com/documentation/bundleresources/information-property-list/nslocalnetworkusagedescription
- `NSBonjourServices` — https://developer.apple.com/documentation/bundleresources/information-property-list/nsbonjourservices
- `NSPasteboard.changeCount` — https://developer.apple.com/documentation/appkit/nspasteboard/changecount
- `NSPasteboard.AccessBehavior` — https://developer.apple.com/documentation/appkit/nspasteboard/accessbehavior-swift.enum
- `CBPeripheralManager` — https://developer.apple.com/documentation/corebluetooth/cbperipheralmanager
- `CBPeripheralManager.startAdvertising(_:)` — https://developer.apple.com/documentation/corebluetooth/cbperipheralmanager/startadvertising(_:)
- Network framework — https://developer.apple.com/documentation/network
- Network framework TLS options — https://developer.apple.com/documentation/network/tls
- `SecIdentityCreate(_:_:_:)` — https://developer.apple.com/documentation/security/secidentitycreate(_:_:_:)
- `SecIdentityCreateWithCertificate(_:_:_:)` — https://developer.apple.com/documentation/security/secidentitycreatewithcertificate(_:_:_:)
- Creating an Identity — https://developer.apple.com/documentation/security/creating-an-identity
- `sec_protocol_options_set_local_identity(_:_:)` — https://developer.apple.com/documentation/security/sec_protocol_options_set_local_identity(_:_:)
- swift-certificates, Creating Certificates (Apple open source) — https://swiftpackageindex.com/apple/swift-certificates/1.21.0/documentation/x509/creating-certificates
- Notarizing macOS software before distribution — https://developer.apple.com/documentation/security/notarizing-macos-software-before-distribution
- Creating distribution-signed code for macOS — https://developer.apple.com/documentation/xcode/creating-distribution-signed-code-for-the-mac
- Customizing the notarization workflow (`notarytool`, `stapler`) — https://developer.apple.com/documentation/security/customizing-the-notarization-workflow
- Resolving common notarization issues (`get-task-allow`, entitlements format) — https://developer.apple.com/documentation/security/resolving-common-notarization-issues
- Hardened Runtime — https://developer.apple.com/documentation/security/hardened-runtime
- `com.apple.security.network.server` — https://developer.apple.com/documentation/bundleresources/entitlements/com.apple.security.network.server
- `com.apple.security.network.client` — https://developer.apple.com/documentation/bundleresources/entitlements/com.apple.security.network.client
- Configuring the macOS App Sandbox — https://developer.apple.com/documentation/xcode/configuring-the-macos-app-sandbox
- TN3137: On Mac keychain APIs and implementations — https://developer.apple.com/documentation/technotes/tn3137-on-mac-keychains
- `kSecUseDataProtectionKeychain` — https://developer.apple.com/documentation/security/ksecusedataprotectionkeychain
- Distribute outside the Mac App Store — https://help.apple.com/xcode/mac/current/en.lproj/dev033e997ca.html
- Safely open apps on your Mac (Gatekeeper "Open Anyway") — https://support.apple.com/en-us/102445
- `AVSampleBufferDisplayLayer` — https://developer.apple.com/documentation/avfoundation/avsamplebufferdisplaylayer
- `AVSampleBufferVideoRenderer` — https://developer.apple.com/documentation/avfoundation/avsamplebuffervideorenderer
- `AVSampleBufferVideoRenderer.Receiver` (macOS 27+) — https://developer.apple.com/documentation/avfoundation/avsamplebuffervideorenderer/receiver
- `CMSampleBufferCreate` (timing and sample-size rules) — https://developer.apple.com/documentation/coremedia/cmsamplebuffercreate(allocator:databuffer:dataready:makedatareadycallback:refcon:formatdescription:samplecount:sampletimingentrycount:sampletimingarray:samplesizeentrycount:samplesizearray:samplebufferout:)
- `CMSampleTimingInfo` — https://developer.apple.com/documentation/coremedia/cmsampletiminginfo
- WWDC14 session 513, Direct Access to Video Encoding and Decoding (Annex-B → AVCC conversion) — https://developer.apple.com/videos/play/wwdc2014/513/

**Samsung**

- Knox `RemoteInjection` — https://docs.samsungknox.com/devref/knox-sdk/reference/com/samsung/android/knox/remotecontrol/RemoteInjection.html
- Knox restricted API methods — https://docs.samsungknox.com/dev/knox-sdk/api-reference/restricted-api-methods/
- Knox remote support overview — https://docs.samsungknox.com/dev/knox-sdk/remote-support-overview.htm

**Secondary (labelled, unverified — never used as sole evidence)**

- W3C WebRTC issue #3109, mDNS ICE triggers the macOS local-network prompt — https://github.com/w3c/webrtc-pc/issues/3109
- IETF mDNS ICE candidate drafts — https://datatracker.ietf.org/doc/html/draft-ietf-rtcweb-mdns-ice-candidates-04
- Community reports of macOS `CBError.uuidNotAllowed` for service `0x1812`.
