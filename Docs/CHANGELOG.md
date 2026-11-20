# Changelog

All notable project changes are recorded here.

## [Unreleased]

### Changed — P14 re-run on the S24 Ultra: background session stays alive (2026-11-20)
- On the **S24 Ultra (Android 17)** the same build kept the session alive while backgrounded for ten minutes, with the screen off, across a Wi-Fi off/on cycle, and across a real reboot (`BOOT_COMPLETED` reconnects within 450 ms). The OnePlus freeze recorded earlier is an OEM deviation, so ADR-023's premise holds on the target device and ADR-043 is revised: background sync is allowed on this device class, with stalled-session detection and foreground-only degradation elsewhere.
- **API 37 requires `ACCESS_LOCAL_NETWORK`.** Without it every LAN connection failed as `SocketTimeoutException` — indistinguishable from a dead listener; the experiment now declares and requests it.
- Fixed a watchdog that replaced a healthy worker during a genuine Wi-Fi outage; the stall check now applies only while the session claims to be connected. Re-verified with 0 false stalls.
- Still unmeasured: deep Doze and overnight (the handset was charging, so `force-idle` was refused), notification dismissal and battery drain. See [P14](prototypes/P14-background-persistence.md).

### Changed — P14 measured; background sync is foreground-only by default (2026-11-20)
- Measured the P14 matrix on the OnePlus. Foreground start, `BOOT_COMPLETED` start (reconnects within 500 ms of the broadcast) and foreground Wi-Fi-toggle recovery all pass; a background start is rejected once the post-visibility grace window has passed, with the exact `ForegroundServiceStartNotAllowedException`.
- **Backgrounding the app freezes the process** and stops the session even though the platform accepts the service as a foreground service (`fg +50 … state: cur=FGS`), with `/proc/<pid>/cgroup` showing `6:freezer:/frozen`; foregrounding thaws it and the heartbeat resumes. Recorded as ADR-043 + R-37: background clipboard sync is **foreground-only by default** until the S24 Ultra is measured, and no battery-optimisation exemption or wake lock will be added to force it.
- Fixed two defects found while running the matrix: a start with a new host/port was ignored while a session ran (the whole run kept a stale target), and the notification was updated inside the synchronised log write, deadlocking the worker against the main thread so the session stalled while the notification still claimed "connected". Notification updates now run on a main-thread supervisor, the notification shows the age of the last heartbeat, and a watchdog restarts a worker that has not beaten for 50 s.
- Doze, overnight, notification-dismissal and battery rows remain unmeasured. See [P14](prototypes/P14-background-persistence.md).

### Added — P14 background session experiment (2026-11-20)
- Added an isolated `connectedDevice` foreground-service experiment that reuses the production TLS session and pin store, heartbeats a real `PING`/`PONG` every 15 s, reconnects with backoff, and shows a persistent notification with a Disconnect action. No wake lock, no battery-optimisation exemption, no Bluetooth permission.
- Measured: foreground start succeeds with no runtime prompt; a background start is accepted 6 s after backgrounding (post-visibility grace) and rejected at 30 s with `ForegroundServiceStartNotAllowedException`. `BOOT_COMPLETED`, the survival matrix, Doze, notification and battery tests remain outstanding — the reboot left the handset off Wi-Fi and out of ADB reach. See [P14](prototypes/P14-background-persistence.md).

### Added — P17 stability, reconnect and clean shutdown (2026-11-20)
- Measured a **48-press burst** in one HID session: `+12` per arrow direction, so every report was delivered exactly once with no drops or duplicates.
- Fixed shutdown leakage: the app now closes both L2CAP channels, closes the ACL and withdraws its HID SDP record from `applicationShouldTerminate`. Quitting while connected previously left a Mac-side stale session that refused the next connection with `0xE00002BC` until the phone's Bluetooth was cycled; an immediate relaunch now reconnects and delivers input.
- Recorded the remaining limitation: after a force-quit or crash, one phone Bluetooth adapter cycle is still required. Evidence: [p17 transcript](prototypes/evidence/p17/README.md).

