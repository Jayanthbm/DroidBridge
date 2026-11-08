# Android Application Specification

## 1. Target

- Initial device: Samsung Galaxy S24 Ultra, India region, Android 17 / One UI 9 (owner-reported).
- Native Kotlin.
- Jetpack Compose.
- Android Studio / Gradle Kotlin DSL.
- Compile/target SDK must be verified against the installed stable SDK and current Android 17 documentation. Android 17 is API 37 in the current research baseline.
- Do not hard-code an SDK version based solely on this document; verify before build.

## 2. App modules

- `app`: application shell and navigation.
- `core:common`: models, result types, logging redaction.
- `core:protocol`: message definitions and validation.
- `core:network`: local socket and service discovery.
- `core:pairing`: pairing and trusted peer storage.
- `core:security`: key management and TLS identity.
- `feature:clipboard`: foreground/manual clipboard workflow.
- `feature:mirroring`: MediaProjection lifecycle and foreground service.
- `feature:settings`: sync policies and connection preferences.
- `experiments:*`: isolated experiments only.

## 3. Screens

1. Welcome / privacy explanation.
2. Dashboard: Mac peer, Wi-Fi state, connection state, Clipboard Sync, Screen Mirroring.
3. Pairing: discover/select Mac, verify code, approve trust.
4. Clipboard: current sync state, Send clipboard, Pause, last action metadata (never content by default).
5. Mirroring: start/stop projection, quality, session state, stop instructions.
6. Settings: Bluetooth optional toggle, clipboard policy, trusted devices, clear pairing, privacy.
7. Diagnostics: redacted logs and export without clipboard/screen payloads.

## 4. Clipboard behaviour

- Foreground/manual send is the initial guaranteed workflow.
- Use `ClipboardManager.getPrimaryClip()` only when allowed by Android focus/default-IME rules.
- Test `setPrimaryClip(ClipData.newPlainText(...))` in foreground and background separately; the system clipboard preview/copy UI is expected and must not be suppressed (R-09).
- Mark clips written on behalf of a sensitive source with the `ClipDescription` sensitive flag so Android 13+ does not preview their content.
- Never require default IME.
- Never add Accessibility.
- No clipboard history by default.
- No automatic OTP extraction or SMS reading.
- Provide maximum text length and optional sensitive-content policy.
- Avoid syncing empty clips and duplicate content.
- Respect Android clipboard system UI and sensitive clip metadata.

## 5. Mirroring

- Use MediaProjection with explicit system consent.
- Use a dedicated foreground service with `mediaProjection` type and required permissions.
- Request consent for each new session as required.
- Use MediaCodec hardware encoder where available.
- Register MediaProjection.Callback and release resources on stop.
- Show clear in-app state matching the Android system projection indicator.
- Stop streaming on projection stop, screen lock, user stop, peer disconnect or app error.
- Support display rotation and size changes.
- Never attempt to capture around secure surfaces.

## 6. Local network

- Android 17 target API 37 requires `ACCESS_LOCAL_NETWORK` **or** a picker grant (R-01).
- The app does not advertise: the macOS app advertises and the Android app selects it with `DiscoveryRequest.FLAG_SHOW_PICKER` and connects outbound, so no broad LAN permission is needed (ADR-010).
- Declare/request `ACCESS_LOCAL_NETWORK` only if the P02 prototype disproves the picker path, and handle denial/revocation explicitly.
- Do not assume `INTERNET` alone grants local LAN access on API 37.
- Do not use hard-coded IPs or ports; re-resolve through `registerServiceInfoCallback()` instead of caching addresses.
- Use the service type recorded in `NETWORK_PROTOCOL.md` (`_droidbridge-dev._tcp` during development) and a dynamic port.

## 6A. Device identity and TLS

- Generate a per-install identity in the Android Keystore: EC P-256, purposes including `PURPOSE_SIGN`, subject/serial/validity set through `KeyGenParameterSpec` (ADR-024, R-22). The Keystore produces the self-signed certificate; read it back with `KeyStore.getCertificate(alias)`.
- A key without `PURPOSE_SIGN` yields a certificate whose signature does not verify — assert verification in a unit test rather than assuming it.
- Present the identity through an `SSLContext` built on a `KeyManager` bound to that alias; on the accepting side require the peer certificate with `setNeedClientAuth(true)`.
- Verify the peer by pinned SHA-256 of its SubjectPublicKeyInfo in a dedicated `X509TrustManager`. Do not add a static `<trust-anchors>` entry (the peer is unknown at build time) and do not disable certificate transparency, endpoint identification or hostname checks as a workaround — the peer is an IP literal and identity comes from the pin.
- The private key is non-exportable, so a device change requires re-pairing. Surface a key change as "device identity changed", never as a retryable network error.

## 7. Bluetooth optional module

Only enabled after user opt-in.
- Request only permissions needed by the chosen Bluetooth role.
- Never request location unless a documented API path truly requires it.
- Test role directionality.
- Android BluetoothHidDevice API makes Android the HID peripheral, not the Mac-to-phone peripheral.
- Do not add Bluetooth permissions to the core app if the feature is disabled/excluded from MVP.

## 8. Permission minimization

Every manifest permission must have:
- Feature owner.
- User-facing reason.
- API-level requirement.
- Runtime/normal/signature classification.
- Whether optional.
- Test proving it is needed.

No broad permissions for speculative future features.

## 9. Play submission

- `targetSdk 37` is above Play's floor (API 36 since 2026-08-31). Re-check the floor at every release — it moves each August — and never drop below it (R-25).
- Ship as an **AAB** signed with the upload key, with Play App Signing enabled (R-25).
- Declare every foreground-service type in Play Console exactly as the manifest declares it, each with a description, the impact if the task is deferred or interrupted, the preset use case, and a demo video (R-26):
  - `connectedDevice` → "Continuous Data Transfer to an External Device". The video must show app-led setup, so it starts in DroidBridge's own UI and ends on an in-app connected state with the clipboard or background sync visibly working. It must not look like a System Settings walkthrough.
  - `mediaProjection` → media/content projection, showing the user starting a projection to the Mac.
- The `connectedDevice` video carries a known review risk because pairing happens in the **system NSD picker**. The declaration description must state that the picker is used deliberately as the least-privilege Android 17 alternative to `ACCESS_LOCAL_NETWORK` (U15).
- Submit the `ACCESS_LOCAL_NETWORK` declaration and a matching store-description justification only if that permission actually ships; the picker-first design is chosen partly to avoid both (R-25).
- Data safety form: no data collected or shared — no cloud endpoint, no telemetry, no analytics SDK. Keep that true in code, not only in the form.
