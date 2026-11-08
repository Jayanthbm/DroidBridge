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

Service type: prototypes and the first implementation use the private type `_droidbridge-dev._tcp`. A final unique type must be chosen and recorded here before any public release; do not invent a globally registered type if public distribution is planned (ADR-022).

Advertised TXT metadata should contain only:
- protocol version,
- app version,
- random peer/session identifier,
- capability flags,
- ephemeral connection nonce.

Do not publish clipboard data, personal names, serial numbers, stable hardware identifiers or authentication secrets in discovery records.

Android 17 discovery (researched 2026-11-08, R-01/R-02):

- **Direction is fixed by the permission model.** `NsdManager.registerService()` (advertising) always requires `ACCESS_LOCAL_NETWORK`, while a picker-selected service can be connected to without it. Therefore the **macOS app advertises and listens**, and the **Android app selects the Mac through `DiscoveryRequest.FLAG_SHOW_PICKER` and connects outbound** (ADR-010).
- The picker grant is per-service, persists across reboots, is re-checkable with `checkPermissionForService()` and reusable via `DiscoveryRequest.FLAG_USER_APPROVED_ONLY`; it can be revoked by the user or for storage reasons.
- Never cache the peer IP: re-resolve through `registerServiceInfoCallback()` on every reconnect, network change and wake.
- Fallback only: `ACCESS_LOCAL_NETWORK` runtime permission plus Android-side advertising, if P02 shows the picker path cannot sustain the session.
- The Mac must declare `NSLocalNetworkUsageDescription` and list the service type in `NSBonjourServices`, and must handle the local-network prompt being undetermined or denied (R-10).

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

Identity model (ADR-011): each side generates a self-signed certificate on first run; the user confirms a short code on both screens; the peer certificate is pinned. Android declares the peer certificate as a custom trust anchor in `network_security_config.xml` — certificate transparency is not applied to custom trust anchors, so the Android 17 CT default does not block a pinned local certificate (R-03). Never disable CT or verification to make a handshake succeed.

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

## 4A. Session persistence, keepalive and reconnect

- The session is owned by a `connectedDevice` foreground service on Android when background sync is enabled (ADR-023, R-20). There is no session without that service or a visible app.
- Liveness is best-effort: Doze suspends network access device-wide, so the protocol must assume a socket can go stale at any time.
- Keepalive: a small `session.ping`/`session.pong` at a modest interval (start at 15 s, tune from P14 measurements). Never spin a tight loop.
- Reconnect: exponential backoff with jitter, a cap, and reset on success. Reconnect re-resolves the peer through `registerServiceInfoCallback()` rather than reusing a stored IP, and re-authenticates with the pinned identity.
- A stale connection must be treated as "disconnected" in the UI; the app must never show "connected" for a socket that has not completed a ping round-trip.
- Message loss across reconnect must not corrupt state: clipboard offers are idempotent by content hash, and the media channel restarts from a keyframe.

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
- `mirror.keyframe_request`
- `mirror.error`

### Media channel framing (settled by ADR-027, pending P07/P08)

Codec: H.264/AVC only for the MVP. VideoToolbox and Android hardware encoders both support it, and it keeps the parameter-set path on CoreMedia's documented API.

- `mirror.format` carries `width`, `height`, `rotation`, `sps`, `pps` and `nalLengthSize`. `sps`/`pps` are **bare NAL units**: no start code, no length prefix, emulation-prevention bytes exactly as encoded, Base64 encoded inside the JSON payload. `nalLengthSize` is 4. The producing side of this message is implemented in `shared/protocol/` (`MirrorFormat`, `H264ParameterSets`), which refuses parameter sets that still carry a start code.
- A media frame payload is **one access unit** made of repeated `[4-byte big-endian NAL length][NAL bytes]`. This is the AVCC shape CoreMedia requires, not Annex-B; the conversion happens on Android so a framing bug surfaces in the code that produced it.
- Timestamps: `ptsUs`, a 64-bit microsecond value from the producer's monotonic clock. PTS must be strictly increasing across the session — CoreMedia documents duplicate presentation timestamps as undefined. On `mirror.started` and after any reconnect the receiver **rebases** to its own clock instead of trusting a resumed producer timeline.
- No B-frames (`KEY_MAX_B_FRAMES = 0`), so samples are in decode order and the receiver uses `decodeTimeStamp = invalid`.
- The first frame after `mirror.started`, after every `mirror.format`, after a reconnect and after any receiver-side decoder flush **must be an IDR**.
- The receiver may drop non-keyframes under backpressure (`isReadyForMoreMediaData == false`) but must never drop the IDR that follows a format change.
- `mirror.keyframe_request` exists so the Mac can force an IDR after `AVSampleBufferDisplayLayer` reports `.failed` / `requiresFlushToResumeDecoding` and is flushed. Without it, recovery would resume with P-frames against a decoder that has no reference picture.
- A rotation or capture-region resize is a **new encode**: `mirror.format` with new parameter sets, then an IDR. It is never handled by stretching the previous stream.

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
