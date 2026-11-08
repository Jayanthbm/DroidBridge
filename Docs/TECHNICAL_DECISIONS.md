# Technical Decision Log

Use newest entries at the top. Every change must include date, status, context, decision, alternatives and consequences.

Research pass of 2026-11-08 produced ADR-010 to ADR-022. Each one cites the register entry it comes from in `Docs/RESEARCH_AND_FEASIBILITY.md`.

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
- Consequences: `ACCESS_LOCAL_NETWORK` handling, picker UX and denied-state UX must exist before Phase 2 closes.

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
- Consequences: Congestion control, keyframe recovery and pacing are our responsibility and must be measured in `P08`. This supersedes ADR-009.

## ADR-011 — Identity is an app-owned pinned certificate, not a public CA
- Date: 2026-11-08
- Status: Accepted for prototypes; verify in `P13`
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
