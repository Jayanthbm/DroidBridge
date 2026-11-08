# Technical Decision Log

Use newest entries at the top. Every change must include date, status, context, decision, alternatives and consequences.

Research pass of 2026-11-08 produced ADR-010 to ADR-028. Each one cites the register entry it comes from in `Docs/RESEARCH_AND_FEASIBILITY.md`.

## ADR-028 — The Mac decodes and displays compressed frames with `AVSampleBufferDisplayLayer` behind one adapter
- Date: 2026-11-08
- Status: Proposed — pending `P07`
- Context: the Mac only has to *view* the Android stream (ADR-015 excludes remote input), so it needs a decoder and a renderer, not frame access. Research (R-31) established that `AVSampleBufferDisplayLayer` decodes compressed H.264 itself, so no `VTDecompressionSession` and no `CVPixelBuffer` handling are required. It also established that the layer's `enqueue(_:)`/`isReadyForMoreMediaData`/`flush()` API is deprecated in the current SDK in favour of `AVSampleBufferVideoRenderer.Receiver`, which is documented as macOS 27.0+ — an OS that is not shipping today.
- Decision: build the viewer on `AVSampleBufferDisplayLayer` + `CMVideoFormatDescriptionCreateFromH264ParameterSets` + `CMSampleBufferCreateReady`, wrapped in a single narrow adapter so the enqueue path can be swapped for `Receiver` later. One encoded frame per sample buffer, one timing entry, `decodeTimeStamp = .invalid`, strictly increasing PTS in a 1/1,000,000 timescale derived from Android's `presentationTimeUs` and rebased on reconnect. Gate enqueues on `isReadyForMoreMediaData` and coalesce frames rather than queueing without bound. On `.failed`/`requiresFlushToResumeDecoding`, call `flush()` and **request a keyframe** rather than resuming P-frames. Set `preventsCapture = true` so the mirrored phone screen cannot leak into a Mac screen recording. Do not raise the deployment target to silence deprecation warnings for an API that does not exist yet.
- Alternatives: `VTDecompressionSession` with a `CVPixelBuffer` pool and a custom `CALayer` (rejected: more code, more state, and it is only justified if frames must be inspected or transformed, which no MVP feature needs); `AVPlayer`/`AVPlayerItemVideoOutput` (rejected: it wants a file or HLS-like timeline, not a live push stream); WebRTC's macOS renderer (rejected with ADR-012: it brings the whole media stack).
- Consequences: the adapter is the only place that touches AVFoundation, so the macOS 27 migration is contained. The keyframe-on-recovery rule becomes part of `NETWORK_PROTOCOL.md`, not an implementation detail. P07 must record which members warn as deprecated on the owner's toolchain (U20) and must measure the layer's added latency rather than assuming it is negligible.

## ADR-027 — Mirroring encodes H.264 from the capture surface and puts length-prefixed AVCC on the wire
- Date: 2026-11-08
- Status: Proposed — pending `P01`/`P07`
- Context: ADR-012 chose TLS/TCP and left codec framing to the prototypes. R-29 and R-30 supply the constraints: MediaCodec can be fed by a `Surface` (so no pixel access and no colour conversion in app code), it emits Annex-B with start-code-prefixed parameter sets, and CoreMedia requires parameter sets as bare NALs plus length-prefixed samples. The formats are incompatible by construction, so one conversion is unavoidable.
- Decision: encode with `MediaCodec` in surface mode (`COLOR_FormatSurface`, `CONFIGURE_FLAG_ENCODE`, `createInputSurface()` handed to `createVirtualDisplay()`), `video/avc`, `KEY_MAX_B_FRAMES = 0`, `KEY_REPEAT_PREVIOUS_FRAME_AFTER` set so a static screen keeps producing frames, and a bitrate mode chosen from `isBitrateModeSupported()` with **CBR preferred** because the API-31 minimum quality floor applies only to VBR. Choose the encode size from `VideoCapabilities.areSizeAndRateSupported()`, never from the panel resolution. Convert on the **producer**: SPS and PPS travel as bare NALs in `mirror.format`, and every access unit is sent as 4-byte big-endian length + NAL bytes, with emulation-prevention bytes untouched.
- Alternatives: send Annex-B and convert on the Mac (rejected: moves a framing bug away from the code that produced it); mux to MP4 with `MediaMuxer` (rejected: file-oriented, adds buffering to a live stream); let the encoder pick the profile (rejected: the documented default is device-specific and may be experimental, and a mismatched profile can make the session fail); VBR by default (rejected: the quality floor can exceed the requested bitrate).
- Consequences: `NETWORK_PROTOCOL.md` must define the media framing precisely, including the keyframe requirement at stream start, after a format change, and after a Mac-side decoder flush. Rotation/resize is a re-encode with a new `mirror.format`, not a scale. P01 must record which resolutions the S24 Ultra encoder accepts (U18) and P08 whether parameter sets change mid-session (U19).

