# Security and Privacy Requirements

## 1. Threat model

Protect against:
- Another device on the same Wi-Fi attempting to connect.
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

## 6. Banking and DRM

The app must not:
- Bypass secure flags.
- Capture protected video.
- Scrape UI/accessibility nodes.
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
- Stop screen sharing.
- Disconnect.
- Forget paired device.
- Clear local app data/preferences.
- Toggle optional Bluetooth.
- Review current permissions and active session.
