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
| Mac → Android text clipboard | **GO** with limits | `setPrimaryClip` is unrestricted; Android 15+ system clipboard UI is user-visible and cannot be suppressed |
| Android → Mac text clipboard | **CONDITIONAL** | `getPrimaryClip()` returns `null` unless the app has input focus or is the default IME — no IME/input-method requirement is allowed |
| Automatic two-way background clipboard | **NO-GO** (as a default) | Android focus restriction plus macOS pasteboard access prompt; manual/foreground baseline only |
| macOS pasteboard monitoring | **CONDITIONAL** | macOS 15.4+ `NSPasteboard.accessBehavior` can prompt or deny programmatic reads (§2 R-13) |
| Local screen streaming to a native Mac window | **GO** | MediaProjection → MediaCodec → local transport → VideoToolbox/AVSampleBufferDisplayLayer |
| Protected content stays protected (`FLAG_SECURE`, DRM) | **GO** (as a guarantee of *non*-capture) | Capture returns blank/unavailable; Android 15+ screenshare protections also redact notifications and password fields (§2 R-08) |
| Arbitrary remote touch/keyboard into other Android apps | **NO-GO** under current constraints | No public injection API; Knox requires signature permission plus device-owner/profile-owner or authorized scope; Bluetooth HID API direction is reversed (§2 R-14..R-16) |
| Mac acting as a Bluetooth HID peripheral for Android | **NO-GO** (pending contrary prototype evidence) | CoreBluetooth refuses to publish the HID-over-GATT service on macOS; classic HID transport is owned by `bluetoothd` (§2 R-14) |
| Samsung Knox remote injection | **NO-GO** for a consumer app | `KNOX_REMOTE_CONTROL` is `signature`-protected; since Knox API 38 non-DO/PO callers need an authorized scope; DeX injection APIs are absent on Android 16+ |
| Audio playback capture (media) | **CONDITIONAL** | Requires `RECORD_AUDIO` + MediaProjection consent + same user profile + app opt-in via capture policy |
| Call audio / cellular call capture | **NO-GO** | Playback capture cannot capture `USAGE_VOICE_COMMUNICATION`; Telecom roles are out of scope |
| Call notifications / answer-reject | **NO-GO** for MVP | Requires Telecom integration/role or a notification listener, both outside the approved permission set |
| Bluetooth as optional supplement | **CONDITIONAL**, non-core | Must never gate Wi-Fi features; HID direction problem unresolved |

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
  - https://developer.android.com/develop/connectivity/telecom/dialer-apps
  - https://developer.android.com/reference/android/app/role/RoleManager
- **Finding:** answering/rejecting calls and reading incoming-call details requires Telecom integration as the default dialer (`ROLE_DIALER`), a `ConnectionService` used by a calling app, or a notification listener for call notifications. The first changes the user's dialer, the third is on the project's prohibited list, and the middle does not apply to calls originated by other apps.
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

Detailed instructions for each prototype are in `Docs/PROTOTYPE_PLAN.md`.

## 4. What changed since the 2026-11-07 baseline

- Added: Android 17 CT-by-default and ECH behavior, and the fact that CT does not apply to custom trust anchors (R-03).
- Added: the advertising-vs-picker permission asymmetry in `NsdManager`, which changes the recommended connection direction (R-02).
- Added: macOS 15.4+ pasteboard access prompts, a risk the baseline did not record (R-11).
- Added: macOS HID-peripheral refusal evidence and the classic-HID ownership detail (R-12).
- Added: Knox authorization-scope requirement and DeX API removal on Android 16+ (R-14).
- Added: audio playback capture boundary conditions and call-audio impossibility, plus Android 17 background-audio hardening (R-15, R-16).
- Added: Android 15+ screenshare protections (notifications/passwords/OTP activities) (R-07) and physical-keyboard password masking (R-19).
- Confirmed unchanged: MediaProjection consent/FGS rules (R-04, R-05), `FLAG_SECURE` (R-08), clipboard focus gate (R-09), macOS local-network privacy (R-10).

## 5. Negative findings worth repeating

- There is **no** supported way for a normal consumer app to inject touch into other apps, so "remote control" must not be advertised.
- There is **no** way to make Android clipboard reads work in the background without either input focus or the default-IME role, and the default-IME role is out of scope.
- There is **no** way to capture cellular call audio with public APIs.
- macOS pasteboard access is no longer guaranteed to be silent; assume a user-visible prompt.
- Local network permission on Android 17 is not avoidable for a device that advertises a service.

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
- Foreground service types — https://developer.android.com/about/versions/14/changes/fgs-types-required
- `ClipboardManager` — https://developer.android.com/reference/android/content/ClipboardManager
- Copy and paste guide — https://developer.android.com/develop/ui/views/touch-and-input/copy-paste
- Secure clipboard handling — https://developer.android.com/privacy-and-security/risks/secure-clipboard-handling
- `AudioPlaybackCaptureConfiguration` — https://developer.android.com/reference/android/media/AudioPlaybackCaptureConfiguration
- `BluetoothHidDevice` — https://developer.android.com/reference/android/bluetooth/BluetoothHidDevice
- Telecom dialer integration — https://developer.android.com/develop/connectivity/telecom/dialer-apps
- `RoleManager` — https://developer.android.com/reference/android/app/role/RoleManager
- `WindowManager.LayoutParams` (`FLAG_SECURE`) — https://developer.android.com/reference/android/view/WindowManager.LayoutParams

**Apple**

- TN3179 Understanding local network privacy — https://developer.apple.com/documentation/technotes/tn3179-understanding-local-network-privacy
- `NSLocalNetworkUsageDescription` — https://developer.apple.com/documentation/bundleresources/information-property-list/nslocalnetworkusagedescription
- `NSBonjourServices` — https://developer.apple.com/documentation/bundleresources/information-property-list/nsbonjourservices
- `NSPasteboard.changeCount` — https://developer.apple.com/documentation/appkit/nspasteboard/changecount
- `NSPasteboard.AccessBehavior` — https://developer.apple.com/documentation/appkit/nspasteboard/accessbehavior-swift.enum
- `CBPeripheralManager` — https://developer.apple.com/documentation/corebluetooth/cbperipheralmanager
- `CBPeripheralManager.startAdvertising(_:)` — https://developer.apple.com/documentation/corebluetooth/cbperipheralmanager/startadvertising(_:)
- Network framework — https://developer.apple.com/documentation/network

**Samsung**

- Knox `RemoteInjection` — https://docs.samsungknox.com/devref/knox-sdk/reference/com/samsung/android/knox/remotecontrol/RemoteInjection.html
- Knox restricted API methods — https://docs.samsungknox.com/dev/knox-sdk/api-reference/restricted-api-methods/
- Knox remote support overview — https://docs.samsungknox.com/dev/knox-sdk/remote-support-overview.htm

**Secondary (labelled, unverified — never used as sole evidence)**

- W3C WebRTC issue #3109, mDNS ICE triggers the macOS local-network prompt — https://github.com/w3c/webrtc-pc/issues/3109
- IETF mDNS ICE candidate drafts — https://datatracker.ietf.org/doc/html/draft-ietf-rtcweb-mdns-ice-candidates-04
- Community reports of macOS `CBError.uuidNotAllowed` for service `0x1812`.