## ADR-026 — macOS ships outside the Mac App Store as a notarised, sandboxed Developer ID app
- Date: 2026-11-08
- Status: Accepted — release prerequisites recorded
- Context: the macOS app is the advertiser and listener (ADR-010) and needs its own identity (ADR-024). Research on distribution (R-25–R-28) established that Developer ID software built after 2019-06-01 must be notarised to run on macOS 10.15+, that notarisation requires the hardened runtime and a secure timestamp, that an unnotarised app can only be opened through System Settings → Privacy & Security → Open Anyway, and that only the Apple Developer Program **Account Holder** can create a Developer ID certificate. On Android, Play's floor is API 36 since 2026-08-31, so `targetSdk 37` is above it.
- Decision: the Mac app is distributed directly as a **Developer ID-signed, notarised and stapled DMG**, with the hardened runtime enabled, a secure timestamp, no `get-task-allow`, ASCII XML entitlements, and inside-out signing — never `codesign --deep` for signing. The App Sandbox is enabled from the first build with `com.apple.security.app-sandbox`, `com.apple.security.network.client` and `com.apple.security.network.server`. Android ships through Google Play as an **AAB with Play App Signing**, declaring the `connectedDevice` and `mediaProjection` FGS types with their descriptions, impact statements and demo videos, and the `ACCESS_LOCAL_NETWORK` declaration only if P02 forces that permission. The Mac App Store is deferred but kept reachable by keeping the sandbox on.
- Alternatives: unnotarised distribution with a bypass instruction (rejected: current macOS offers only the Settings override, and instructing users to disable a security control is not an acceptable install path); Mac App Store only (rejected for now: adds review and storefront requirements without solving anything the direct path does not, and the audience is the owner's own devices); ad-hoc signing as the release format (rejected: not notarisable, and the local-network privilege state does not track the program reliably).
- Consequences: a **paid Apple Developer Program membership** and the Account Holder's involvement are hard prerequisites before Phase 9; notary credentials (app-specific password or App Store Connect API key) must live outside the repository; the release checklist gains signing, notarisation, stapling and declaration items; a sandbox violation becomes a release blocker rather than a late surprise. Moving the listener into a launchd daemon to inherit macOS's automatic local-network allowance is explicitly **not** used — it would remove the user's control over a privacy permission.

## ADR-025 — The macOS identity is stored in the data protection keychain under an embedded Developer ID profile
- Date: 2026-11-08
- Status: Proposed — pending `P13`/`P15`
- Context: ADR-024 requires a non-exportable identity key and certificate that survive relaunches and remain readable by the app after re-signing. macOS has two keychain implementations (TN3137): the legacy file-based keychain, whose items are guarded by ACLs, and the data protection keychain, whose access groups "must be authorized by a provisioning profile" and whose groups are built from code-signing entitlements. Apple's entitlement rule (R-28) makes the App Sandbox and Hardened Runtime entitlements unrestricted, but `keychain-access-groups` restricted — so the storage choice decides whether the app needs an embedded profile at all.
- Decision: store the identity in the **data protection keychain** (`kSecUseDataProtectionKeychain: true`) under this app's own `keychain-access-groups` entry, authorized by an **embedded Developer ID provisioning profile**. The key is a P-256 key from `SecKeyCreateRandomKey`, marked non-exportable and Secure-Enclave-backed where the hardware supports it; the self-signed certificate from ADR-024 is stored beside it. No iCloud synchronisation (`kSecAttrSynchronizable` stays off), so pairing material never leaves the Mac.
- Alternatives: legacy file-based keychain (rejected as the default: its ACLs prompt the user when the app's designated requirement changes, which is exactly what a certificate renewal or re-sign does, and it gives weaker per-app isolation); keeping the key in a file protected by a keychain-held secret (rejected: more code, weaker guarantee, and the private key would exist outside the Keychain's control); not storing a key at all and regenerating per launch (rejected: it would re-pair every time).
- Consequences: every build carries a profile, which must be replaced with a **distribution** profile at release and re-signed afterwards — the profile is inside the bundle, so it is part of what gets notarised. If P15 shows the profile is unworkable for local development, the fallback is the legacy keychain with ACL prompts accepted, and this ADR is superseded rather than silently ignored. U17 tracks whether re-signing under a renewed certificate keeps the identity readable.

## ADR-024 — Device identity is generated per install and pinned by public-key digest
- Date: 2026-11-08
- Status: Proposed — pending `P13`
- Context: ADR-011 chose a pinned, app-owned certificate but left the mechanics open. Research (R-22–R-24) established that Android's Keystore generates a self-signed certificate for an asymmetric key at no extra cost, that macOS has **no** public certificate-creation API and needs Apple's `swift-certificates`, and that neither platform's default verification can be used for an IP-literal peer.
- Decision: On first run each app generates a non-exportable P-256 key and a self-signed v3 certificate (Android: `KeyGenParameterSpec` with `PURPOSE_SIGN`, subject/serial/validity set, certificate read back from the Keystore; macOS: swift-crypto key + `X509.Certificate(version:serialNumber:publicKey:notValidBefore:notValidAfter:issuer:subject:extensions:issuerPrivateKey:)`, key and certificate stored in the app's Keychain). Both sides present the identity over mutual TLS (Android `setNeedClientAuth(true)` on the accepting socket; macOS `sec_protocol_options_set_local_identity` plus an explicit `sec_protocol_options_set_peer_authentication_required(true)`, because servers default to *not* requiring peer authentication). Verification is a SHA-256 digest of the peer's SubjectPublicKeyInfo compared on every handshake — no hostname matching, no system trust store, no static trust anchor in `network_security_config.xml` (impossible for a peer learned at pairing time).
- Alternatives: static `<trust-anchors>` with a bundled peer certificate (rejected: the peer is unknown at build time); TLS-PSK (rejected: Apple PSK is TLS 1.2 only and Android has no public PSK API); hand-built DER/X.509 (rejected: unnecessary risk); system trust store with a local CA (rejected: no CA, no cloud, no build-time secret).
- Consequences: a key change is an identity change and must be shown as such; a certificate renewal for the same key needs no re-pairing. One Apple-maintained dependency set (`swift-certificates`, `swift-crypto`, `swift-asn1`) is added to the macOS app. Peer-authentication defaults must be asserted in tests, since a silent default would produce one-way authentication that still "works". **Where** the macOS key and certificate are stored — and the provisioning-profile consequence of that choice — is settled separately in ADR-025.

## ADR-023 — Background clipboard session runs in a `connectedDevice` foreground service
- Date: 2026-11-08
- Status: Proposed — pending `P14`
- Context: "Clipboard sync while mirroring is off" needs a live session, but MediaProjection is not available for clipboard-only work. The `connectedDevice` type covers network connections to external devices, has no documented timeout, is not blocked from `BOOT_COMPLETED`, and its runtime prerequisite can be met by **declaring** the normal `CHANGE_NETWORK_STATE` permission — so it needs no runtime prompt (`RESEARCH_AND_FEASIBILITY.md` R-20). Doze still suspends the network device-wide, so liveness is best-effort.
- Decision: One `connectedDevice` foreground service owns the LAN session whenever background sync is enabled by the user; the mirroring session uses its own `mediaProjection` service and, when both run, shares the same connection. The service is started only from app UI or from `BOOT_COMPLETED`, shows a persistent notification with connection state and a disconnect action, and on any network change, Doze exit, socket error or app resume it reconnects with exponential backoff. Declare `POST_NOTIFICATIONS` and request it in context, and declare the `connectedDevice` type on the Play Console app-content page (R-21).
- Alternatives: `dataSync` (rejected: 6 h/24 h cap and boot restriction); `specialUse` (rejected: Play subtype review for a covered use case); no background service at all (rejected for the P0 clipboard user story, retained as the degradation path if P14 fails); `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (rejected: not justifiable for a consumer app).
- Consequences: the app always shows a session notification while background sync is on, must treat "connected" as a state that can lapse, and must never present a stale socket as reachable. If P14 shows the socket cannot survive a normal night, the feature degrades to foreground-only sync and the product copy changes accordingly.

## ADR-022 — Reserve a private Bonjour service type during development
- Date: 2026-11-08
- Status: Accepted for prototypes
- Context: The Mac advertises a service that Android must discover through the system NSD picker (ADR-010). `NSBonjourServices` must list the type, and a globally wrong or already-registered type would confuse discovery.
- Decision: Prototypes use the private type `_droidbridge-dev._tcp`. Before any public release, a unique type must be chosen and documented in `NETWORK_PROTOCOL.md`, and the same value used in `NSBonjourServices`, the Android `DiscoveryRequest`, and the TXT record schema.
- Alternatives: A generic type such as `_http._tcp` (rejected: collides with unrelated services and pollutes the picker list).
- Consequences: Service type is part of the pairing compatibility surface; changing it later forces re-pairing.

## ADR-021 — Target Android 17 (API 37) for the MVP
- Date: 2026-11-08
- Status: Accepted
- Context: Android 17 blocks local network access by default for apps targeting API 37+ (`RESEARCH_AND_FEASIBILITY.md` R-01). Targeting ≤ 36 keeps an implicit grant but is temporary and conflicts with Play target-API requirements and the security posture.
- Decision: `targetSdk = 37` from the first Android build, with the LAN strategy in ADR-010 adopted from day one rather than retrofitted.
- Alternatives: Stay on 36 to avoid the permission model (rejected: temporary, and would hide a mandatory migration behind a later release).
- Consequences: `ACCESS_LOCAL_NETWORK` handling, picker UX and denied-state UX must exist before Phase 2 closes. Confirmed 2026-11-08: Play's floor has been API 36 since 2026-08-31, so 37 is above the requirement rather than below it, and targeting 37 is a deliberate choice to absorb the local-network model early (R-25). Because `ACCESS_LOCAL_NETWORK` became a restricted permission with a Play declaration from 2027-08-25, the picker-first design also avoids a policy deliverable — one more reason for ADR-010's direction.

## ADR-020 — Treat Android screenshare protections as a documented product limitation
- Date: 2026-11-08
- Status: Accepted
- Context: Android 15+ hides notification content, password input and OTP-bearing activities from screen sharing (`R-07`). Secure windows are excluded via `FLAG_SECURE` (`R-08`).
- Decision: Do not attempt to defeat or detect-and-recover these protections. Mirroring surfaces blank/redacted regions and the UI states why this can happen. DroidBridge's own sensitive screens use `FLAG_SECURE`.
- Alternatives: Asking users to enable Developer Options "disable screen share protections" (rejected: developer-only escape hatch, removes a user protection, and would make test results unrepresentative).
- Consequences: Product copy must set expectations; mirroring tests must record redacted regions instead of treating them as failures.

## ADR-019 — Call notifications and call control are out of scope
- Date: 2026-11-08
- Status: Rejected for MVP
- Context: Answering/rejecting calls or reading call details requires the default-dialer role, a `ConnectionService` owned by the calling app, or a notification listener (`R-17`). Cellular call audio cannot be captured at all (`R-15`).
- Decision: No Telecom role, call-log, phone-state, microphone or notification-listener permission. No call features in the MVP or in the initial release scope.
- Alternatives: Notification listener for call alerts (explicitly prohibited by `AGENTS.md`); default-dialer role (changes the user's phone).
- Consequences: Removing call work also removes the strongest pressure to add broad permissions.

## ADR-018 — No audio capture in the MVP
- Date: 2026-11-08
- Status: Rejected for MVP; research track open
- Context: Media playback capture needs `RECORD_AUDIO` plus MediaProjection consent and subject-app opt-in, and is further constrained in Android 17 background-audio hardening (`R-15`, `R-16`). Call audio is impossible.
- Decision: MVP streams video only. Audio is a separately approved research track (`P12`) that must not add `RECORD_AUDIO` to the shipping manifest before that approval.
- Alternatives: Add `RECORD_AUDIO` now for a future feature (rejected: speculative permission).
- Consequences: Mirroring UX must state that there is no audio.

## ADR-017 — Knox RemoteInjection is excluded
- Date: 2026-11-08
- Status: Rejected
- Context: `KNOX_REMOTE_CONTROL` is signature-protected; since Knox API 38 non-Device-Owner/Profile-Owner callers need an authorized scope issued through MDM, and the DeX injection methods are gone on Android 16+ (`R-14`).
- Decision: Do not use Knox SDK, Knox permissions, or any managed-device requirement. Do not prototype against Knox.
- Alternatives: None compatible with a consumer app and the project's prohibition on privileged OEM interfaces.
- Consequences: Remote input cannot rely on Samsung tooling; see ADR-015 and ADR-016.

## ADR-016 — No Bluetooth HID input path in either direction
- Date: 2026-11-08
- Status: Rejected for MVP
- Context: macOS refuses to publish the HID-over-GATT service through CoreBluetooth and owns the classic HID transports (`R-12`). Android's `BluetoothHidDevice` makes Android the peripheral, not the host-driven device (`R-13`). Android 17 also masks password characters typed from physical keyboards (`R-19`).
- Decision: Do not build Mac → Android input on Bluetooth HID. Bluetooth remains an optional, non-core discovery/experiment channel at most.
- Alternatives: A custom BLE GATT link (produces data in a companion app, not system input — not a substitute), or a future owner decision to accept a different trade-off.
- Consequences: The mirroring product is view-only (ADR-015). `P09` exists only to confirm the refusal and record evidence.

## ADR-015 — Mirroring ships view-only; remote input stays a closed gate
- Date: 2026-11-08
- Status: Accepted
- Context: No public API lets an ordinary app inject touch or keys into other apps under the project's constraints; the remaining candidates (Accessibility, ADB, root, Knox) are prohibited or ineligible (`R-12`, `R-13`, `R-14`).
- Decision: Ship mirroring as a viewer. The Mac UI must not present a clickable "control" affordance that cannot deliver input. `input.*` protocol messages stay undefined until a supported mechanism is proven.
- Alternatives: Ship an "experimental control" that may silently fail (rejected: `AGENTS.md` forbids claiming control without on-device proof).
- Consequences: `NETWORK_PROTOCOL.md` input section remains a stub; Phase 5 becomes a documentation/measurement phase rather than a feature phase.

## ADR-014 — Model macOS pasteboard access as an explicit user-granted state
- Date: 2026-11-08
- Status: Proposed — pending `P06`
- Context: macOS 15.4+ defaults the general pasteboard to *ask upon programmatic access*, and a denial blocks monitoring until the user changes System Settings (`R-11`).
- Decision: The Mac app models three states (undetermined / allowed / denied), explains the prompt before it appears, links to the settings pane, and always offers a manual "send clipboard now" path that works from an explicit user action. Automatic Mac → Android sync is enabled only after access is confirmed.
- Alternatives: Poll and hope (rejected: silent failure); require `alwaysAllow` up front with no explanation (rejected: poor trust UX).
- Consequences: Clipboard sync must degrade to a manual workflow without pretending to be automatic.

## ADR-013 — Clipboard sync is foreground/manual first, with no IME and no Accessibility
- Date: 2026-11-08
- Status: Accepted (reaffirms ADR-006)
- Context: `getPrimaryClip()` returns null without input focus or the default-IME role (`R-09`); the project forbids requiring an IME or an Accessibility Service.
- Decision: Android → Mac is a user-initiated action from the app while it holds focus. Mac → Android writes via `setPrimaryClip()` and must tolerate system clipboard UI and auto-clear. No clipboard history, no background read attempts, no silent retries.
- Alternatives: Bundle a keyboard/IME (rejected: changes the user's input method and privacy model); Accessibility scraping (prohibited).
- Consequences: Two-way automatic sync is not a release claim; the release notes must describe the manual workflow.

## ADR-012 — TLS 1.3 over TCP with length-prefixed framing is the MVP transport
- Date: 2026-11-08
- Status: Accepted for prototypes; final confirmation at `P08`
- Context: The MVP needs one authenticated duplex channel carrying control messages and an H.264 stream. WebRTC adds a media stack, ICE/mDNS machinery that interacts with macOS local-network privacy, and a second dependency surface (`R-18`).
- Decision: Mutually authenticated TLS 1.3 over TCP, length-prefixed binary framing for control and media frames, no STUN/TURN, no plaintext fallback. WebRTC is measured only if `P08` shows the simple transport cannot meet latency/quality targets.
- Alternatives: WebRTC with local-only candidates (retained as a measured fallback); QUIC (rejected for MVP: extra dependency, no benefit on a reliable LAN).
- Consequences: Congestion control, keyframe recovery and pacing are our responsibility and must be measured in `P08`. This supersedes ADR-009. The codec-specific half of this decision — surface-fed H.264 and length-prefixed AVCC on the media channel — is settled in ADR-027, and the macOS side in ADR-028.

## ADR-011 — Identity is an app-owned pinned certificate, not a public CA
- Date: 2026-11-08
- Status: Accepted for prototypes; implementation specified in ADR-024; verify in `P13`
- Context: Android 17 enables Certificate Transparency by default for targetSdk 37, but CT verification is not performed for connections using custom trust anchors (`R-03`). No public CA can be used for a local IP anyway.
- Decision: Each side generates a self-signed identity on first run, the user confirms a short authentication code on both screens, and the peer certificate is pinned. The Android app ships a `network_security_config.xml` declaring the app-owned anchor (never a global CT disable, never a trust-all manager).
- Alternatives: A shared pre-shared key (weaker identity model, no forward secrecy story); public CA with a local hostname (infeasible offline).
- Consequences: Certificate rotation equals re-pairing; both platforms must surface a clear "identity changed" warning.

## ADR-010 — The Mac advertises and the Android app dials out through the system NSD picker
- Date: 2026-11-08
- Status: Proposed — pending `P02`
- Context: On Android 17/API 37, `NsdManager.registerService()` (advertising) always requires `ACCESS_LOCAL_NETWORK`, while a picker-selected service can be connected to without the broad permission (`R-01`, `R-02`). The media direction is Android → Mac, so Android dialling out suits the pipeline.
- Decision: Primary design — the macOS app advertises `_droidbridge-dev._tcp` via Bonjour and listens; the Android app uses `DiscoveryRequest.FLAG_SHOW_PICKER` (~`FLAG_USER_APPROVED_ONLY` for repeat connections), connects out to the selected peer over TLS, and keeps that single duplex connection for clipboard and mirroring. `ACCESS_LOCAL_NETWORK` is the documented fallback only if `P02` shows the picker path cannot sustain the session (e.g. inbound needs, revocation churn, or One UI 9 behavior).
- Alternatives: Android advertises and listens (requires the broad runtime permission for every user); manual IP entry (rejected: brittle, DHCP changes, bad UX).
- Consequences: The Mac must request local-network access and declare its service type; Android must re-resolve addresses through `registerServiceInfoCallback` instead of caching IPs; reconnect UX depends on remembering picker grants and re-showing the picker when a grant is revoked.

## ADR-009 — Media transport not yet selected
- Date: 2026-11-07
- Status: Superseded by ADR-012
- Decision: Compare local TLS/TCP framing and WebRTC local-only transport before selection.
- Resolution: ADR-012 selects TLS/TCP for the MVP and keeps WebRTC as a measured fallback in `P08`.

## ADR-001 — Two native applications
- Date: 2026-11-07
- Status: Accepted
- Decision: One Android Kotlin/Compose app and one native macOS SwiftUI app in one repository.
- Rationale: Native clipboard, MediaProjection, pasteboard, networking and video APIs are platform-specific.

## ADR-002 — Wi-Fi first, Bluetooth optional
- Date: 2026-11-07
- Status: Accepted
- Decision: Wi-Fi is the primary channel for discovery, pairing, clipboard and screen streaming. Bluetooth is optional and experimental.
- Consequence: All core features must work with Bluetooth disabled.

## ADR-003 — No cloud
- Date: 2026-11-07
- Status: Accepted
- Decision: No cloud account, storage, relay or telemetry.
- Consequence: Same local network is required.

## ADR-004 — No Accessibility, root or debugging at runtime
- Date: 2026-11-07
- Status: Accepted
- Decision: Never require Accessibility Service, root, USB debugging or wireless debugging in production.
- Consequence: Arbitrary remote touch injection is not assumed.

## ADR-005 — Protected content must remain protected
- Date: 2026-11-07
- Status: Accepted
- Decision: Respect FLAG_SECURE, DRM and system projection controls.
- Consequence: Some screens may appear black/unavailable.

## ADR-006 — Clipboard foreground-first
- Date: 2026-11-07
- Status: Accepted (reaffirmed by ADR-013)
- Decision: Build reliable foreground/manual flows first. Automatic background clipboard behaviour is experimental.
- Rationale: Android 10+ restricts background clipboard reads.

## ADR-007 — Android 17 LAN permission investigation
- Date: 2026-11-07
- Status: Resolved into ADR-010 and ADR-021
- Decision: Test NSD system picker and ACCESS_LOCAL_NETWORK runtime permission paths.
- Rationale: Android 17/API 37 local network protections affect direct LAN sockets and NSD.

## ADR-008 — Remote input is a feasibility gate
- Date: 2026-11-07
- Status: Closed as NO-GO by ADR-015, ADR-016, ADR-017
- Decision: Do not claim full remote control until a compliant public API/HID approach passes physical-device tests.
- Alternatives: View-only mirroring MVP if no compliant route exists.
