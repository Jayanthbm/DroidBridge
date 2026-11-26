# Security and Privacy Requirements

## 1. Threat model

Protect against:
- Another device on the same Wi-Fi attempting to connect.
- An untrusted Bluetooth peer pairing with or sending input to the Mac/phone.
- Rogue/impersonated Bonjour services.
- Replay or tampering of messages.
- Clipboard leakage.
- Accidental screen streaming after user expects it to stop.
- Logs/crash reports exposing clipboard or screen content.
- Reconnection to an untrusted or changed peer.
- Unintended exposure on public Wi-Fi.

The app does not claim to protect against a compromised Android OS, compromised Mac, malicious paired peer or physical device compromise.

## 2. Pairing and authentication

- Pairing must require user confirmation on both devices.
- Use ephemeral key exchange and a short authentication string/code.
- Store long-term peer credentials securely (Android Keystore, macOS Keychain).
- Revoke peers from either app.
- Never accept a new peer solely because it is on the same LAN.
- Re-pair when peer identity changes unexpectedly.

## 3. Encryption

- TLS 1.3 where supported.
- Mutual authentication or pinned peer identity.
- No plaintext fallback.
- No custom cryptography.
- No cloud key management.
- Protect key material at rest.

## 4. Clipboard privacy

Defaults:
- No clipboard history.
- No persistent clipboard content.
- No clipboard payload logging.
- Manual send available.
- Pause sync available.
- Maximum payload size.
- Duplicate suppression.
- User-visible last-sync metadata without content preview by default.
- No automatic OTP/SMS reading.
- Do not attempt to classify or extract banking OTPs from notifications.
- Provide a setting to disable sync for sensitive sessions.
- Clear in-memory payload buffers after delivery/timeout where practical.

## 5. Screen privacy

- Require MediaProjection consent for each session.
- Show session state in the app.
- Respect Android system stop controls.
- Stop on projection callback, screen lock, peer disconnect or user action.
- Respect `FLAG_SECURE` and protected surfaces.
- Never use alternate capture paths to bypass protection.
- Do not save/record frames by default.
- No hidden background capture.

### 5A. Audio privacy (ADR-057)

- Playback audio is captured only through `AudioPlaybackCaptureConfiguration` bound to the same
  MediaProjection consent, and only for `USAGE_MEDIA`, `USAGE_GAME` and `USAGE_UNKNOWN`. Calls, voice
  communication, notifications and alarms are outside the usage set and cannot appear in the stream.
- The microphone is never recorded: no `AudioRecord` source is used, only playback capture.
- Audio is transient: it is decoded on the paired Mac and played; it is never written to disk, never
  logged as samples, never sent to a server and never retained after the session. Diagnostics log
  amplitude and frame counts only.
- The switch defaults on but is visible before the share starts; turning it off means no
  `RECORD_AUDIO` request and no capture. A denial is honoured for the run.
- The Mac never requests a microphone TCC grant, because it only plays decoded samples.

### 5B. Development switches are inert in Release

The macOS app reads its prototype switches through `HarnessFlags`, which compiles the reads to constants
in a Release build. A shipped DroidBridge cannot be launched with `--p08-allow-capture` to turn off
`preventsCapture`, with `--p08-fault-after` to inject a corrupt sample, with `P08_RECORD_DIR`/`P08_DUMP_DIR`
to write video to disk, or with `P17_AUTO_ACCEPT_PAIRING` to answer a pairing code silently. Debug builds
keep the hooks the P03–P17 evidence runs need, so the privacy guard and the evidence apparatus do not
conflict.

## 6. Banking and DRM

The app must not:
- Bypass secure flags.
- Capture protected video.
- Scrape UI/accessibility nodes or add an Android Accessibility Service.
- Use root or privileged APIs to retrieve protected pixels.
- Send protected frames over the network.
- Cache protected frames.

Test only to verify that the OS/app protection remains effective.

## 7. Network security

- Local network only.
- Authenticated TLS.
- Bind to local interfaces only.
- No open unauthenticated port.
- Dynamic ports.
- Strict message size and type validation.
- Rate limits and connection timeouts.
- Handle network changes and stale peer addresses.
- No external telemetry.

## 8. Diagnostics

Allowed:
- timestamps,
- protocol version,
- peer pseudonymous ID,
- connection state,
- latency and frame-rate metrics,
- error codes.

Forbidden:
- clipboard text,
- screenshots/video frames,
- passwords,
- OTP values,
- authentication secrets,
- full personal identifiers.

## 9. User controls

- Pause clipboard sync.
- Turn playback audio off before sharing, or mute it on the Mac.
- Stop screen sharing.
- Disconnect.
- Forget paired device.
- Clear local app data/preferences.
- Toggle optional Bluetooth; advertise/pair only after an explicit user action and expose a clear disconnect/stop control.
- Review current permissions and active session.
