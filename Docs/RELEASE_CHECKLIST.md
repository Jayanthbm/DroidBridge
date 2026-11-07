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

## macOS
- [ ] Native signed app builds from clean checkout.
- [ ] Local Network usage description present.
- [ ] Bonjour service declarations correct.
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
