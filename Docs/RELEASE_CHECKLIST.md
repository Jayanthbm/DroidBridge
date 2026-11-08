# Release Checklist

## Product
- [ ] Android and macOS app versions aligned.
- [ ] Clipboard sync works with documented limitations.
- [ ] Screen mirroring works with explicit MediaProjection consent.
- [ ] Remote input claims match proven capabilities.
- [ ] Protected content is never bypassed.
- [ ] Bluetooth remains optional.

## Android
- [ ] Release build installs without ADB.
- [ ] No Accessibility service.
- [ ] No root requirement.
- [ ] No USB/wireless debugging runtime dependency.
- [ ] Manifest permission audit passed.
- [ ] Android 17 local network permission/picker behaviour documented.
- [ ] MediaProjection foreground service and stop callback tested.
- [ ] Clipboard foreground/background behaviour documented.
- [ ] Battery/performance test passed.
- [ ] `targetSdk` at or above Play's current floor (API 36 since 2026-08-31; 37 is planned).
- [ ] AAB built and signed with the upload key, Play App Signing configured.
- [ ] Play Console foreground-service declarations match the manifest exactly, each with a description, impact statement, use case and demo video.
- [ ] `ACCESS_LOCAL_NETWORK` Play declaration submitted — only if that permission actually shipped.
- [ ] Store description states the core functionality of any declared restricted permission.

## macOS
- [ ] Native signed app builds from clean checkout.
- [ ] Local Network usage description present.
- [ ] Bonjour service declarations correct.
- [ ] Developer ID Application signature, hardened runtime and secure timestamp on every code item; signed inside-out, never with `codesign --deep`.
- [ ] No `get-task-allow` entitlement in the shipped build.
- [ ] Notarisation accepted and the ticket stapled; app launches from a clean Mac without an "Open Anyway" override.
- [ ] Shipped entitlements are exactly `app-sandbox`, `network.client`, `network.server` and the keychain access group; any embedded provisioning profile is a distribution profile.
- [ ] Local Network prompt appears on first advertise, and a denied state is handled with a link to System Settings.
- [ ] Keychain usage reviewed.
- [ ] No unnecessary Screen Recording/Accessibility/Input Monitoring permissions.
- [ ] Viewer handles disconnect and rotation.

## Security
- [ ] Pairing code verification.
- [ ] TLS and peer authentication.
- [ ] Revoke peer tested.
- [ ] No clipboard/screen content in logs.
- [ ] No cloud endpoints.
- [ ] No protected content bypass.
- [ ] No secrets in repository.

## Final no-debugging acceptance
- [ ] USB cable disconnected.
- [ ] USB debugging disabled.
- [ ] Wireless debugging disabled.
- [ ] Bluetooth disabled and core features still work.
- [ ] Both devices on same Wi-Fi.
- [ ] Pairing and reconnect tested.
- [ ] Clipboard sync tested.
- [ ] Screen mirroring tested.
- [ ] Secure-screen behaviour tested.
