# Technical Decision Log

Use newest entries at the top. Every change must include date, status, context, decision, alternatives and consequences.

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
- Status: Accepted
- Decision: Build reliable foreground/manual flows first. Automatic background clipboard behaviour is experimental.
- Rationale: Android 10+ restricts background clipboard reads.

## ADR-007 — Android 17 LAN permission investigation
- Date: 2026-11-07
- Status: Accepted for research
- Decision: Test NSD system picker and ACCESS_LOCAL_NETWORK runtime permission paths.
- Rationale: Android 17/API 37 local network protections affect direct LAN sockets and NSD.

## ADR-008 — Remote input is a feasibility gate
- Date: 2026-11-07
- Status: Accepted
- Decision: Do not claim full remote control until a compliant public API/HID approach passes physical-device tests.
- Alternatives: View-only mirroring MVP if no compliant route exists.

## ADR-009 — Media transport not yet selected
- Date: 2026-11-07
- Status: Open
- Decision: Compare local TLS/TCP framing and WebRTC local-only transport before selection.
