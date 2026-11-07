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

Do not lock in WebRTC before measuring. Prototype two options:

A. Simple authenticated TCP/TLS with length-prefixed messages and a separate video stream for initial proof.
B. WebRTC with local-only ICE candidates, no STUN/TURN/cloud relay, if latency, congestion control and media adaptation justify the dependency.

The final choice must compare:
- Latency.
- Frame loss and recovery.
- Network handoff behaviour.
- Battery/CPU.
- Complexity and maintenance.
- Offline/local-only behaviour.
- Security and library maintenance.

## 6. Video pipeline candidate

Android:
MediaProjection → VirtualDisplay → encoder input Surface → MediaCodec H.264 → transport.

macOS:
Transport → VideoToolbox decode or AVSampleBufferDisplayLayer → SwiftUI/AppKit mirroring window.

Use hardware encoding/decoding where supported. Measure on the actual S24 Ultra and Mac. Handle resolution/rotation changes and projection stop callbacks.

## 7. Clipboard pipeline

Mac → Android:
NSPasteboard change detection → policy/filter → authenticated message → Android receive → explicit/allowed clipboard write → confirmation.

Android → Mac:
Foreground user action or verified permitted clipboard event → read current clip → policy/filter → authenticated message → Mac NSPasteboard write.

Use origin IDs and content hashes to prevent echo loops. Avoid storing raw clipboard text in logs or persistence.

## 8. Input pipeline

Mac pointer/keyboard events → experimental transport → selected supported input adapter.

Possible adapters are research-only:
- Bluetooth HID peripheral implementation on macOS.
- Android hardware keyboard/mouse support.
- Official OEM APIs only if eligible and authorized.

No generic input-injection API is assumed. If no compliant input adapter passes the proof of concept, release mirroring as view-only and clearly state the limitation.

## 9. Calls/audio

Keep separate from screen mirroring. Audio playback capture and call handling have distinct Android permissions, policies and roles. No call features in the MVP.
