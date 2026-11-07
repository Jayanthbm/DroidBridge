# Local Network Protocol Specification

## 1. Principles

- Local network only.
- No cloud relay, STUN/TURN server or remote fallback in the MVP.
- Wi-Fi primary; Bluetooth optional and not part of core transport.
- Authenticated and encrypted.
- Versioned protocol.
- Clipboard and video channels logically separated.
- Never trust unauthenticated local network peers.

## 2. Discovery

Proposed service type: choose and document a unique Bonjour/DNS-SD type before implementation; do not invent a globally registered type if public distribution is planned. A private development type may be used during prototypes.

Advertised TXT metadata should contain only:
- protocol version,
- app version,
- random peer/session identifier,
- capability flags,
- ephemeral connection nonce.

Do not publish clipboard data, personal names, serial numbers, stable hardware identifiers or authentication secrets in discovery records.

Android 17 discovery options:
A. NSD system service picker with `DiscoveryRequest.FLAG_SHOW_PICKER`.
B. Direct NSD/mDNS requiring `ACCESS_LOCAL_NETWORK`.
Test both.

macOS:
- `NSLocalNetworkUsageDescription`
- `NSBonjourServices`
- Network.framework `NWBrowser`/`NWListener`.

## 3. Pairing

Initial pairing should be user-confirmed on both devices:
1. Discover peer.
2. Select device.
3. Exchange ephemeral public keys/nonces.
4. Display short authentication code on both screens.
5. User confirms matching code.
6. Derive/store trusted peer identity.
7. Establish mutually authenticated encrypted session.

Prefer standard, maintained cryptographic libraries and TLS 1.3 where supported. Do not implement custom cryptography.

Pairing code is not a long-term secret. Use certificate/public-key pinning or another documented authenticated identity model after pairing.

## 4. Session security

- TLS 1.3 preferred.
- Mutual peer authentication.
- Random session nonce.
- Replay protection.
- Per-message type and size validation.
- Rate limiting.
- Connection idle timeout.
- Explicit session close.
- Key rotation/re-pairing strategy.
- No plaintext fallback.
- No internet binding or relay.

## 5. Message envelope

Conceptual JSON envelope for control messages:

```json
{
  "protocolVersion": 1,
  "messageId": "random-unique-id",
  "sessionId": "random-session-id",
  "originDeviceId": "pairwise-pseudonymous-id",
  "messageType": "clipboard.text",
  "sentAt": "2026-11-07T12:00:00Z",
  "payload": {
    "text": "example"
  }
}
```

Production implementation must define a strict schema, maximum payload sizes, validation and binary framing. JSON is for control messages; video should use a binary media transport rather than base64 JSON.

## 6. Clipboard messages

- `clipboard.offer`
- `clipboard.accept`
- `clipboard.text`
- `clipboard.ack`
- `clipboard.reject`
- `clipboard.pause`
- `clipboard.clear` (only if explicitly user-triggered)

Fields:
- content ID/hash,
- source peer,
- text encoding,
- byte length,
- sensitivity marker if available,
- expiry/TTL,
- origin ID.

Never include raw clipboard text in logs.

## 7. Mirroring messages

- `mirror.start_request`
- `mirror.consent_status`
- `mirror.started`
- `mirror.stop`
- `mirror.stopped`
- `mirror.format`
- `mirror.rotation`
- `mirror.error`

Media channel should carry H.264 or another mutually supported codec with explicit framing, timestamps and keyframe recovery. Evaluate WebRTC versus a simple local stream during prototypes.

## 8. Input messages

Only define/use after a supported input mechanism passes the feasibility gate:
- `input.pointer`
- `input.key`
- `input.scroll`
- `input.ack`

Never send input commands if Android has not explicitly enabled a supported input path. Do not simulate success.

## 9. Versioning

- Major protocol version for incompatible changes.
- Minor version for compatible optional capabilities.
- Capability negotiation at session start.
- Unknown mandatory message type must fail safely.
- Add protocol test vectors in `shared/test-vectors/`.