### Added — P17 Classic HIDP delivers input from DroidBridge's own app (2026-11-20)
- **DroidBridge's own implementation now delivers HID input to Android.** `P17ClassicHIDP` published the HID SDP record (`handle 1330188580`), applied Class of Device `0x2540`, opened L2CAP control `0x0011` and interrupt `0x0013`, and moved a focused receiver's counters from zero to `UP 2, DOWN 1, LEFT 1, RIGHT 1`, with Android reporting `STATE_CONNECTED` for `Jayanth's MacBook Air [BR/EDR]`. The Classic route no longer depends on the AGPL probe.
- Added a numeric-comparison pairing panel that displays the code for manual verification and answers only on explicit **Codes match — Accept**, plus a manual *Connect / Pair* control with an address field — the previous build had no way to answer the comparison at all.
- Fixed a main-thread deadlock in `IOBluetoothDevice.pairedDevices()` by warming IOBluetooth up off the main thread and refreshing peers on a background queue.
- Documented three environment constraints that masquerade as HIDP faults: macOS 26's `kTCCServiceBluetoothAlways` consent (unconsented calls surface as `0xE00002BC`/`0xE00002D6`), the single Classic pairing-agent slot that made programmatic pairing fail `0x0000001F` while another app held it, and Android retaining the previous HID link after the Mac app exits. See [ADR-042](TECHNICAL_DECISIONS.md#adr-042--treat-macos-bluetooth-consent-the-single-pairing-agent-slot-and-one-hid-session-per-connection-as-environment-constraints) and R-36.
- Not proven: S24 Ultra, a pass with both debugging modes disabled, reconnect/relaunch/bond-recovery, sustained typing, and the app's own programmatic pairing. Evidence: [P17](prototypes/P17-classic-hidp.md), [run transcript](prototypes/evidence/p17/README.md).

