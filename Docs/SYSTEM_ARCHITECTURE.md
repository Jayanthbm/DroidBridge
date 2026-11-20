# System Architecture

## 1. Architecture principles

- Two native apps, one product.
- Android and macOS share a documented protocol, not a shared UI framework.
- Wi-Fi is the primary transport.
- Bluetooth is optional and isolated.
- Clipboard and mirroring remain independent.
- No cloud dependency.
- Security and pairing are shared foundations.
- Remote input is an experimental adapter and cannot be assumed available.

## 2. Repository layout after implementation

```text
DroidBridge/
├── android/
│   ├── app/
│   ├── core/
│   │   ├── common/
│   │   ├── networking/
│   │   ├── pairing/
│   │   ├── security/
│   │   └── protocol/
│   ├── feature/
│   │   ├── dashboard/
│   │   ├── clipboard/
│   │   ├── mirroring/
│   │   └── settings/
│   └── experiments/
│       ├── clipboard/
│       ├── media-projection/
│       ├── bluetooth-hid/
│       └── input/
├── macos/
│   ├── DroidBridge.xcodeproj
│   ├── App/
│   ├── Core/
│   │   ├── Networking/
│   │   ├── Pairing/
│   │   ├── Security/
│   │   └── Protocol/
│   ├── Features/
│   │   ├── Dashboard/
│   │   ├── Clipboard/
│   │   ├── Mirroring/
│   │   └── Settings/
│   └── Experiments/
├── shared/
│   ├── protocol/
│   └── test-vectors/
├── Docs/
└── scripts/
```

## 3. Android layers

- UI: Jetpack Compose.
- Domain: pairing state, clipboard sync policy, mirroring session lifecycle.
- Platform adapters: ClipboardManager, MediaProjection, MediaCodec, NSD, Bluetooth (optional).
- Transport: authenticated TLS sockets or selected real-time media transport.
- Persistence: only pairing metadata and preferences; no clipboard history by default.

## 4. macOS layers

- UI: SwiftUI, menu bar status item, mirroring window.
- Domain: peer state, clipboard coordinator, mirroring session coordinator.
- Platform adapters: NSPasteboard, Network.framework, VideoToolbox/AVFoundation.
- Transport: matching protocol implementation.
- Persistence: trusted device identity and preferences in Keychain/UserDefaults as appropriate.

## 5. Transport decision

**Researched 2026-11-08 (R-18, ADR-012): TLS 1.3 over TCP with length-prefixed framing is the MVP transport.** Reasons: one authenticated duplex channel covers clipboard and the H.264 stream; no ICE/STUN/TURN; no mDNS candidate machinery, which on macOS 26+ can itself trigger the local-network prompt. WebRTC remains a measured fallback only if P08 shows the simple transport cannot meet latency/quality targets.

Connection direction (ADR-010): the macOS app advertises the Bonjour service and listens; the Android app selects it through the system NSD picker and dials out, so the Android app can run without the broad `ACCESS_LOCAL_NETWORK` permission.

Session ownership (ADR-023): when the user enables background clipboard sync, an Android `connectedDevice` foreground service owns the connection and shows a persistent notification; a mirroring session uses its own `mediaProjection` service and shares the same connection when both are active. Because Doze suspends network access device-wide, every consumer of the session treats "connected" as a state that can lapse and reconnects with backoff.

The P08 comparison must still record:
- Latency.
- Frame loss and recovery.
- Network handoff behaviour.
- Battery/CPU.
- Complexity and maintenance.
- Offline/local-only behaviour.
- Security and library maintenance.

## 6. Video pipeline candidate

Android:
MediaProjection → VirtualDisplay → encoder input Surface → MediaCodec H.264 → **Annex-B→AVCC conversion** → transport.

macOS:
Transport → `CMVideoFormatDescription` + `CMSampleBuffer` → `AVSampleBufferDisplayLayer` → SwiftUI/AppKit mirroring window.

The encoder is surface-fed, so no pixel or colour-format handling exists in app code (ADR-027). The conversion step is mandatory: MediaCodec emits start-code-prefixed NALs and CoreMedia requires bare parameter sets plus length-prefixed samples (R-30). The Mac viewer uses the display layer's own decoder, wrapped in one adapter so the deprecated enqueue API can be replaced by the macOS 27 `Receiver` later (ADR-028).

Use hardware encoding/decoding where supported. Measure on the actual S24 Ultra and Mac. Handle resolution/rotation changes (a re-encode with new parameter sets, not a scale), keyframe recovery after a receiver-side flush, and projection stop callbacks.

## 7. Clipboard pipeline

Mac → Android:
NSPasteboard change detection (**subject to the macOS 15.4+ programmatic-access prompt, ADR-014**) → policy/filter → authenticated message → Android receive → `setPrimaryClip()` → confirmation. If pasteboard access is denied, only a user-initiated "send clipboard now" action is offered.

Android → Mac:
Foreground user action or verified permitted clipboard event → read current clip → policy/filter → authenticated message → Mac NSPasteboard write.

Use origin IDs and content hashes to prevent echo loops. Avoid storing raw clipboard text in logs or persistence.

## 8. Input pipeline

Mac pointer/keyboard events → experimental transport → selected supported input adapter.

**Status 2026-11-17: Bluetooth HID feasibility reopened as a conditional POC (R-32, ADR-036); no control feature is implemented or claimed.**

- The upstream `jqssun/darwin-bt-remote` project contains distinct macOS BLE HID-over-GATT and Bluetooth Classic HIDP implementations. This contradicts the earlier blanket NO-GO inference that Mac-as-HID could not be done, but does not prove compatibility with DroidBridge's S24 Ultra / Android 17 / One UI target.
- P16 evaluates BLE HID; P17 evaluates Classic HIDP. These are isolated prototypes, not production adapters. Do not copy/vendor the AGPL-3.0-only implementation without a separate license decision.
- The upstream optional Direct Input path uses macOS Accessibility to capture local input and is explicitly excluded. DroidBridge POCs may transmit only events directly initiated inside DroidBridge's own UI.
- HID keyboard/pointer events are not automatically equivalent to touch gestures, do not set the phone clipboard, and must not be advertised as full remote control unless actual target tests prove the stated scope.
- Accessibility Service/API/nodes, ADB-generated functional input, `INJECT_EVENTS`, root, Knox injection, hidden APIs and screen-protection bypass remain prohibited. ADB may automate test setup (e.g. open Bluetooth Settings/connect an already-paired test peripheral) during development only; it is never the HID test-input source or runtime dependency. Do not use it to capture screen/UI hierarchy or read/write clipboard data.
- Mirroring and clipboard are separate features and POCs. Screen capture remains MediaProjection with user consent and `FLAG_SECURE`/DRM protections intact; clipboard remains the documented foreground/manual implementation.

Until P16/P17 pass on the target phone, production mirroring remains view-only and the Mac UI must not imply control. Bluetooth remains opt-in and visibly connected.

## 9. Calls/audio

Keep separate from screen mirroring. Audio playback capture and call handling have distinct Android permissions, policies and roles. No call features in the MVP.
