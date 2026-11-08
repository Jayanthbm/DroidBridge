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

### Fixed
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
