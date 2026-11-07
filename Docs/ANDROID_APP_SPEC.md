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
- Test `setPrimaryClip(ClipData.newPlainText(...))` in foreground and background separately.
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

- Android 17 target API 37 requires evaluation of `ACCESS_LOCAL_NETWORK`.
- Prototype NSD system picker (`FLAG_SHOW_PICKER`) first for least privilege.
- If direct LAN access is needed, declare/request `ACCESS_LOCAL_NETWORK` and handle denial/revocation.
- Do not assume `INTERNET` alone grants local LAN access.
- Do not use hard-coded IPs or ports.
- Use a registered service type and dynamic port.

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
