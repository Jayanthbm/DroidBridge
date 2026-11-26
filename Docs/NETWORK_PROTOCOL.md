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

### Implemented wire shape (Phase 3)

Message codes: `clipboard.offer 0x30`, `clipboard.accept 0x31`, `clipboard.text 0x32`,
`clipboard.ack 0x33`, `clipboard.reject 0x34`, `clipboard.pause 0x35`. All of them ride the existing
envelope `[version][type][sequence:4 BE][payload]`, so they are ordered with the keepalive and cannot
overtake an earlier frame — which is what lets an offer and its body travel as two messages.

- **offer**: UTF-8 JSON, `{"hash":"<sha256 hex>","bytes":<utf8 length>,"origin":"<peer id>","source":"<label>"}`.
  `origin` is mandatory: a missing origin is a framing error, never a default, because a defaulted
  origin disables loop prevention.
- **text**: the raw UTF-8 bytes, no JSON, no base64. Byte length is the offer's `bytes`; text is never
  re-encoded in transit.
- **accept**: empty payload.
- **ack**: the 32-byte content hash as lowercase hex — the receiver's proof that *that* body landed.
- **reject**: a short wire reason, one of `too_large`, `not_foreground`, `paused`, `hash_mismatch`,
  `own_origin`.
- **pause**: `pause` or `resume`.

Limits and policy settled by implementation:

- Maximum text size is **64 KiB** of UTF-8 (`ClipboardSync.MAX_TEXT_BYTES`). The limit is applied to
  the offer, so an oversized body is refused before it is transferred.
- **Sensitivity markers are honoured, not ignored.** An Android clip flagged
  `ClipDescription.EXTRA_IS_SENSITIVE` and a macOS pasteboard item carrying
  `org.nspasteboard.ConcealedType` are refused at the sender. Declining to send is the only safe
  behaviour for a password-manager copy that the user did not ask to move.
- **TTL is not implemented** by design: nothing is stored, so nothing can expire. The MVP keeps at
  most one pending incoming text in memory and drops it when applied, dismissed, or the session ends.
- Loop prevention uses **content hash plus origin ID**, per §6. A receiver that applies text remembers
  that hash until the next local copy, so the resulting echo is not re-sent.

The Android side implements the corresponding permission-free rules in `ClipboardBridge`
(`android/feature/clipboard/`), the macOS side in `ClipboardBridgeMac` (`macos/Core/`), and both are
specified by the shared, unit-tested `ClipboardSync` object.

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
- `device.info` (0x40): what the phone is, so the Mac can name it. Payload is `DeviceInfo` JSON with
  `manufacturer`, `model`, `androidRelease`, `sdkInt` and `appVersion`; fixed key order, so the encoding is
  a test vector on both sides. Sent by the phone every time a session reaches CONNECTED, including silent
  reconnects. It is a label, not an authorisation - the session is already authenticated - and it carries
  no serial number, build fingerprint, user-set device name or account. Unknown control types are a framing
  error on both peers, so this message needs both ends updated.

### Audio messages (ADR-057, shipping since 2026-11-26)

Control types: `audio.format` (`AUDIO_FORMAT`, 0x22) and `audio.frame` (`AUDIO`, 0x21, a media frame).

- Codec: AAC-LC, 44 100 Hz, 2 channels, 128 000 bit/s. This is the universal platform encoder
  (`c2.android.aac.encoder`) and decodable by `AVAudioConverter` on macOS without a container.
- `audio.format` carries `codec` ("aac-lc"), `sampleRate`, `channels`, `bitrate` and `audioSpecificConfig`
  (Base64 of the 2-byte AAC AudioSpecificConfig, e.g. `EhA=` for 44.1 kHz stereo LC). It is sent once
  before the first audio frame and again after any change; the Mac treats a change as a new decoder.
- An `audio.frame` payload is `[8-byte big-endian ptsUs][AAC access unit]`. One access unit per message,
  no length prefix beyond the envelope, no ADTS header: the access unit is exactly what MediaCodec emits.
- Both sides measure amplitude (RMS of decoded PCM, 16-bit units) and log `signal` / `silence`. A stream
  that carries frames but measures 0 is digital silence, not success: apps can set
  `ALLOW_CAPTURE_BY_NONE`, and calls/notifications are excluded by the usage set (ADR-045, ADR-057).
- Audio and video are **not** re-timed. The receiver schedules audio as it arrives; the A/V offset is a
  known unmeasured quantity and is not to be presented as fixed.
- `mirror.stop` ends audio capture and the receiver tears down the audio player; an audio failure never
  stops video.
- **Either peer may send `mirror.stop`.** From the phone it reports that capture ended and carries the
  reason; from the Mac it is a request, and the phone runs the same teardown it runs for its own Stop
  button (reason `mac stop`), then sends its own `mirror.stop` back. Both directions keep the TLS session
  open: stopping a share is session-scoped, and sharing again needs a fresh consent, not a new pairing.

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
- Add protocol test vectors only in `shared/test-vectors/` (binary golden files, hex, and JSON). No app data, logs or secrets may ever live there. No other directory under `shared/` may hold protocol artifacts.