### Added — independent P17 Classic HIDP implementation and BLE NO-GO result (2026-11-20)
- Recorded **macOS BLE HOGP → Android as NO-GO**. Android bonds the Mac but never creates a HID input device (`HidHostService.mInputDevices` empty, `mTargetDevice: null`) and the Mac receives no GATT traffic. The unmodified upstream BLE app behaves identically, so this is Android's `[ DUAL ]` transport routing of the Mac's shared public BR/EDR address, not a GATT-layout defect.
- Recorded **Classic HIDP as a preliminary GO on OnePlus Nord AC2001 / Android 12 only**: SDP HID record published, L2CAP `0x0011`/`0x0013` opened from the Mac, four Android input devices registered, and arrow press/release pairs counted by a focused receiver with USB disconnected and wireless debugging off.
- Added an **independently authored** `P17ClassicHIDP` macOS experiment (public `IOBluetooth` only: SDP publication, outbound ACL, L2CAP control/interrupt, HIDP transaction handling, `0xA1` input reports). No third-party implementation source is included, and the earlier AGPL-derived comparison artifact was removed before commit.
- The independent POC publishes its SDP record and opens the ACL, but its control channel currently fails (`0xE00002BC`) against a dozing handset; key delivery from this app is **not yet demonstrated**. Corrected an earlier research note: upstream's Classic path uses only public API, and its `_privateBool` KVC helper is dead code.
- See [P17](prototypes/P17-classic-hidp.md), [P16](prototypes/P16-ble-hid.md), [ADR-041](TECHNICAL_DECISIONS.md#adr-041--route-mac-to-android-hid-input-through-classic-hidp-not-ble-hogp) and R-35.

### Updated — P16 BLE HID UUID registration retest pending (2026-11-20)
- Initial P16 CoreBluetooth publication returned the UUID-not-allowed message. Review of the linked repo found it registers standard GATT services/characteristics using canonical Bluetooth-base UUIDs and uses short form only for advertising. The isolated P16 implementation now applies that distinction independently; its Mac build and signature verification pass, but the user must manually retry service publication.
- No advertising, pairing or phone-side input test has happened; OnePlus/S24 compatibility remains unknown. Do not substitute a custom UUID. P17 Classic HIDP remains a separate pending route. See [P16 evidence](prototypes/P16-ble-hid.md), [ADR-038](TECHNICAL_DECISIONS.md#adr-038--register-sig-gatt-uuids-in-canonical-bluetooth-base-form-for-p16-retest) and R-33.

### Planned — isolated Bluetooth HID control feasibility POCs (2026-11-17)
- Reviewed `jqssun/darwin-bt-remote`: it implements macOS BLE HID-over-GATT and Bluetooth Classic HIDP; its Samsung pairing report remains open and does not prove S24 Ultra compatibility. Its optional Accessibility-based Direct Input path is explicitly excluded, and no AGPL source is copied.
- Added P16/P17 plans for independent target-device BLE/Classic HID tests, plus explicit guardrails that ADB/debugging cannot generate input or carry screen/clipboard data. Existing MediaProjection mirroring and foreground/manual clipboard POCs remain separate; no feature is claimed complete.
- Accessibility remains prohibited. See R-32, ADR-036 and P16/P17 in `Docs/PROTOTYPE_PLAN.md`.

### Changed — macOS bundle identifier and local app cleanup (2026-11-17)
- Changed the macOS bundle ID to `com.droidbridge.app` and the test bundle ID to `com.droidbridge.app.tests`; Android's `applicationId` remains unchanged.
- Regenerated the Xcode project, passed 67/67 macOS tests and the Release build, and verified the canonical `~/Applications/DroidBridge.app` signature, embedded ID/entitlements, persistent identity pin, and standalone Bonjour resolution.
- Removed only the specifically inventoried old `.app` bundles. Preserved their enclosing Xcode/build cache directories, app settings, Keychain items, and repository contents. See [ADR-035](TECHNICAL_DECISIONS.md#adr-035--use-comdroidbridgeapp-for-the-macos-bundle-identifier) and [P15](prototypes/P15-signed-build.md).

### In progress — P11 macOS Local Network permission UX (2026-11-17)
- Added user-facing Local Network permission guidance and an Open System Settings action when Bonjour advertising fails.
- On macOS 27.2.1, the ad-hoc Release app advertised `_droidbridge-dev._tcp`; `dns-sd` browsed and resolved the service and its TXT record. This confirms the current effective allowed state only.
- First-launch prompt wording and denied-state recovery remain unverified; native UI inspection was unavailable, and no TCC reset or settings change was made. macOS suite: 67/67; Release build succeeded. See [P11](prototypes/P11-local-network-permission.md).

### Changed and verified — P08/P10 receiver investigation (2026-11-17)
- Replaced the macOS wall-clock one-second frame counter with monotonic bounded-burst token buckets; the sustained media/control limits remain. Media events are summarized instead of emitted once per frame, and receiver UI counters publish at a bounded cadence.
- Hardened H.264 access-unit parsing and stream sequencing: signed PTS decode, AVCC/NAL validation and IDR detection, monotonic PTS within each format epoch, IDR after stream/format boundaries, IDR-gated decoder recovery, and local renderer errors separated from invalid peer traffic.
- Android now drops non-increasing encoder timestamps within a format epoch (reset on rotation/new format) and reattaches the activity to the process-held session after recreation.
- Automated tests: macOS 65 passed; Android mirroring 22 passed and Debug APK assembled.
- One live S24 run showed the notification shade in the Mac mirror and remained active through dismissal; the historical freeze remains unexplained and no general fix is claimed. The public-version synthetic notification showed redacted text on both screens. The private-only result remains inconclusive. Later user-reported unlocked/unplugged attempts ended after the phone locked or mirroring stopped; they do not establish an awake stability run or resolve the private-only result. USB/debugging state and detailed evidence are recorded in the P08/P10 reports.

### Verified locally — P15 personal-use identity persistence (2026-11-17)
- Implemented app-specific, versioned login-Keychain identity storage. Two separate Release `--identity` processes reported the same pin. Isolated disposable-Keychain tests verify persistence and fail-closed handling of an orphan key; full macOS suite passes 67/67, and the Release build succeeds.
- Release signature audit remains pass: ad-hoc with hardened runtime, exactly the sandbox/network-client/network-server entitlements, and no `get-task-allow`. Gatekeeper rejects the unnotarized ad-hoc app as expected. Android reconnect without re-pairing and Keychain ACL behavior across code-signing changes remain untested. The earlier generated key item remains untouched. Developer ID signing, notarization, stapling and quarantined distribution checks are deferred by the owner while personal-use only; see [P15](prototypes/P15-signed-build.md), [ADR-033](TECHNICAL_DECISIONS.md#adr-033--defer-macos-developer-id-distribution-while-droidbridge-is-personal-use-only) and [ADR-034](TECHNICAL_DECISIONS.md#adr-034--persist-personal-use-mac-identity-in-the-login-keychain-until-a-profile-is-available).

### Fixed — P08 receiver identity (2026-11-12)
- The macOS receiver minted a new identity every time Listen was pressed, so the phone's stored pin
  went stale on each restart: reconnecting always meant Forget stored pin plus a fresh six-digit
  comparison. One identity is now held for the life of the app run, so a reconnect authenticates
  from the stored pin (`authenticated=true`, no re-pairing). Persistent identity storage remains
  P15 scope.

### Verified — P10 password case, shade defect (2026-11-12)
- The password test field (`password="true"`) was driven with throwaway text during a live session and
  the mirrored pixels were captured: the mirror shows mask dots, never the typed characters. Recording
  them at all needed `--p08-allow-capture`, because the display layer's `preventsCapture` made every
  `screencapture` of the video area black.
- Opening the notification shade freezes the mirrored picture on the last pre-shade frame, and the
  receiver then closes the session with `peer exceeded 120 control frames per second` (twice, fresh
  sessions). This withdraws the earlier "shade streams normally" claim, which had been inferred from
  window focus and a rising frame counter rather than from the picture.
- Both synthetic OTP notification variants remain confirmed at the notification service (the
  `setPublicVersion()` one exposes only `Synthetic notification (no code shown)`, the other has no
  public version), but the shade freeze blocks observing either of them in the mirror.
- Locking the device continues to revoke the projection instead of exposing the keyguard.

### Verified — P08 end-to-end mirroring on the S24 Ultra (2026-11-12)
- Rotation mid-session re-encoded `590x1280` → `1280x590` with a fresh `mirror.format`, and the Mac
  swapped its format description with the render queue staying at zero frames.
- Keyframe recovery over the real transport: flush plus keyframe request reached the phone
  (`keyframeRequests=1`, `requesting an IDR`) and the stream resumed without drops. The corrupt
  sample used to provoke decoder failure did not flip the renderer, so that precondition is
  recorded as unproven rather than passed.
- Stop and screen-off both ended the capture (`user stop` / `system onStop`), with
  `dumpsys media_projection` returning `null` and no service left behind.
- 30 m 43 s continuous session: 9118 frames, `dropped=0` and `received == enqueued` at every
  sample. Static-screen cost with `KEY_REPEAT_PREVIOUS_FRAME_AFTER`: 4.6–5.1 fps, 244–321 kbit/s.
- Locked-screen stop: with the keyguard showing, the projection was revoked immediately and the Mac
  received `MIRROR_STOP` with the system reason; no service or projection record was left behind.
- Idle cost measured on the same static screen: 3.7 fps / 342 kbit/s with
  `KEY_REPEAT_PREVIOUS_FRAME_AFTER`, 0.39 fps / 103 kbit/s without it (experiment switch
  `--ez omitRepeatFrame true`), and keyframes stop advancing when nothing is encoded.
- Still open, so P08 is not complete: the run with USB and wireless debugging both off, and
  glass-to-glass latency plus CPU/GPU/battery/thermal numbers.

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

### Verified — P03 Wi-Fi drop and reconnect on the S24 Ultra (2026-11-09)
- A real network drop was tested, not a close-and-redial: `cmd wifi set-wifi-enabled disabled` took
  `wlan0` to `NO-CARRIER` with no address and 100% packet loss to the Mac.
- Both ends terminated. The phone closed on write (`session closed: send failed: SocketException`);
  the Mac closed on its 30 s idle deadline. Neither hung or kept a dead session marked authenticated.
- After Wi-Fi was restored the phone rediscovered the Mac and reconnected with the stored pin alone:
  `authenticated=true`, Mac `AUTHENTICATED (stored pin matched; no user action needed)`. No
  re-pairing, no code comparison, and a ping on the new session was delivered.
- Reconnect is manual: nothing re-establishes the session automatically, and the listening side
  learns of a drop only by its idle deadline. Recorded as a limitation, not a pass.

### Verified — P03 final acceptance, USB disconnected (2026-11-09)
- The acceptance run required by `PHASES_AND_ACCEPTANCE.md` passed with **no development transport
  available**: USB disconnected, USB debugging off, wireless debugging off, Developer Options off.
  `adb devices` listed nothing for the whole run.
- A TLS 1.3 mutually authenticated session carried application data: both screens showed `996 498`,
  compared by the owner, then `FRAME type=ping seq=1 payload-bytes=12` on the Mac.
- First run where the phone UI was operated by a person rather than by `adb shell input tap`, so the
  two-screen code comparison is real evidence rather than a simulation.
- Two findings recorded, not papered over: a 30 s idle deadline drops a session a user pauses in, and
  restarting the harness to extend that deadline minted a new Mac identity (ADR-025), forcing a
  second re-pair.

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
