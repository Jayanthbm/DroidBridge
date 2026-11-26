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
- [x] Pairing code verification.
- [x] TLS and peer authentication.
- [x] Revoke peer tested.
- [ ] No clipboard/screen content in logs.
- [ ] No cloud endpoints.
- [ ] No protected content bypass.
- [ ] No secrets in repository.

## Final no-debugging acceptance
- [x] USB cable disconnected.
- [x] USB debugging disabled.
- [x] Wireless debugging disabled.
- [x] Bluetooth disabled and core features still work.
- [x] Both devices on same Wi-Fi.
- [x] Pairing and reconnect tested.
- [x] Clipboard sync tested.
- [x] Screen mirroring tested.
- [x] Secure-screen behaviour tested.

Run 2026-11-26 on the S24 Ultra (`RZCY81TKBSV`, SM-S928B, Android 17, debug APK 0.1.0) against the
Debug Mac app from `macos/build`, device-side steps performed by the owner at the phone. State verified
at each row with `adb devices`: only `192.168.31.64:5555` (the OnePlus, untouched) was present, so the
S24 was not on USB and had no debug transport; Developer options and USB debugging were off on the phone
and wireless debugging was off. Bluetooth off throughout. Blocker found during the run and fixed before
it closed: the Mac closed the connection when the phone confirmed first (three defects, D1/D1a/D2 in
`Docs/PROGRESS_LOCAL.md`, ADR-060); the pairing row was re-run on the fixed build and passed with the
phone confirming first. Evidence: `Docs/PROGRESS_LOCAL.md` (2026-11-26 sweep entry),
`Docs/prototypes/P08-live-mirroring.md`, `Docs/prototypes/P10-secure-screen-protection.md`.
Signing: the APK is the debug build - release signing is Phase 9 and not part of this acceptance.
