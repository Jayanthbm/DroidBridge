# Changelog

All notable project changes are recorded here.

## [Unreleased]

### Added — Phase 0 (2026-11-09)
- Android application shell: Kotlin + Jetpack Compose, Gradle Kotlin DSL, AGP 9.5.1,
  `compileSdk`/`targetSdk` 37, `minSdk` 31. Modules `app`, `core:{common,protocol,network,pairing,security}`
  and `feature:{dashboard,clipboard,mirroring,settings}`.
- macOS application shell: Swift + SwiftUI, xcodegen-generated Xcode project, App Sandbox with the
  audited three-entitlement set, menu-bar presence and a dashboard window.
- `shared/protocol` is now consumed by the Android build as a composite build, so the H.264 framing
  rules have exactly one definition.
- `scripts/env-baseline.sh`, `scripts/verify-all.sh`, `scripts/android-install.sh`.
- GitHub Actions `verify` workflow: shared protocol tests plus Android assemble/test/lint on a clean
  runner, read-only token, no secrets.
- `Docs/prototypes/P00-environment-report.md` and `Docs/prototypes/PHASE0-repository-and-environment.md`.

### Added — Phase 1, P01 (2026-11-09)
- `android/experiments/media-projection/`: isolated MediaProjection capture experiment with a
  surface-fed H.264 encoder, an encoder capability probe, and 8 framing tests. Separate
  `applicationId`, never depended on by `:app`.

### Added — Phase 1, P02 (2026-11-09)
- macOS Bonjour advertiser: `NWListener` publishing `_droidbridge-dev._tcp` with a versioned TXT
  record, an echo handler, and a live event view. The Mac half of the ADR-010 direction.

### Verified — P02 LAN discovery on the S24 Ultra (2026-11-09)
- The Android 17 system picker path works end to end on the target device: the picker appears, lists
  the Mac, resolves it to `192.168.31.109:47999`, and the connection completes a duplex echo.
- `FLAG_USER_APPROVED_ONLY` rediscovers a previously approved peer with no picker, which is what
  makes silent reconnect possible.
- **Corrected a previous conclusion:** the Mac was never blocked by Little Snitch or the firewall.
  With no security settings changed, the phone connected to both a plain listener and the real
  `DroidBridge.app` listener and completed a duplex echo.
- New experiment `android/experiments/discovery/`, a separate installable app so
  `ACCESS_LOCAL_NETWORK` cannot reach the production manifest.

### Verified — P00 target-device baseline (2026-11-09)
- S24 Ultra measured: SM-S928B, Android 17, API 37, One UI 9.0, build `CP2A.260605.016.S928BXXS6EZJ1`,
  security patch 2026-10-05, `user` build, verified boot `green`. The owner-reported specs are
  confirmed rather than assumed.

### Verified — P04 re-run on Android 17 (2026-11-09)
- The clipboard matrix is unchanged from Android 12 across six API levels, including the unfocused
  read returning nothing. The sensitive flag is now a real API-33+ extra and the clip was still read
  in full, confirming it is a preview hint rather than an access control.

### Fixed — P01 encoder configuration (2026-11-09)
- The H.264 encoder requires `KEY_LEVEL` whenever `KEY_PROFILE` is set; setting the profile alone
  made `configure()` fail with `CodecException 0x80001001`, an error that names no key. Found by a
  device-side bisect over the format keys. Capture now runs end to end: 124 frames, 359,092 bytes,
  3 keyframes, SPS 21 bytes, PPS 8 bytes.

### Verified — P04 clipboard matrix (2026-11-09)
- The full matrix ran on the device. The focus gate is confirmed: with another app in front or the
  screen off, the read returns nothing. `EXTRA_IS_SENSITIVE` does not block a read, there is no
  OS-side size cap, and nothing deduplicates — all three must be handled by DroidBridge.

### Added — Phase 1, P03 (2026-11-09)
- Length-prefixed control framing with a validated, unsigned length prefix and a 1 MiB cap.
- Short authentication code for pairing, implemented in Kotlin and Swift with shared test vectors
  proving the two agree.
- Sequenced control envelope (`ControlMessage` in Kotlin and Swift) with a version byte, message
  types, and replay rejection: a sequence that does not advance is refused, and the first frame must
  be sequence 1.
- Mutual TLS 1.3 session with SPKI pinning on both sides. A pin mismatch fails the handshake itself
  rather than warning after it.
- Pairing confirmation: both sides derive and display the same code, application data stays locked
  until the user confirms, and only then is the peer pin persisted.
- `PeerPinStore` (macOS Keychain) and `KeystorePinStore` (Android Keystore) for persistent peer pins.
- `P03Harness` (`--identity`, `--listen`, `--pair`) so the Mac half can be driven and measured
  without a UI.

### Verified — P03 on the S24 Ultra (2026-11-09)
- Physical pairing against the Mac over Wi-Fi: the same code appeared on both screens (`642 575`,
  then `614 068` after the fix below), data was locked until confirmation, and confirming persisted
  the pin before enabling data.
- Duplex data on the confirmed session; stored-pin reconnect authenticated inside the handshake with
  no code prompt; the 30 s idle deadline closed sessions twice, observed rather than inferred.
- A restarted Mac with a new identity was refused by the phone (`peer pin mismatch`), which is the
  correct behaviour for a changed peer.

### Fixed — P03 defects found only on hardware
- The Android identity key permitted only SHA-256 digests, so Keystore refused Conscrypt's
  `NONEwithECDSA` CertificateVerify and no client certificate was ever sent. The key now also
  permits `DIGEST_NONE`. No build or unit test could have caught this.
- The macOS verify block captured the expected pin at listener start, so a listener that began
  unpinned kept accepting "first pairings" after a confirmation and then granted data access because
  a pin existed. The pin is now read at handshake time, and `connectionReady` independently refuses
  a mismatched pin.
- Withdrew an unsupported claim that a live TLS handshake had been captured. The quoted bytes were
  not a TLS record and no command producing them existed; the record now states what was measured.

### Added — Phase 1, P04 (2026-11-09)
- `android/experiments/clipboard/`: clipboard read-access probe with one action per P04 matrix row
  and a greppable observation line. Declares no permission, by design.

### Fixed
- The clipboard probe escaped its preview; a newline in clipboard content would have split one
  observation across two lines and corrupted the evidence log.
- The advertiser model is owned by the app rather than a tab view; SwiftUI builds `TabView`
  content lazily, so it was never constructed and `--advertise-on-launch` did nothing.
- `shared/protocol` now targets Java 17 to match its Android consumer; the Java 21 toolchain made
  every Android unit test fail with `UnsupportedClassVersionError` (ADR-032).
- Release macOS builds no longer carry `com.apple.security.get-task-allow` (ADR-031).

### Changed
- Android manifest sets `allowBackup=false` with all backup and device-transfer domains excluded.
- `.gitignore` extended for lint reports, provisioning profiles and local agent state.

### Earlier
- Initial product requirements and constraints.
- Android and macOS app specifications.
- Research and feasibility register.
- System architecture and local protocol draft.
- Security, privacy and permissions policies.
- Prototype roadmap and test plan.
- AI agent workflow and phase acceptance criteria.
