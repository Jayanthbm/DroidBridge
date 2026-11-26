# Technical Decision Log

Use newest entries at the top. Every change must include date, status, context, decision, alternatives and consequences.

## ADR-043 — Do not claim background clipboard sync until a `connectedDevice` FGS is shown to survive backgrounding on the target device

**Date:** 2026-11-20
**Status:** Accepted and revised with S24 Ultra evidence. Measured on S24 Ultra `SM-S928B` / Android 17 / API 37 (target) and OnePlus Nord AC2001 / Android 12 (secondary).
**Context:** P14 built an isolated `connectedDevice` foreground service that reuses the production TLS session and sends a real `PING`/`PONG` every 15 s. On the OnePlus, after the app left the foreground the worker stopped beating within ~40 s. The process remained alive with `isForeground=true`, and `dumpsys activity oom` reported `fg +50 F/S/FGS` with `state: cur=FGS set=FGS`, i.e. the platform accepted the service as a foreground service; yet `/proc/<pid>/cgroup` showed `6:freezer:/frozen`. Bringing the activity forward moved it to `freezer:/thaw` and beats resumed, and backgrounding again returned it to `/frozen`, so the freeze is caused by leaving the foreground and not by a session bug. Tests 1–3 and the foreground Wi-Fi-toggle case passed: foreground start needs no runtime prompt, a background start 30 s after backgrounding is rejected with `ForegroundServiceStartNotAllowedException`, `BOOT_COMPLETED` starts the service and it reconnects within 500 ms, and a Wi-Fi off/on cycle reconnects in one backoff cycle.
**Revised context:** On the S24 Ultra the same build kept the session alive exactly as ADR-023 predicts: `/proc/<pid>/cgroup` stayed `5:freezer:/` for ten minutes backgrounded (`HEARTBEAT n=3 → n=44`), five minutes with `mWakefulness=Dozing` (`n=6 → n=26`), a Wi-Fi off/on cycle, and a real reboot (`BOOT_COMPLETED` → connected within 450 ms). The OnePlus freeze is therefore an OEM deviation on a non-target device, not a property of `connectedDevice`.

**Decision:** On the target device class, background clipboard sync via a `connectedDevice` foreground service is **allowed**, because the premise now holds there. Treat "keeps the session alive while backgrounded" as **device-dependent**: the app must detect a frozen/stalled session (heartbeat age, not just socket state), degrade to foreground-only behaviour where the OEM freezes a valid FGS, and never present a stale session as reachable. Do not introduce a battery-optimisation exemption or wake lock to force liveness on either device class.
**Alternatives:** Require `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (rejected: ADR-023 already rules it out as unjustifiable for a consumer app); hold a wake lock (rejected: same, and it does not stop the freezer); use `dataSync` instead of `connectedDevice` (rejected: capped at 6 h/24 h and restricted from boot); ship background sync as foreground-only and say so (chosen).
**Consequences:** Background sync may ship for devices that behave like the S24, but the design must assume some devices will freeze the process: the notification must show heartbeat age, a watchdog must recover a stalled worker, and product copy must not promise liveness it cannot guarantee. Deep Doze (unplugged) and overnight rows are still unmeasured on both devices, and P14's remaining rows stay open. Measurement procedure must record the process cgroup state alongside heartbeat counts, or an OEM freeze is easily misread as a protocol failure. Any API 37 build that talks to a LAN address must declare `ACCESS_LOCAL_NETWORK`; without it every connection fails as `SocketTimeoutException`, which looks like a dead listener.

## ADR-044 — Do not depend on macOS Local Network consent, and never measure it from a script

**Date:** 2026-11-21
**Status:** Accepted, measured on macOS 27.2.1 (`26B131`).

**Context:** P11 assumed a macOS "Local Network" prompt would gate the product app's Wi-Fi path.
Measurement contradicted that on the target OS: a sandboxed app with the production entitlement set
(§P11 report) browsed Bonjour services and dialled a LAN address successfully, **with no alert and
without ever appearing in Settings → Privacy & Security → Local Network**, across four launch paths
(shell child, LaunchServices, Finder double-click, `launchd` agent) and two install locations. The
product app never triggered the control at all in earlier work because it only advertises and accepts
inbound connections.

A methodology trap was also found: macOS attributes a local-network operation to the *responsible
process*, and TN3179 auto-allows command-line tools run from Terminal and their children. Every
permission measurement taken by launching the app from a script therefore measured **Terminal's
exemption**, including the original P11 evidence.

**Decision:**
1. No product behaviour, copy or acceptance criterion may depend on the user granting Mac-side local
   network consent, and no claim may state that no such prompt exists. Enforcement is an OS-version
   property measured only on 27.2.1.
2. Keep the declarations and entitlements (`NSLocalNetworkUsageDescription`, `NSBonjourServices`,
   `network.client`, `network.server`): required if any build enforces the control, inert otherwise.
3. Keep `.waiting` handling and the "Open System Settings" recovery affordance in the app, because
   TN3179 warns the system may deny an operation before the user answers, and a future OS may enforce
   what this one does not.
4. Any future permission measurement must launch the app through Finder or a `launchd` agent, never
   from a shell or via `open` from a shell, and must state the launch path in the report.

**Consequences:** P11's denied-state UX is unreachable on this OS build and is recorded as such rather
than as a pass or a gap. iOS keeps enforcement, so nothing here transfers to an iOS client. If a later
macOS build starts enforcing, the report must be re-run — the harness (`macos/Experiments/P11LocalNetwork/`)
exists for exactly that, and the failure path it would exercise is already implemented.

## ADR-042 — Treat macOS Bluetooth consent, the single pairing-agent slot and one HID session per connection as environment constraints

**Date:** 2026-11-20
**Status:** Accepted; verified against macOS 27.2.1 (build `26B131`) and OnePlus Nord AC2001 / Android 12.
**Context:** Bringing up DroidBridge's independent Classic HIDP app surfaced four host-side constraints that each presented as a HIDP or pairing failure but were none of them:
1. macOS 26 gates IOBluetooth behind the `kTCCServiceBluetoothAlways` privacy permission. Until it is granted, every call blocks and the app reports `L2CAP control channel (0x0011) failed (0xE00002BC)` or `openConnection failed (0xE00002D6)`; `tccd` logs `AUTHREQ_PROMPTING`. Re-signing an app with an ad-hoc identity invalidates its grant (`Failed to match existing code requirement`), while an Apple Development identity keeps it across rebuilds.
2. `IOBluetoothDevice.pairedDevices()` performs IOBluetooth's first-use CoreBluetooth coordinator initialisation, which waits on a semaphore signalled from the main thread. Calling it on the main thread deadlocks; calling IOBluetooth from two threads at once makes them wait on each other's `dispatch_once`.
3. macOS accepts exactly one Classic pairing agent. `IOBluetoothDevicePair` registers first with programmatic pairing disabled, then with it enabled, and the second registration fails with `Classic pairing agent already exist` while another app (here CleanMyMac4's `bluetooth-device-fetch-xpcservice`, re-registering about once per second) holds the slot. The numeric-comparison callback still arrives, but `replyUserConfirmation(_:)` is never delivered and the bond fails with `0x0000001F` after the peer's SSP timeout, with `BT_ERROR_AUTHENTICATION_FAILURE` on the Mac side.
4. An app that exits without withdrawing its HID SDP record and closing its L2CAP channels leaves a **Mac-side** stale session: the next launch is refused with `0xE00002BC` even though Android already reports `STATE_DISCONNECTED`, the lock persisted at least 8 minutes, and only cycling the phone's Bluetooth adapter cleared it. Separately, if the phone removes its half of a bond while the Mac keeps its record, the Mac reuses a dead link key and there is no public API to delete the local record (`IOBluetoothDevice` exposes only `removeFromFavorites`).
**Decision:** Record these as documented environment constraints rather than defects to engineer around. Verify the Bluetooth privacy grant before diagnosing any IOBluetooth error code; never make the first IOBluetooth call on the main thread; treat pairing as an OS-level concern performed by the system or initiated by the peer, with phone-initiated bonding as the supported path when a programmatic agent cannot be registered; send every report of a run over one control/interrupt pair; withdraw the SDP record and close both channels from `applicationShouldTerminate` so an ordinary quit releases the session (a force-quit or crash still needs one phone Bluetooth cycle); and require "Forget This Device" on the Mac as a manual prerequisite when the peer dropped its half.
**Alternatives:** Add JIT or Accessibility permissions to drive the system pairing dialog (prohibited and unnecessary); use private `IOBluetooth`/XPC interfaces to remove local link keys or force the agent slot (prohibited by the repository's hidden-API rule); require the owner to disable third-party apps permanently (rejected: not a product requirement); retry reconnects aggressively (rejected: the peer's own timeout governs).
**Consequences:** HIDP feasibility must be judged only after the consent grant is confirmed, otherwise false-negative results are recorded. Automated pairing from the app cannot be claimed as working until it is proven on a host where the agent slot is free; the demonstrated path is an existing or peer-initiated bond. Test scripts must send all reports within a single HID session, every test report should state the Bluetooth consent state alongside USB and debugging state, and operators should expect to restart the peer's Bluetooth after an abnormal exit of the Mac app.

## ADR-041 — Route Mac-to-Android HID input through Classic HIDP, not BLE HOGP

**Date:** 2026-11-20
**Status:** BLE HOGP NO-GO for Android; **Classic HIDP GO and accepted** on the OnePlus Nord (Android 12) and the target S24 Ultra (Android 17) with DroidBridge's own implementation. The acceptance run was performed with USB unplugged and both USB and wireless debugging off: 48 presses in one session took the receiver from `14/14/14/14` to `26/26/26/26`, with no ADB channel available. This approves the transport route, not a production input surface — the experiment sends one key per explicit user action, and mapping, text entry and the rest of the input matrix remain unproven. See ADR-042 for host-side constraints.
**Context:** P16 attempted macOS BLE HOGP toward Android. On OnePlus Nord AC2001 / Android 12 / API 31 the phone bonded the Mac but never bound it as a HID input device: `HidHostService.mInputDevices` stayed empty and the Mac recorded zero GATT reads, writes or subscriptions. Repeating the run with the **unmodified** upstream `darwin-bt-remote` app in its default BLE mode produced the identical outcome, so this is not a defect in our GATT layout. The Mac advertises on public address `60:3E:5F:31:D0:6D`, which is also its BR/EDR address; Android therefore classifies the peer `[ DUAL ]` and routes HID over Classic BR/EDR. Separately, an upstream Classic HIDP run on the same hardware published an HID SDP record, opened L2CAP PSM `0x0011` (control) and `0x0013` (interrupt) from the Mac, caused Android to register four input devices (`MacBook Air Keyboard/Mouse/Consumer Control/System Control`) with `STATE_CONNECTED`, and delivered arrow press/release reports that a focused receiver counted (`UP 8, DOWN 3, LEFT 3, RIGHT 1`, with matching down/up event pairs). Source review also showed the upstream Classic backend uses only public `IOBluetooth` APIs and that its `_privateBool` KVC helper is never called.
**Decision:** Treat macOS BLE HOGP → Android as a closed negative result and stop investing in it for Android. Pursue P17 Classic HIDP as the Mac→Android input route, implemented independently with public `IOBluetooth` APIs (SDP HID record plus L2CAP control/interrupt channels and Mac-initiated connection). Keep BLE HOGP recorded for completeness, since it remains the likely path for iOS hosts. Do not adopt upstream source; the reference app is an AGPL research probe only.
**Alternatives:** Keep trying BLE Report Map/descriptor variants (rejected: the host never performs GATT discovery, so layout is not the cause); change the advertised identity or use a custom UUID (rejected: not HID, and the address is the Mac's public BR/EDR address); ship the upstream app (rejected: AGPL obligations and no DroidBridge review); use Accessibility or ADB input (prohibited).
**Consequences:** The independent `P17ClassicHIDP` app now delivers arrow input on the OnePlus (`UP 2, DOWN 1, LEFT 1, RIGHT 1` from a zeroed baseline), so the route no longer rests on the AGPL probe. Any production input feature must still be built on the Classic HIDP path and re-proven on the S24 Ultra with USB disconnected and both debugging modes off. Keyboard support does not imply pointer or touch-equivalent control, and Android 17 masks password characters from physical keyboards. macOS automatic termination can silently drop the HID connection, so an independent implementation must keep the process alive and expose connection state. P16's negative result and P17's preliminary result must be cited together; neither establishes general remote control.

## ADR-040 — Keep a reference-derived P16 comparison isolated pending AGPL review

**Date:** 2026-11-20
**Status:** Local isolated research only; no device result, production use or distribution.
**Context:** The original P16 remains independent and Boot-only. A separate `P16BLEHIDReference` target now contains BLE-only HID code derived from `jqssun/darwin-bt-remote` commit `e64cae983b8816e7e2f067e79025ffc49faf7e2d`, including upstream `LICENSE` and in-app attribution. Its Classic HIDP, Accessibility/Direct Input and service-changed workaround were excluded. The target builds and code-signs, but it has not been launched or device-tested. The upstream license is AGPL-3.0-only.
**Decision:** Keep the reference comparison isolated and local while the owner resolves AGPL handling. Do not merge it into production, push, share or otherwise convey it until that review is decided. Any future test must use the same explicit start/advertise/stop controls, require Android to select a protocol and subscribe to that same central's keyboard input, and require manual confirmation that the harmless P16 receiver is focused before sending one arrow. Do not treat this target as evidence that Android supports Report Protocol.
**Alternatives:** Use only the original Boot-only P16 (retained); copy the upstream app wholesale (rejected; includes prohibited components); share or push the derived comparison before license review (rejected); use private APIs to emulate unsupported descriptors (prohibited).
**Consequences:** The target can support a controlled comparison, but no Android connection, subscription or key-delivery claim exists. The original P16 result remains pending its own Mac publication retest. See [P16](prototypes/P16-ble-hid.md).

## ADR-039 — Limit P16 to Boot Keyboard under public CoreBluetooth descriptor support

**Date:** 2026-11-20
**Status:** Implemented in isolated P16; Mac-side manual publication retest pending.
**Context:** HOGP requires HID, Battery, and Device Information services, with PnP ID in Device Information. The HID Service also requires a readable Report Map. Report Protocol additionally requires Report characteristics mapped with Report Reference descriptors (`0x2908`). Apple's public `CBMutableDescriptor` SDK contract supports only User Description and Presentation Format descriptors, while CoreBluetooth creates CCCDs automatically; it provides no documented method to publish `0x2908`. Boot Hosts ignore the Report Map and use fixed Boot Keyboard reports. A reference project's use of `CBMutableDescriptor` for `0x2908` is implementation evidence, not Apple's supported API contract. The P16 GATT database also lacked Battery/Device Information services. On OnePlus Nord AC2001 / Android 12, the already-bonded device remained disconnected after a Bluetooth Settings row tap, with no active HID Host input device. No key was sent.
**Decision:** Keep P16 isolated and implement required Battery/Device Information/PnP ID services plus the mandatory read-only Report Map, but limit its input path to the fixed Boot Keyboard protocol. Publish the mandatory read-only Report Map, omit Report characteristics, and do not claim Report Protocol. Send only if Android explicitly selects Boot Protocol, subscribes to the Boot Keyboard Input characteristic, and the user clicks an arrow in the POC. Keep Battery as a separate primary service because the Report Map does not reference Battery Level. If the Android host requires Report Protocol or does not connect/subscribe, record that as a compatibility/API limitation; do not use private APIs, fabricated descriptors or Accessibility.
**Alternatives:** Continue with Report Protocol and assume Apple accepts a custom `0x2908` descriptor (rejected; contradicts the public SDK contract); copy or adapt AGPL source (not needed; still does not make it documented CoreBluetooth behavior); use undocumented/private GATT APIs (prohibited); substitute Classic HIDP (separate P17).
**Consequences:** The POC can establish at most Boot-Protocol keyboard feasibility, not full HOGP Report Protocol compliance. OnePlus/S24 results remain unknown until the rebuilt app is manually exercised. No key-delivery claim exists. See R-34, ADR-038 and [P16](prototypes/P16-ble-hid.md).

## ADR-038 — Register SIG GATT UUIDs in canonical Bluetooth-base form for P16 retest

**Date:** 2026-11-20
**Status:** Implemented in the isolated P16 POC; awaiting manual CoreBluetooth service-publication retest.
**Context:** P16 first reported `The specified UUID is not allowed for this operation` while registering standard HID service `0x1812` with CoreBluetooth. Review of the linked `jqssun/darwin-bt-remote` source showed a concrete difference: its GATT registry uses canonical 128-bit Bluetooth-base UUID strings for standard SIG services, characteristics and descriptors, and it uses the short service UUID in advertising. Our initial POC used short-form strings for both GATT registration and advertising. Apple documents the CoreBluetooth error but does not document this short-form registration rule; the upstream repository supplies implementation evidence, not an Apple guarantee.
**Decision:** Independently change P16's registered standard service/characteristic/descriptor IDs to canonical Bluetooth-base UUIDs, while keeping the short HID UUID (`1812`) in the advertisement payload. Surface the NSError domain and code in P16 publication/advertising errors. Do not copy upstream implementation code. Build and signature verification pass; do not claim the service publishes until a manual retry confirms it. Do not replace SIG UUIDs with custom UUIDs or use private APIs.
**Alternatives:** Conclude BLE is impossible based on the first error alone (rejected as premature); use random UUIDs (rejected, not HID); copy the AGPL-3.0-only implementation (not approved); bypass CoreBluetooth with private APIs or Accessibility (prohibited).
**Consequences:** The user should retry Start Bluetooth once on the rebuilt POC and report service status, including NSError domain/code if it fails. Only after “HID service published” may we proceed to advertising and then manual phone testing. P17 Classic HIDP remains independent and pending; upstream HIDP code includes private KVC/Class-of-Device behavior and is not a safe template to transplant wholesale. See R-33 and [P16](prototypes/P16-ble-hid.md).

## ADR-037 — Record initial CoreBluetooth UUID rejection in P16

**Date:** 2026-11-20
**Status:** Superseded for diagnosis by ADR-038; initial observed error remains valid.
**Context:** The first P16 macOS app registered the Bluetooth SIG HID service UUID `0x1812` in short form. After fixing a separate characteristic-cached-value crash, the user observed `HID service publication failed: The specified UUID is not allowed for this operation.` Apple documents `CBError.Code.uuidNotAllowed` as “The specified UUID isn’t permitted.” The tested host is a MacBook Air M1 on macOS 27.2.1. No Android pairing or input test occurred.
**Decision:** Preserve the error as an observed result, but do not treat it as proof that CoreBluetooth rejects the canonical Bluetooth-base UUID form. The linked reference repository uses full Bluetooth-base UUIDs for GATT registration; P16 now adopts this independently and needs a manual retest.
**Alternatives:** Treat the error as a definitive host-wide NO-GO (rejected as premature); replace with a custom UUID (not standard HID); use private APIs or Accessibility (prohibited).
**Consequences:** P16 is pending a Mac-side service-publication retest, not a phone pairing attempt. No Android compatibility or control claim follows. See ADR-038, R-33 and [P16](prototypes/P16-ble-hid.md).

Research pass of 2026-11-08 produced ADR-010 to ADR-028. Each one cites the register entry it comes from in `Docs/RESEARCH_AND_FEASIBILITY.md`.

## ADR-036 — Reopen Mac-to-Android input feasibility through isolated Bluetooth HID POCs

**Date:** 2026-11-17
**Status:** Accepted for feasibility prototypes only; no production input feature approved.
**Context:** The initial P09/ADR-015 conclusion ruled out Mac-as-Bluetooth-HID based on BLE/CoreBluetooth findings and did not test the separately documented Bluetooth Classic HIDP route. The owner identified `jqssun/darwin-bt-remote`, whose public source describes both BLE HID-over-GATT and Classic HIDP backends. The same repository has an open report of unreliable pairing/media input with a Samsung Galaxy S9 on Android 10. This provides a credible alternative to test but no proof for the target S24 Ultra / Android 17 / One UI. The project still prohibits Android Accessibility, ADB as a runtime or functional input/data path, root, hidden APIs, Knox injection, and protected-screen bypasses.
**Decision:** Keep Accessibility prohibited. Add isolated P16 BLE HID and P17 Classic HIDP feasibility POCs, independently implemented against standard/public platform APIs and tested on the S24 Ultra. A POC may send only synthetic keyboard/pointer reports explicitly initiated by the POC UI. Do not copy/vendor upstream source unless the owner separately approves its AGPL-3.0 obligations. Keep mirroring (MediaProjection) and clipboard (documented foreground/manual flow) as separate POCs. The user may leave USB debugging enabled for installation or sanitized diagnostics during development, but no ADB commands may generate functional input, capture screen content, or read/write clipboard data. Device behavior must be manually observed on the phone and in the application; final acceptance must use USB disconnected and both debugging modes off.
**Alternatives:** Continue relying on the old P09 NO-GO (rejected as incomplete because Classic HIDP was not tested); implement Accessibility (rejected; owner's chosen direction is non-Accessibility control); add ADB as a hidden/temporary functional path (rejected; it invalidates the feature test and violates the runtime goal); copy the AGPL implementation (deferred pending license review, not needed for an independent POC).
**Consequences:** P09 remains historical evidence for its exact tests, not a final verdict on HID. Production mirroring remains view-only until P16/P17 demonstrate a precisely scoped input feature on the target. A successful physical keyboard test does not prove arbitrary touch, swipe, app-wide control, clipboard transfer, or compatibility with protected/banking apps. No control claim is made until the target-device tests pass and an acceptance decision is recorded.

## ADR-035 — Use `com.droidbridge.app` for the macOS bundle identifier

**Date:** 2026-11-17
**Status:** Implemented and locally verified
**Context:** The owner requested a consistent, less ambiguous DroidBridge app identity and cleanup of older Finder-indexed app copies. The macOS project previously built with `com.droidbridge.macos`; Android independently uses `com.droidbridge.app`.
**Decision:** Set the macOS app bundle identifier to `com.droidbridge.app` and the macOS test bundle to `com.droidbridge.app.tests`. Leave Android's `applicationId` unchanged. Regenerate the Xcode project from `macos/project.yml`, build and test, then verify the canonical app's plist, signature, persistent identity pin, and Bonjour discovery before removing stale generated app bundles. Retain the canonical copy at `~/Applications/DroidBridge.app`.
**Alternatives:** Keep the old Mac identifier (rejected: does not satisfy the owner's request); change Android's identifier too (rejected: outside scope and can affect Android install/identity behavior); remove broad DerivedData or build directories (rejected: unnecessary and risks unrelated data).
**Consequences:** macOS and Android use the same reverse-DNS string in separate platform namespaces. Local Network consent may be associated with the new Mac bundle identity; actual denied/first-run behavior remains unverified. The login-Keychain identity was verified to persist across the identifier change in the current local build. Existing TCC state was not reset. Only explicitly identified stale `.app` bundles are removed; Keychain items, application settings, cache/build parent directories, and repository files are retained.

## ADR-034 — Persist personal-use Mac identity in the login Keychain until a profile is available

**Date:** 2026-11-17
**Status:** Implemented and verified for the current personal-use ad-hoc build; distribution storage remains pending P15
**Context:** The initial P15 Release build used a temporary keychain deleted when the app process exited; two separate identity-harness processes generated different pins, forcing re-pairing after every app restart. The app has no Apple Developer Program membership or provisioning profile, so ADR-025's data-protection keychain group cannot currently be authorized. R-28 and ADR-025 document the legacy file-based/login Keychain as the fallback, with ACL prompts accepted when the code-signing requirement changes.
**Decision:** The personal-use implementation stores the P-256 private key under an app-specific, versioned application tag in the user's default login Keychain and stores the self-signed certificate in that Keychain. Loads query the private key in the selected Keychain and match certificates by the generated `DroidBridge` label plus the exact public-key application label. Because the legacy certificate query's Keychain selector did not strictly constrain results in testing, certificate lookup temporarily restricts the process search list to the selected Keychain and restores it immediately; identity resolution uses a separate scoped search-list operation. Create only when the tagged key is absent; fail closed on Keychain errors or a missing/non-unique matching certificate. No synchronization is requested, and no private key is placed in an app data file. This path passed disposable-Keychain persistence/fail-closed tests and two separate Release harness launches against the login Keychain with identical pins. The earlier failed attempt's generated key item remains untouched; the new versioned tag does not adopt or overwrite it. Peer reconnect without re-pairing and behavior after code-signing identity changes remain untested. Keep the data-protection Keychain plus authorized access group as the intended distribution path under ADR-025 when a provisioning profile is available; test separately before distribution.
**Alternatives:** Continue generating a temporary identity (rejected: proven to force identity change and re-pair on every app restart); persist a raw private key in a file (rejected: expands the exposed secret surface); add an unauthorized `keychain-access-groups` entitlement or claim the ad-hoc build is equivalent to Developer ID (rejected: Apple restricts that entitlement to profile-authorized use).
**Consequences:** The current local login-Keychain path persists across the tested app-process launches. A fresh install, missing key, or Keychain loss can produce a new Mac identity and require an explicit re-pair; Keychain access errors must not silently regenerate a replacement. Legacy Keychain ACL behavior may prompt after code-signature changes and must be tested with the eventual signing identity. This does not resolve U17 or prove identity readability after Developer ID re-signing. Revisit the storage path when the distribution signing path is available. The failed experiment's generated key item remains in the login Keychain and was not deleted or adopted.

## ADR-033 — Defer macOS Developer ID distribution while DroidBridge is personal-use only

**Date:** 2026-11-17
**Status:** Accepted by owner
**Context:** The owner has no paid Apple Developer Program membership and confirmed that the current app is for personal use. The local Release build uses an ad-hoc “Sign to Run Locally” signature, runs from the build output on the development Mac, and is rejected by Gatekeeper as an unnotarized artifact. This does not prove downloaded/quarantined distribution works.
**Decision:** Continue validating the sandboxed local Mac build for the owner's personal use. Defer Developer ID signing, notarization, stapling, and quarantined-download acceptance until distribution is an explicit goal and the required membership is available. Keep all Phase 9 distribution gates unchanged; never label the ad-hoc build notarized or generally distributable. P15 remains in progress until its relevant local-use identity/keychain and behavior checks are recorded; its distribution-only portion is deferred, not passed.
**Alternatives:** Halt all Mac work until membership is available (rejected: unnecessary for local personal development); weaken Gatekeeper or bypass quarantine to claim distribution (rejected: unsafe and unsupported); describe the ad-hoc artifact as release-ready (rejected: the Gatekeeper check disproves that claim).
**Consequences:** Local build/launch checks may proceed without an Apple account. Signing identity comparisons, notarization, stapling, and quarantined-copy launch remain explicitly out of the current personal-use scope. If sharing/distribution becomes a goal, revisit ADR-026 and complete the Phase 9 gates before release.

## ADR-032 — Shared protocol targets Java 17, not 21

**Date:** 2026-11-09
**Status:** Accepted
**Context:** `shared/protocol` was created with `jvmToolchain(21)`. The Android build compiles and
unit-tests against Java 17. Because the module is consumed as a composite build, Gradle does not
reconcile the two toolchains: the module emitted class file version 65, and every Android unit test
that touched the protocol failed with `UnsupportedClassVersionError` — an error that names the
bytecode version and not the cause.
**Decision:** `shared/protocol` targets Java 17, matching its only consumer. The build file carries
a comment explaining that the two must match by hand.
**Consequences:** Android unit tests can load the protocol. If a future consumer needs 21, this
becomes a real decision rather than a silent mismatch. The macOS app does not consume this module
(it is Swift and implements `NETWORK_PROTOCOL.md` directly), so there is no second consumer to
satisfy.

## ADR-031 — Android build: AGP 9 built-in Kotlin, SDK levels in one place, shared protocol as a composite build
- Date: 2026-11-09
- Status: Accepted
- Context: Phase 0 needed a real Android build. Three constraints surfaced while making it work. (a) AGP 9.0+ has built-in Kotlin support and **fails the build** if `org.jetbrains.kotlin.android` is also applied. (b) `compileSdk` must be set per module; a `subprojects` convention block alone does not satisfy the application module. (c) `shared/protocol` is a standalone Kotlin/JVM build that must stay testable without an Android SDK, but the app must not carry a second copy of the framing rules.
- Decision: Apply only `com.android.application` / `com.android.library` plus `org.jetbrains.kotlin.plugin.compose`; no Kotlin Android plugin. Put `compileSdk`/`targetSdk`/`minSdk` and the lint policy in the root `build.gradle.kts` for both the library and application plugin ids, with the numbers themselves in `gradle/libs.versions.toml`. Consume `shared/protocol` through `includeBuild("../shared/protocol")` with a `dependencySubstitution`, and keep `core/protocol/MediaFraming.kt` as a facade so a broken substitution is a compile error.
- Alternatives: (a) downgrade to AGP 8 to keep the separate Kotlin plugin (rejected: buys nothing and starts on a version that will be removed); (b) copy the protocol sources into an Android module (rejected: two definitions of the wire format, which is exactly the failure ADR-027 exists to prevent); (c) publish the protocol to a local Maven repo (rejected: an extra publish step in the developer loop for no benefit over a composite build).
- Consequences: `minSdk = 31` is a Phase 0 choice, not a product decision — it is the OS on the available test device and the level at which the clipboard and FGS rules in the specs are current. Raising it later is cheap; lowering it after code exists is not. Lint runs with `warningsAsErrors = true` and an empty baseline, so a new warning fails the build rather than accumulating.

## ADR-030 — The Xcode project is generated from `project.yml` and the generated project is committed
- Date: 2026-11-09
- Status: Accepted
- Context: `MACOS_APP_SPEC.md` requires an Xcode project in `macos/`. A hand-edited `.pbxproj` is unreadable in review, merges badly, and hides settings such as entitlements and signing flags in a binary-ish blob. xcodegen 2.46.0 is installed on the owner's machine.
- Decision: `macos/project.yml` is the source of truth; `xcodegen generate` produces `DroidBridge.xcodeproj`. The generated project is **committed** so the repository opens in Xcode without the tool, and `scripts/verify-all.sh` regenerates it before building so the committed project cannot silently drift.
- Alternatives: hand-maintained `.pbxproj` (rejected: unreviewable, and the entitlement/signing settings are the security-relevant part); Swift Package Manager only (rejected: no app bundle, no entitlements, no Info.plist — not a macOS app target); Tuist (rejected: not installed, and xcodegen is sufficient).
- Consequences: a contributor without xcodegen can still build; a contributor who changes `project.yml` must regenerate and commit, or CI's regeneration step will produce a diff. Deployment target is macOS 15.0 and `ARCHS = arm64` only — Intel is not claimed because it has not been built or tested.

## ADR-031 — Release builds must not carry `get-task-allow`; injection is disabled for Release only
- Date: 2026-11-09
- Status: Accepted
- Context: `PERMISSIONS_MATRIX.md` §4 requires `com.apple.security.get-task-allow` to be absent from a shipped build, because the notary service rejects it. The first Phase 0 Release build carried it anyway: Xcode injects base entitlements into any ad-hoc/development-signed build. This was caught by reading the entitlements back out of the signed bundle, not by reading the settings.
- Decision: set `CODE_SIGN_INJECT_BASE_ENTITLEMENTS: NO` for the **Release** configuration only. Debug keeps the injection, because a build without `get-task-allow` cannot be debugged. Verify by `codesign -d --entitlements :-` on the built bundle, not by inspecting project settings.
- Alternatives: strip the entitlement in a post-build script (rejected: a second signing pass, and it hides the cause); leave it and rely on the eventual Developer ID export to strip it (rejected: "Xcode strips it on export" is a claim about a step that does not exist yet, and the audit must pass on the artifact in hand).
- Consequences: the Release bundle now carries exactly `app-sandbox`, `network.client`, `network.server`. Any future configuration that re-enables base-entitlement injection re-introduces the defect, so the audit step in `scripts/verify-all.sh` is the control, not the setting.

## ADR-028 — The Mac decodes and displays compressed frames with `AVSampleBufferDisplayLayer` behind one adapter
- Date: 2026-11-08
- Status: Proposed — pending `P07`
- Context: the Mac only has to *view* the Android stream (ADR-015 excludes remote input), so it needs a decoder and a renderer, not frame access. Research (R-31) established that `AVSampleBufferDisplayLayer` decodes compressed H.264 itself, so no `VTDecompressionSession` and no `CVPixelBuffer` handling are required. It also established that the layer's `enqueue(_:)`/`isReadyForMoreMediaData`/`flush()` API is deprecated in the current SDK in favour of `AVSampleBufferVideoRenderer.Receiver`, which is documented as macOS 27.0+ — an OS that is not shipping today.
- Decision: build the viewer on `AVSampleBufferDisplayLayer` + `CMVideoFormatDescriptionCreateFromH264ParameterSets` + `CMSampleBufferCreateReady`, wrapped in a single narrow adapter so the enqueue path can be swapped for `Receiver` later. One encoded frame per sample buffer, one timing entry, `decodeTimeStamp = .invalid`, strictly increasing PTS in a 1/1,000,000 timescale derived from Android's `presentationTimeUs` and rebased on reconnect. Gate enqueues on `isReadyForMoreMediaData` and coalesce frames rather than queueing without bound. On `.failed`/`requiresFlushToResumeDecoding`, call `flush()` and **request a keyframe** rather than resuming P-frames. Set `preventsCapture = true` so the mirrored phone screen cannot leak into a Mac screen recording. Do not raise the deployment target to silence deprecation warnings for an API that does not exist yet.
- Alternatives: `VTDecompressionSession` with a `CVPixelBuffer` pool and a custom `CALayer` (rejected: more code, more state, and it is only justified if frames must be inspected or transformed, which no MVP feature needs); `AVPlayer`/`AVPlayerItemVideoOutput` (rejected: it wants a file or HLS-like timeline, not a live push stream); WebRTC's macOS renderer (rejected with ADR-012: it brings the whole media stack).
- Consequences: the adapter is the only place that touches AVFoundation, so the macOS 27 migration is contained. The keyframe-on-recovery rule becomes part of `NETWORK_PROTOCOL.md`, not an implementation detail. P07 must record which members warn as deprecated on the owner's toolchain (U20) and must measure the layer's added latency rather than assuming it is negligible.

## ADR-027 — Mirroring encodes H.264 from the capture surface and puts length-prefixed AVCC on the wire
- Date: 2026-11-08
- Status: Proposed — pending `P01`/`P07`
- Context: ADR-012 chose TLS/TCP and left codec framing to the prototypes. R-29 and R-30 supply the constraints: MediaCodec can be fed by a `Surface` (so no pixel access and no colour conversion in app code), it emits Annex-B with start-code-prefixed parameter sets, and CoreMedia requires parameter sets as bare NALs plus length-prefixed samples. The formats are incompatible by construction, so one conversion is unavoidable.
- Decision: encode with `MediaCodec` in surface mode (`COLOR_FormatSurface`, `CONFIGURE_FLAG_ENCODE`, `createInputSurface()` handed to `createVirtualDisplay()`), `video/avc`, `KEY_MAX_B_FRAMES = 0`, `KEY_REPEAT_PREVIOUS_FRAME_AFTER` set so a static screen keeps producing frames, and a bitrate mode chosen from `isBitrateModeSupported()` with **CBR preferred** because the API-31 minimum quality floor applies only to VBR. Choose the encode size from `VideoCapabilities.areSizeAndRateSupported()`, never from the panel resolution. Convert on the **producer**: SPS and PPS travel as bare NALs in `mirror.format`, and every access unit is sent as 4-byte big-endian length + NAL bytes, with emulation-prevention bytes untouched.
- Alternatives: send Annex-B and convert on the Mac (rejected: moves a framing bug away from the code that produced it); mux to MP4 with `MediaMuxer` (rejected: file-oriented, adds buffering to a live stream); let the encoder pick the profile (rejected: the documented default is device-specific and may be experimental, and a mismatched profile can make the session fail); VBR by default (rejected: the quality floor can exceed the requested bitrate).
- Consequences: `NETWORK_PROTOCOL.md` must define the media framing precisely, including the keyframe requirement at stream start, after a format change, and after a Mac-side decoder flush. Rotation/resize is a re-encode with a new `mirror.format`, not a scale. P01 must record which resolutions the S24 Ultra encoder accepts (U18) and P08 whether parameter sets change mid-session (U19).

## ADR-026 — macOS ships outside the Mac App Store as a notarised, sandboxed Developer ID app
- Date: 2026-11-08
- Status: Accepted — release prerequisites recorded
- Context: the macOS app is the advertiser and listener (ADR-010) and needs its own identity (ADR-024). Research on distribution (R-25–R-28) established that Developer ID software built after 2019-06-01 must be notarised to run on macOS 10.15+, that notarisation requires the hardened runtime and a secure timestamp, that an unnotarised app can only be opened through System Settings → Privacy & Security → Open Anyway, and that only the Apple Developer Program **Account Holder** can create a Developer ID certificate. On Android, Play's floor is API 36 since 2026-08-31, so `targetSdk 37` is above it.
- Decision: the Mac app is distributed directly as a **Developer ID-signed, notarised and stapled DMG**, with the hardened runtime enabled, a secure timestamp, no `get-task-allow`, ASCII XML entitlements, and inside-out signing — never `codesign --deep` for signing. The App Sandbox is enabled from the first build with `com.apple.security.app-sandbox`, `com.apple.security.network.client` and `com.apple.security.network.server`. Android ships through Google Play as an **AAB with Play App Signing**, declaring the `connectedDevice` and `mediaProjection` FGS types with their descriptions, impact statements and demo videos, and the `ACCESS_LOCAL_NETWORK` declaration only if P02 forces that permission. The Mac App Store is deferred but kept reachable by keeping the sandbox on.
- Alternatives: unnotarised distribution with a bypass instruction (rejected: current macOS offers only the Settings override, and instructing users to disable a security control is not an acceptable install path); Mac App Store only (rejected for now: adds review and storefront requirements without solving anything the direct path does not, and the audience is the owner's own devices); ad-hoc signing as the release format (rejected: not notarisable, and the local-network privilege state does not track the program reliably).
- Consequences: a **paid Apple Developer Program membership** and the Account Holder's involvement are hard prerequisites before Phase 9; notary credentials (app-specific password or App Store Connect API key) must live outside the repository; the release checklist gains signing, notarisation, stapling and declaration items; a sandbox violation becomes a release blocker rather than a late surprise. Moving the listener into a launchd daemon to inherit macOS's automatic local-network allowance is explicitly **not** used — it would remove the user's control over a privacy permission.

## ADR-025 — The macOS identity is stored in the data protection keychain under an embedded Developer ID profile
- Date: 2026-11-08
- Status: Proposed — pending `P13`/`P15`
- Context: ADR-024 requires a non-exportable identity key and certificate that survive relaunches and remain readable by the app after re-signing. macOS has two keychain implementations (TN3137): the legacy file-based keychain, whose items are guarded by ACLs, and the data protection keychain, whose access groups "must be authorized by a provisioning profile" and whose groups are built from code-signing entitlements. Apple's entitlement rule (R-28) makes the App Sandbox and Hardened Runtime entitlements unrestricted, but `keychain-access-groups` restricted — so the storage choice decides whether the app needs an embedded profile at all.
- Decision: store the identity in the **data protection keychain** (`kSecUseDataProtectionKeychain: true`) under this app's own `keychain-access-groups` entry, authorized by an **embedded Developer ID provisioning profile**. The key is a P-256 key from `SecKeyCreateRandomKey`, marked non-exportable and Secure-Enclave-backed where the hardware supports it; the self-signed certificate from ADR-024 is stored beside it. No iCloud synchronisation (`kSecAttrSynchronizable` stays off), so pairing material never leaves the Mac.
- Alternatives: legacy file-based keychain (rejected as the default: its ACLs prompt the user when the app's designated requirement changes, which is exactly what a certificate renewal or re-sign does, and it gives weaker per-app isolation); keeping the key in a file protected by a keychain-held secret (rejected: more code, weaker guarantee, and the private key would exist outside the Keychain's control); not storing a key at all and regenerating per launch (rejected: it would re-pair every time).
- Consequences: every build carries a profile, which must be replaced with a **distribution** profile at release and re-signed afterwards — the profile is inside the bundle, so it is part of what gets notarised. If P15 shows the profile is unworkable for local development, the fallback is the legacy keychain with ACL prompts accepted, and this ADR is superseded rather than silently ignored. U17 tracks whether re-signing under a renewed certificate keeps the identity readable.
- Interim behaviour (P08, 2026-11-12): the macOS receiver holds **one identity per app run** instead of generating one per Listen. Generating per Listen changed the pin under a live session, so every restart needed Forget stored pin plus a fresh comparison and reconnect recovery could not be measured. This is the minimum needed for P08; the identity still does not survive an app relaunch until this ADR lands, so a relaunch keeps requiring one new pairing.

## ADR-024 — Device identity is generated per install and pinned by public-key digest
- Date: 2026-11-08
- Status: Proposed — pending `P13`
- Context: ADR-011 chose a pinned, app-owned certificate but left the mechanics open. Research (R-22–R-24) established that Android's Keystore generates a self-signed certificate for an asymmetric key at no extra cost, that macOS has **no** public certificate-creation API and needs Apple's `swift-certificates`, and that neither platform's default verification can be used for an IP-literal peer.
- Decision: On first run each app generates a non-exportable P-256 key and a self-signed v3 certificate (Android: `KeyGenParameterSpec` with `PURPOSE_SIGN`, subject/serial/validity set, certificate read back from the Keystore; macOS: swift-crypto key + `X509.Certificate(version:serialNumber:publicKey:notValidBefore:notValidAfter:issuer:subject:extensions:issuerPrivateKey:)`, key and certificate stored in the app's Keychain). Both sides present the identity over mutual TLS (Android `setNeedClientAuth(true)` on the accepting socket; macOS `sec_protocol_options_set_local_identity` plus an explicit `sec_protocol_options_set_peer_authentication_required(true)`, because servers default to *not* requiring peer authentication). Verification is a SHA-256 digest of the peer's SubjectPublicKeyInfo compared on every handshake — no hostname matching, no system trust store, no static trust anchor in `network_security_config.xml` (impossible for a peer learned at pairing time).
- Alternatives: static `<trust-anchors>` with a bundled peer certificate (rejected: the peer is unknown at build time); TLS-PSK (rejected: Apple PSK is TLS 1.2 only and Android has no public PSK API); hand-built DER/X.509 (rejected: unnecessary risk); system trust store with a local CA (rejected: no CA, no cloud, no build-time secret).
- Consequences: a key change is an identity change and must be shown as such; a certificate renewal for the same key needs no re-pairing. One Apple-maintained dependency set (`swift-certificates`, `swift-crypto`, `swift-asn1`) is added to the macOS app. Peer-authentication defaults must be asserted in tests, since a silent default would produce one-way authentication that still "works". **Where** the macOS key and certificate are stored — and the provisioning-profile consequence of that choice — is settled separately in ADR-025.

## ADR-023 — Background clipboard session runs in a `connectedDevice` foreground service
- Date: 2026-11-08
- Status: **Proposed — P14 shows the premise fails on the OnePlus** (a valid `connectedDevice` FGS is frozen once the app is backgrounded); the S24 Ultra row is outstanding. See ADR-043.
- Context: "Clipboard sync while mirroring is off" needs a live session, but MediaProjection is not available for clipboard-only work. The `connectedDevice` type covers network connections to external devices, has no documented timeout, is not blocked from `BOOT_COMPLETED`, and its runtime prerequisite can be met by **declaring** the normal `CHANGE_NETWORK_STATE` permission — so it needs no runtime prompt (`RESEARCH_AND_FEASIBILITY.md` R-20). Doze still suspends the network device-wide, so liveness is best-effort.
- Decision: One `connectedDevice` foreground service owns the LAN session whenever background sync is enabled by the user; the mirroring session uses its own `mediaProjection` service and, when both run, shares the same connection. The service is started only from app UI or from `BOOT_COMPLETED`, shows a persistent notification with connection state and a disconnect action, and on any network change, Doze exit, socket error or app resume it reconnects with exponential backoff. Declare `POST_NOTIFICATIONS` and request it in context, and declare the `connectedDevice` type on the Play Console app-content page (R-21).
- Alternatives: `dataSync` (rejected: 6 h/24 h cap and boot restriction); `specialUse` (rejected: Play subtype review for a covered use case); no background service at all (rejected for the P0 clipboard user story, retained as the degradation path if P14 fails); `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (rejected: not justifiable for a consumer app).
- Consequences: the app always shows a session notification while background sync is on, must treat "connected" as a state that can lapse, and must never present a stale socket as reachable. If P14 shows the socket cannot survive a normal night, the feature degrades to foreground-only sync and the product copy changes accordingly.

## ADR-022 — Reserve a private Bonjour service type during development
- Date: 2026-11-08
- Status: Accepted for prototypes
- Context: The Mac advertises a service that Android must discover through the system NSD picker (ADR-010). `NSBonjourServices` must list the type, and a globally wrong or already-registered type would confuse discovery.
- Decision: Prototypes use the private type `_droidbridge-dev._tcp`. Before any public release, a unique type must be chosen and documented in `NETWORK_PROTOCOL.md`, and the same value used in `NSBonjourServices`, the Android `DiscoveryRequest`, and the TXT record schema.
- Alternatives: A generic type such as `_http._tcp` (rejected: collides with unrelated services and pollutes the picker list).
- Consequences: Service type is part of the pairing compatibility surface; changing it later forces re-pairing.

## ADR-021 — Target Android 17 (API 37) for the MVP
- Date: 2026-11-08
- Status: Accepted
- Context: Android 17 blocks local network access by default for apps targeting API 37+ (`RESEARCH_AND_FEASIBILITY.md` R-01). Targeting ≤ 36 keeps an implicit grant but is temporary and conflicts with Play target-API requirements and the security posture.
- Decision: `targetSdk = 37` from the first Android build, with the LAN strategy in ADR-010 adopted from day one rather than retrofitted.
- Alternatives: Stay on 36 to avoid the permission model (rejected: temporary, and would hide a mandatory migration behind a later release).
- Consequences: `ACCESS_LOCAL_NETWORK` handling, picker UX and denied-state UX must exist before Phase 2 closes. Confirmed 2026-11-08: Play's floor has been API 36 since 2026-08-31, so 37 is above the requirement rather than below it, and targeting 37 is a deliberate choice to absorb the local-network model early (R-25). Because `ACCESS_LOCAL_NETWORK` became a restricted permission with a Play declaration from 2027-08-25, the picker-first design also avoids a policy deliverable — one more reason for ADR-010's direction.

## ADR-020 — Treat Android screenshare protections as a documented product limitation
- Date: 2026-11-08
- Status: Accepted
- Context: Android 15+ hides notification content, password input and OTP-bearing activities from screen sharing (`R-07`). Secure windows are excluded via `FLAG_SECURE` (`R-08`).
- Decision: Do not attempt to defeat or detect-and-recover these protections. Mirroring surfaces blank/redacted regions and the UI states why this can happen. DroidBridge's own sensitive screens use `FLAG_SECURE`.
- Alternatives: Asking users to enable Developer Options "disable screen share protections" (rejected: developer-only escape hatch, removes a user protection, and would make test results unrepresentative).
- Consequences: Product copy must set expectations; mirroring tests must record redacted regions instead of treating them as failures.

## ADR-019 — Call notifications and call control are out of scope
- Date: 2026-11-08
- Status: Rejected for MVP
- Context: Answering/rejecting calls or reading call details requires the default-dialer role, a `ConnectionService` owned by the calling app, or a notification listener (`R-17`). Cellular call audio cannot be captured at all (`R-15`).
- Decision: No Telecom role, call-log, phone-state, microphone or notification-listener permission. No call features in the MVP or in the initial release scope.
- Alternatives: Notification listener for call alerts (explicitly prohibited by `AGENTS.md`); default-dialer role (changes the user's phone).
- Consequences: Removing call work also removes the strongest pressure to add broad permissions.

## ADR-018 — No audio capture in the MVP (superseded by ADR-057 for media audio)
- Date: 2026-11-08
- Status: Superseded on 2026-11-26 by ADR-057 for media playback audio; call and notification audio remain out of scope
- Context: Media playback capture needs `RECORD_AUDIO` plus MediaProjection consent and subject-app opt-in, and is further constrained in Android 17 background-audio hardening (`R-15`, `R-16`). Call audio is impossible.
- Decision: MVP streams video only. Audio is a separately approved research track (`P12`) that must not add `RECORD_AUDIO` to the shipping manifest before that approval.
- Alternatives: Add `RECORD_AUDIO` now for a future feature (rejected: speculative permission).
- Consequences: Mirroring UX must state that there is no audio.

## ADR-017 — Knox RemoteInjection is excluded
- Date: 2026-11-08
- Status: Rejected
- Context: `KNOX_REMOTE_CONTROL` is signature-protected; since Knox API 38 non-Device-Owner/Profile-Owner callers need an authorized scope issued through MDM, and the DeX injection methods are gone on Android 16+ (`R-14`).
- Decision: Do not use Knox SDK, Knox permissions, or any managed-device requirement. Do not prototype against Knox.
- Alternatives: None compatible with a consumer app and the project's prohibition on privileged OEM interfaces.
- Consequences: Remote input cannot rely on Samsung tooling; see ADR-015 and ADR-016.

## ADR-016 — Initial Bluetooth HID assessment (superseded in part by ADR-036)
- Date: 2026-11-08
- Status: Superseded for Mac-as-HID feasibility by ADR-036; Android-as-HID direction remains out of scope
- Context: The initial research concluded that BLE HID was refused by macOS and that Classic HID transport was system-owned. Later source review of `jqssun/darwin-bt-remote` found independent BLE HOGP and Classic HIDP backends; see R-32.
- Decision: This initial NO-GO is superseded only as to whether a Mac can attempt HID peripheral behavior. P16/P17 must test BLE/Classic separately on the actual S24 Ultra using an independent prototype. Android's `BluetoothHidDevice` remains the reverse role. Android 17 physical-keyboard password masking remains a limitation (R-19).
- Alternatives: Treat the repository's claims as proof (rejected; Samsung issue is open and target is untested); use a custom BLE GATT data service (not system input); use Accessibility/ADB/Knox/root (prohibited).
- Consequences: No production control feature is yet approved. The mirroring UI remains view-only pending target POC evidence. See ADR-036.

## ADR-015 — Mirroring ships view-only until a supported input path is proven
- Date: 2026-11-08
- Status: Accepted for current implementation; remote-input feasibility reopened under ADR-036 (2026-11-17)
- Context: No generic touch-injection API is available under the project's constraints. BLE and Classic Bluetooth HID are now candidates for isolated testing based on R-32, but neither is verified on the target phone.
- Decision: Until P16/P17 pass on the target and an acceptance decision records the exact support, mirroring remains view-only and the Mac UI must not imply that control works. `input.*` protocol messages remain undefined until an adapter is proven.
- Alternatives: Treat upstream compatibility claims as sufficient (rejected); use Accessibility, ADB input, root, Knox or hidden interfaces (prohibited).
- Consequences: P16/P17 now gate any control claim. Even a HID pass may establish only keyboard and/or pointer behavior, not arbitrary touch gestures. Phase 5 acceptance must be updated from the POC evidence.

## ADR-014 — Model macOS pasteboard access as an explicit user-granted state
- Date: 2026-11-08
- Status: Proposed — pending `P06`
- Context: macOS 15.4+ defaults the general pasteboard to *ask upon programmatic access*, and a denial blocks monitoring until the user changes System Settings (`R-11`).
- Decision: The Mac app models three states (undetermined / allowed / denied), explains the prompt before it appears, links to the settings pane, and always offers a manual "send clipboard now" path that works from an explicit user action. Automatic Mac → Android sync is enabled only after access is confirmed.
- Alternatives: Poll and hope (rejected: silent failure); require `alwaysAllow` up front with no explanation (rejected: poor trust UX).
- Consequences: Clipboard sync must degrade to a manual workflow without pretending to be automatic.

## ADR-013 — Clipboard sync is foreground/manual first, with no IME and no Accessibility
- Date: 2026-11-08
- Status: Accepted (reaffirms ADR-006)
- Context: `getPrimaryClip()` returns null without input focus or the default-IME role (`R-09`); the project forbids requiring an IME or an Accessibility Service.
- Decision: Android → Mac is a user-initiated action from the app while it holds focus. Mac → Android writes via `setPrimaryClip()` and must tolerate system clipboard UI and auto-clear. No clipboard history, no background read attempts, no silent retries.
- Alternatives: Bundle a keyboard/IME (rejected: changes the user's input method and privacy model); Accessibility scraping (prohibited).
- Consequences: Two-way automatic sync is not a release claim; the release notes must describe the manual workflow.

## ADR-012 — TLS 1.3 over TCP with length-prefixed framing is the MVP transport
- Date: 2026-11-08
- Status: Accepted for prototypes; final confirmation at `P08`
- Context: The MVP needs one authenticated duplex channel carrying control messages and an H.264 stream. WebRTC adds a media stack, ICE/mDNS machinery that interacts with macOS local-network privacy, and a second dependency surface (`R-18`).
- Decision: Mutually authenticated TLS 1.3 over TCP, length-prefixed binary framing for control and media frames, no STUN/TURN, no plaintext fallback. WebRTC is measured only if `P08` shows the simple transport cannot meet latency/quality targets.
- Alternatives: WebRTC with local-only candidates (retained as a measured fallback); QUIC (rejected for MVP: extra dependency, no benefit on a reliable LAN).
- Consequences: Congestion control, keyframe recovery and pacing are our responsibility and must be measured in `P08`. This supersedes ADR-009. The codec-specific half of this decision — surface-fed H.264 and length-prefixed AVCC on the media channel — is settled in ADR-027, and the macOS side in ADR-028.

## ADR-011 — Identity is an app-owned pinned certificate, not a public CA
- Date: 2026-11-08
- Status: Accepted for prototypes; implementation specified in ADR-024; verify in `P13`
- Context: Android 17 enables Certificate Transparency by default for targetSdk 37, but CT verification is not performed for connections using custom trust anchors (`R-03`). No public CA can be used for a local IP anyway.
- Decision: Each side generates a self-signed identity on first run, the user confirms a short authentication code on both screens, and the peer certificate is pinned. The Android app ships a `network_security_config.xml` declaring the app-owned anchor (never a global CT disable, never a trust-all manager).
- Alternatives: A shared pre-shared key (weaker identity model, no forward secrecy story); public CA with a local hostname (infeasible offline).
- Consequences: Certificate rotation equals re-pairing; both platforms must surface a clear "identity changed" warning.

## ADR-010 — The Mac advertises and the Android app dials out through the system NSD picker
- Date: 2026-11-08
- Status: Proposed — pending `P02`
- Context: On Android 17/API 37, `NsdManager.registerService()` (advertising) always requires `ACCESS_LOCAL_NETWORK`, while a picker-selected service can be connected to without the broad permission (`R-01`, `R-02`). The media direction is Android → Mac, so Android dialling out suits the pipeline.
- Decision: Primary design — the macOS app advertises `_droidbridge-dev._tcp` via Bonjour and listens; the Android app uses `DiscoveryRequest.FLAG_SHOW_PICKER` (~`FLAG_USER_APPROVED_ONLY` for repeat connections), connects out to the selected peer over TLS, and keeps that single duplex connection for clipboard and mirroring. `ACCESS_LOCAL_NETWORK` is the documented fallback only if `P02` shows the picker path cannot sustain the session (e.g. inbound needs, revocation churn, or One UI 9 behavior).
- Alternatives: Android advertises and listens (requires the broad runtime permission for every user); manual IP entry (rejected: brittle, DHCP changes, bad UX).
- Consequences: The Mac must request local-network access and declare its service type; Android must re-resolve addresses through `registerServiceInfoCallback` instead of caching IPs; reconnect UX depends on remembering picker grants and re-showing the picker when a grant is revoked.

## ADR-009 — Media transport not yet selected
- Date: 2026-11-07
- Status: Superseded by ADR-012
- Decision: Compare local TLS/TCP framing and WebRTC local-only transport before selection.
- Resolution: ADR-012 selects TLS/TCP for the MVP and keeps WebRTC as a measured fallback in `P08`.

## ADR-001 — Two native applications
- Date: 2026-11-07
- Status: Accepted
- Decision: One Android Kotlin/Compose app and one native macOS SwiftUI app in one repository.
- Rationale: Native clipboard, MediaProjection, pasteboard, networking and video APIs are platform-specific.

## ADR-002 — Wi-Fi first, Bluetooth optional
- Date: 2026-11-07
- Status: Accepted
- Decision: Wi-Fi is the primary channel for discovery, pairing, clipboard and screen streaming. Bluetooth is optional and experimental.
- Consequence: All core features must work with Bluetooth disabled.

## ADR-003 — No cloud
- Date: 2026-11-07
- Status: Accepted
- Decision: No cloud account, storage, relay or telemetry.
- Consequence: Same local network is required.

## ADR-004 — No Accessibility, root or debugging at runtime
- Date: 2026-11-07
- Status: Accepted
- Decision: Never require Accessibility Service, root, USB debugging or wireless debugging in production.
- Consequence: Arbitrary remote touch injection is not assumed.

## ADR-005 — Protected content must remain protected
- Date: 2026-11-07
- Status: Accepted
- Decision: Respect FLAG_SECURE, DRM and system projection controls.
- Consequence: Some screens may appear black/unavailable.

## ADR-006 — Clipboard foreground-first
- Date: 2026-11-07
- Status: Accepted (reaffirmed by ADR-013)
- Decision: Build reliable foreground/manual flows first. Automatic background clipboard behaviour is experimental.
- Rationale: Android 10+ restricts background clipboard reads.

## ADR-007 — Android 17 LAN permission investigation
- Date: 2026-11-07
- Status: Resolved into ADR-010 and ADR-021
- Decision: Test NSD system picker and ACCESS_LOCAL_NETWORK runtime permission paths.
- Rationale: Android 17/API 37 local network protections affect direct LAN sockets and NSD.

## ADR-008 — Remote input is a feasibility gate
- Date: 2026-11-07
- Status: Reopened for Mac Bluetooth HID feasibility by ADR-036 (2026-11-17); no control claim until physical-device tests pass.
- Decision: Do not claim full remote control until P16/P17 establish exact supported behavior through a compliant Bluetooth HID path. Accessibility, ADB functional paths, root and privileged APIs remain prohibited.
- Alternatives: Keep production mirroring view-only until the evidence supports any narrower control claim.

## ADR-045 — Media audio capture is GO (conditional); call audio is closed NO-GO; call control deferred

**Date:** 2026-11-22
**Status:** Accepted, measured on SM-S928B (Android 17 / API 37).

**Context:** P12 was the last unstarted phase and the only one that could not proceed without an
owner decision, because playback capture needs `RECORD_AUDIO`. The owner approved that permission for
an isolated experiment and directed that setup and consent prompts be driven through ADB rather than
by hand.

**Decision:**
1. **Media audio: GO, conditional.** `AudioRecord` + `AudioPlaybackCaptureConfiguration` under a
   `mediaProjection` foreground service captures an allowed app's playback **bit-exactly** on the
   target device (`rms=5791.5 peak=8190` against a source amplitude of 8192). Public APIs only.
2. **Call audio: NO-GO, closed.** Publishing `USAGE_VOICE_COMMUNICATION` with the most permissive
   policy an app can declare still yields digital silence, so the exclusion is the platform's, not any
   app's. This is measured, not inferred from documentation.
3. **Call notifications/control: deferred to P2.** They need `READ_PHONE_STATE` or notification-listener
   access, which `AGENTS.md` gates behind separate approval and which no MVP surface needs.
4. **A refused capture must be treated as a product state, not an error.** Refusal is silent: the
   stream keeps its full frame rate and carries nothing. An audio feature that cannot distinguish
   "refused" from "quiet" would appear to work while delivering silence, so any future audio track
   must measure amplitude, never frame arrival.

**Alternatives rejected:** recording call audio through a privileged role or OEM interface (prohibited);
using the notification listener to infer calls (unapproved sensitive permission, and it would not
deliver audio); streaming audio without a refusal signal (fails the "never claim what was not
measured" rule).

**Consequences:** `RECORD_AUDIO` lives in `:experiments:audio-capture` and nowhere else; `:app` is
unchanged. Audio stays an experiment until a product decision adds it, and that decision now has a
number to reason about instead of a documentation claim.

## ADR-046 — One coroutine owns a session; the pairing code is confirmed on both sides or not at all

**Date:** 2026-11-22
**Status:** Accepted, verified on the OnePlus Nord (Android 12) against the macOS product app.

**Context:** Phase 2 turned the P02/P03 prototypes into product code: `ServiceDiscovery`, a
`Pairing` state machine in `:core:pairing`, and `SessionController` in `:core:network`, with the
Mac side unchanged (`SecureListener` + `LiveMirrorReceiver` already advertised, pinned and answered
PING). Building it surfaced three defects that only a real end-to-end run exposes, and each one
shaped a decision.

**Decisions:**

1. **Sockets run off the main thread, always.** `SecureSession.connect` blocks, and the first
   end-to-end run failed every attempt with `NetworkOnMainThreadException` while `curl` from the same
   device reached the same port. `SessionController` defaults to `Dispatchers.IO` rather than trusting
   the caller's scope, because the failure looks exactly like a network fault from the outside.

2. **One coroutine owns the whole connection lifecycle.** Connect, keepalive and retry live in a single
   loop. The first implementation launched a replacement worker from inside its own failure handler;
   the older worker's cleanup then cancelled the newer one, so every drop produced two overlapping
   reconnects that appeared to succeed and instantly died. A deliberate drop now only closes the
   socket — the single loop observes it and takes the one retry path.

3. **"Requires confirmation" is derived from the session, not from its own inverse.** The wiring
   passed `requiresConfirmation = (state == AUTHENTICATED)`, so an unconfirmed session was announced
   as connected. The state machine was right; the caller was wrong, and only a live one-shot run
   showed it. The flag is now `state != AUTHENTICATED`, and the prompt-bearing state is the only one
   that can reach `CONNECTED`.

4. **An unprompted confirmation is allowed only for a code the operator has already matched.** An
   unattended run cannot click "Confirm pairing", and a blind auto-confirm would defeat the whole
   design. `--p08-confirm-code <code>` confirms **only** when the derived code equals that value, and
   logs `AUTO_CONFIRM_CODE_VERIFIED`; anything else logs `AUTO_CONFIRM_CODE_SKIPPED`. The same rule
   already existed for pins (`--p08-expect-pin`).

5. **The Mac advertises its protocol version.** `ServiceTxtRecord` existed but the listener published
   `txtRecord: nil`, so a peer could not be checked for compatibility and a stale advertisement was
   indistinguishable from a live one. The listener now publishes `v`+`sid`, and Android reads `v`
   (`ServiceDiscovery.TXT_PROTOCOL_VERSION`). The two sides previously used different key names —
   `pv` on Android, `v` on the Mac — which discovery reported as "unstated" rather than failing.

**Consequences:** the pairing rules are testable on the JVM (15 tests across `Pairing` and
`BackoffPolicy`, plus the discovery compatibility rule); the Mac keeps refusing unknown identities
instead of silently trusting a rebuilt app; and the accepted Phase 2 exit run is documented as
functionally complete with the debugging-off sweep still outstanding.

6. **The picker is the consent path; the broad LAN permission is a fallback.** Measured on the S24 by
   denying `ACCESS_LOCAL_NETWORK` and running anyway: the picker resolved the Mac and the session
   connected, so asking for the broad permission at launch bought nothing and requested more access
   than the feature needs. The app now starts picker discovery directly on API 37 and offers the
   runtime request only when the picker path reports that it needs it, as a user-initiated action.

**Environment constraint (not a product defect):** Little Snitch on this Mac silently blocks inbound
connections from a freshly re-signed binary, so the app was run through `/usr/bin/python3`'s
`execv`, whose responsible process it allows. This is the same class of problem as the P15 Keychain
ACL: a rebuild invalidates a per-binary trust record. It does not affect shipped behaviour, but any
unattended Mac-side test must account for it.

## ADR-047 — Clipboard: hash+origin identity, sender-side limits, no interception, nothing stored

**Status:** accepted (Phase 3). Supersedes nothing; implements the §6 contract that P04/P05/P06 made
feasible.

**Context.** P04 established that a backgrounded Android app cannot even read its clipboard, and that
neither platform dedups anything at the OS level. P06 established that the macOS pasteboard's
`changeCount` is the only reliable signal of a user copy and that the app's own writes must be
recognised, or it will echo received text straight back.

**Decisions.**

1. **One pending transfer, identified by SHA-256 of the UTF-8 bytes, and an origin ID.** The origin ID
   is the sender's identity pin, which already exists, is stable across restarts, and is never sent
   anywhere it does not have to go. A receiver that applied a hash refuses to send that same hash back
   until it sees a different local copy. Both sides implement both rules.

2. **Limits are enforced by the sender before the body moves.** Offer carries the byte length and the
   hash; the receiver refuses an oversized or mismatched offer before it takes a body, and the sender
   refuses a pasteboard entry it will not carry. A 64 KiB UTF-8 limit keeps the whole transfer inside
   one control frame and keeps the pending buffer bounded.

3. **No clipboard interception and no history.** Sending is an explicit user action, not a watcher;
   receiving on Android is a user action too, because the platform will not allow otherwise. Nothing is
   persisted: no text, no hash history, no file, and logs carry hashes and byte counts only
   (SECURITY_AND_PRIVACY). This is why there is no TTL in the implementation even though the protocol
   allows one.

4. **Sensitivity markers are honoured at the sender.** `EXTRA_IS_SENSITIVE` on Android and
   `org.nspasteboard.ConcealedType` on macOS both refuse the send, with a visible explanation. Moving a
   password the user did not ask to move is a privacy failure that no amount of user consent at pairing
   time covers.

5. **Transport writes are queued, never performed on the caller's thread.** The ack was lost to
   `NetworkOnMainThreadException` when it was written from a Compose click handler. `SessionController`
   now has one writer coroutine draining an ordered outbox, which is also what guarantees an offer and
   its body are written in that order. Extends ADR-046 decision 1 from connect-path to all sends.

6. **Polling starts only for a trusted session.** The Mac pasteboard monitor is not armed while a peer
   is unconfirmed; otherwise an unpaired device could learn what the user copies just by connecting.

**Consequences.** The MVP is text-only, requires a tap on the Android side, and cannot be sold as
"clipboard sync" in the background on Android — that is the platform's rule, not a missing feature.
Rich content, images and file promises are out of scope for this phase.

## ADR-048 — Keepalive is allowed before the pairing is confirmed; content is not

**Status:** accepted 2026-11-24. Supersedes nothing; refines ADR-046 d4.

**Context.** A first pairing from a clean install could never complete on a real device. The phone confirms
its side and starts its 15 s heartbeat immediately; confirming on the Mac is a human act that finishes a
moment later. The receiver refused *every* frame while `awaitingConfirmation`, so it closed on the heartbeat
with `peer sent data before the pairing was confirmed`. The user's confirm then landed on a closed session
(`PAIRING_CONFIRM_FAILED session is not awaiting pairing confirmation`), no pin was stored, and both sides
retried in a loop — measured as an endless reconnect cycle at 2026-11-23 21:30, and reproduced on every
attempt.

The rule "no data before confirmation" exists to protect *user content*: clipboard text and video frames must
not reach an unconfirmed peer. A PING carries a sequence number and nothing else.

**Decision.**

1. While `awaitingConfirmation`, a PING is answered with PONG and nothing else happens: no pin is stored, no
   frame reaches the app, and the session is not authenticated. Every other frame type is still refused.
2. The receiver answers that PONG by writing to the socket directly, because `sendControl` requires an
   authenticated session and would also re-enter the serial queue the receive loop is already on.
3. `Data` slices keep their parent's indices, so the type byte is read as `frame[frame.startIndex + 1]`. The
   first version used `frame[1]` and crashed with `SIGTRAP` inside `isPingFrame` on the very first pairing.
4. A capture service claims the foreground state at the top of `onStartCommand` for `ACTION_START`, before any
   validation can return early. Returning from `startForegroundService()` without `startForeground()` is a
   fatal platform violation (`ForegroundServiceDidNotStartInTimeException`), which is exactly what happened
   when the consent data or the session was missing.
5. `POST_NOTIFICATIONS` is requested when capture starts, chained before the capture consent rather than
   alongside it. Two runtime dialogs launched in one frame lose one, and the consent must not be the one lost.
   Denial never blocks capture; it only removes the ongoing notification, which is the sole stop affordance
   outside the activity.

**Consequences.** First pairing completes unattended and the pin is stored once. The ongoing notification and
its Stop action exist on a fresh install. A capture start that fails validation no longer crashes the app, it
reports and stops. The keepalive exception is narrow by construction (one message type, answered, not
delivered), and the clipboard and media paths still refuse an unconfirmed peer.

## ADR-049 — The same phone returning supersedes a half-open session

**Status:** accepted 2026-11-24. Amends ADR-046 d1 ("one reader per session, the session owner").

**Context.** "One session per listener" protected the prototype from a second app instance stealing the
stream, but it made recovery from a half-open session a manual act: the Mac holds the dead session until its
120 s idle reap, the phone's retry budget gives up in ~60 s, and every measured flap ended with the phone in
`FAILED` and a human tapping Find the Mac. Reproduced three times, with the give-up landing 56 s before the
reap in the best case.

**Decision.** An additional connection is parked, not refused. It completes its TLS handshake while the live
session continues. When it verifies with the same identity pin as the live session, it supersedes: the old
session is closed (`superseded: the same phone reconnected`) and the new one is adopted, preserving the
one-live-session invariant. A different identity pin is dropped exactly as before. Because a flapping phone
opens a connection every ~2 s and can outrun a handshake, only one connection parks at a time and newcomers
while one is parked are refused. Mid-handshake states do not cancel the parked connection. The adopted
connection's handshake is completed explicitly (`completeHandshake`, once per adoption) because
Network.framework does not redeliver `.ready` to a replacement state handler — the first version of this fix
produced a zombie session with no receive loop and no PONG.

**Consequences.** A returning phone always wins its session back within one handshake (~1 s), no manual tap.
A port scanner or a second phone cannot take the slot (pin must match the live session). The parked
connection's frames are never read before adoption, so nothing bypasses the one-reader invariant.

## ADR-050 — Phase 5: the Classic HIDP peripheral is promoted into the product app, sandboxed

**Status:** accepted 2026-11-24.

**Context.** P17 proved the Mac-side Classic HIDP device role (SDP publication, outbound L2CAP control and
interrupt channels, HIDP handshake and report writes) against both phones with `48/48` arrow presses —
unsandboxed. The experiment deliberately carried no App Sandbox and its entitlement comment said the file
must not be adopted without review. Phase 5 promotes the peripheral into `DroidBridge.app`, which is
sandboxed by audit (ADR-026).

**Decision.**

1. `ClassicHIDPeripheral.swift` and the HID report descriptor move to `macos/Core/` unchanged in behaviour;
   the view becomes `Features/HID/HIDRemoteView.swift` on a new "HID Keyboard" tab. Every P17 safety control
   survives the promotion: one arrow per confirmed foreground check, no pointer, no text entry, explicit
   publish/stop.
2. The app adopts `com.apple.security.device.bluetooth` and links `IOBluetooth.framework`. The sandbox
   question is answered by measurement, not assumption: the sandboxed app published the HID SDP record
   (`published: yes`) with no entitlement rejection on 2026-11-24.
3. The shutdown obligations measured in P17 are inherited, not copied: `applicationShouldTerminate` stops
   the peripheral, and SIGTERM/SIGINT run the same path, because quitting while connected leaves a HID
   session no one owns and the next launch is refused with `0xE00002BC`.
4. The SDP service name becomes `DroidBridge HID`; the phone's HID host accepts any HID keyboard, so no
   phone-side coupling to the name exists.

**Consequences.** One app now owns discovery, transport, mirroring, clipboard and HID input. The P17
experiment target is removed from the project. Phase 5 device acceptance rows (pairing prompts, arrow
delivery on both phones) remain to be measured on the next device session; the prior P17 evidence does not
transfer to the promoted build without at least one verification run.

## ADR-051 — The 120 s idle reap stays; the phone's retry budget is raised to outlast it

**Status:** accepted 2026-11-24.

**Context.** The Mac closes a session that receives nothing for 120 s (`SecureListener` idle reap), on
the grounds that a phone whose radio went silent without a goodbye must be reaped or the session and
its single reader leak forever. The overnight-run finding made the cost concrete: the phone's retry
budget (6 attempts, ~60 s) expired while the Mac was still inside its own 120 s window, so a phone
that should have reconnected — a reboot gap, a Wi-Fi toggle — showed `GAVE_UP` to the user while the
Mac sat willing. ADR-049's supersede already made any reconnect that did arrive a first-try success,
so the remaining question was purely the budget mismatch.

**Decision.**
- The Mac's 120 s reap is unchanged. Shortening it reaps healthy Doze-suspended sessions; lengthening
  it widens the window where a dead session occupies the one-reader slot.
- The phone's budget rises from 6 attempts (~60 s) to 10 attempts (~150 s with the 30 s backoff
  cap), so the phone's patience now covers the Mac's reap window plus margin. Deliberate stops and
  pin mismatches remain terminal; nothing else changes about the retry rules.
- The `GAVE_UP` failure state gains an explicit "Try again" affordance, because any finite budget
  must end in a clear next step, not a dead-looking card.

**Consequences.** A real network outage now takes ~150 s instead of ~60 s to surface as `GAVE_UP`;
that is the correct trade — an outage is invisible to the user until they look at the phone, while a
spurious give-up during a reboot gap is user-visible failure. The 2026-11-24 measured scenario
(reboot gap, phone `GAVE_UP` 56 s before the reap) is now covered from the phone side; the
"Try again" button covers the rest.

## ADR-052 — HID teardown: the close is awaited, but SIGTERM-while-connected still leaves stale state (open)

**Status:** revised 2026-11-24 after four measured variants.

**Context.** Killing the connected app leaves the next launch's connect failing with
`L2CAP control channel (0x0011) failed (0xE00002BC)`. P17 recorded that an AppleEvent quit was clean
and a phone-side Bluetooth cycle did not clear the stale session, but the kill path was never fixed.

**Measured 2026-11-24 on the S24 Ultra (promoted build).**
1. `stop()` + `exit(0)`: stale.
2. `stop()` + 1.5 s hold + `exit(0)`: stale.
3. SDP withdrawn before closing, ACL explicitly dropped, L2CAP closed-callbacks awaited (bounded
   4 s), then `exit(0)`: stale.
4. Same awaited teardown, then `NSApp.terminate(nil)` (AppKit's normal exit): stale.
5. The relaunch failure mode differs by symptom: `0xE00002BC` (bluetoothd holds the session) or
   `0xE00002D6` (`kIOReturnTimeout` — the phone's HID host stops answering PSM 17 while it holds a
   half-open session). Both clear only after cycling the Mac's Bluetooth.

**Decision.**
- Keep the improvements that are correct regardless: the SDP record is withdrawn before the channels
  close, the ACL is dropped explicitly, the close callbacks own the cleanup (no eager nil), and the
  termination path waits (bounded 4 s) for them before the process exits through AppKit termination
  rather than `exit(0)`. These remove real races and are strictly better than the old code.
- SIGTERM-while-connected is recorded as an open known limitation for this macOS/phone pair: the
  stale session requires a Mac-side Bluetooth cycle to clear. The product implication: the HID
  peripheral should not be presented as kill-safe; the graceful quit path is the documented way to
  stop it, and a relaunch after a kill should surface the error and the recovery (publish again,
  cycle Mac Bluetooth if `0xE00002BC`/`0xE00002D6` recurs).
- Not papered over: the phone-side auto-reconnect behaviour (a HID host reconnects when the control
  channel drops while the record is still advertised) is the leading suspect for the half-open state
  and needs its own measurement before another fix attempt.

**Consequences.** The kill-path recovery is manual and documented; the shutdown code no longer races
the teardown; the wrong conclusion "the hold fixes it" is withdrawn.

## ADR-053 — One pin namespace per physical peer; `android-p08` is the canonical peer id

**Status:** accepted 2026-11-24.

**Context.** Measured the same day: the Mac's Mirror-tab listener (peer id `android-p08`) held a pin
from an earlier identity era while the phone presented its current one, producing a genuine
`PIN MISMATCH` that took a forget-and-repair cycle to clear. Investigation showed the pin store is
keyed by peer id and three peer ids had been in play across sessions: `android-p08` (Mirror tab and
`--p08-listen`), `com.droidbridge.app` (passed explicitly in an earlier session's harness flags), and
`android-p03` (the legacy P03 harness default). The same phone therefore had up to three independent
pin records, and which one applied depended on which Mac surface accepted the connection.

**Decision.**
- One peer id per physical peer, across every Mac surface: **`android-p08`** is the canonical peer id
  for the phone of record. The P03 harness default is unified to the same value; `--peer-id` and
  `--p08-peer-id` remain as explicit test overrides for runs that need a genuinely unpinned peer.
- No surface may pass a different peer id in a product or measurement run without recording why.
- The UI does not need to surface the peer id once the namespace is unified: with one id per peer,
  "forget the pin" means the same thing everywhere.

**Consequences.** Unifying the harness default means a harness run inherits the product pin —
intended, since the harness now exercises the same trust path the product uses; runs that need a
first pairing pass `--peer-id`/`--p08-reset-pin` explicitly (the flags were built for exactly that).

## ADR-054 — Confirmed pins are a set, not label-keyed slots (supersedes ADR-053's keying)

**Status:** accepted 2026-11-25.

**Context.** ADR-053 unified the peer id across surfaces, which surfaced a structural problem: the
pin store is keyed by a caller-chosen label, and the project has *two* phones of record (S24 Ultra
and OnePlus Nord). One canonical label means one pin slot, so the two phones would alternately PIN
MISMATCH each other and force a forget-and-repair on every switch. The label was never a security
boundary: a pin is the SHA-256 digest of the peer's SPKI (ADR-011), so the pin *is* the peer's
identity commitment.

**Decision.** Trust is set membership: `PeerPinStore` keeps a set of confirmed pins
(`confirm`, `isConfirmed`, `forgetAll`); a handshake is authenticated exactly when the presented pin
is in the set. The peer-id label is retained only for log lines and diagnostics, and existing
per-label keychain items migrate into the set on first read (malformed values are dropped, never
trusted). "Forget phone pin" clears the whole set — matching the product's single forget affordance;
per-pin forgetting is future UI work if more than a handful of peers accumulates.

**Security analysis.** Membership in a set of confirmed SPKI digests is equivalent trust to the
previous single-label comparison: the attacker's goal (a session accepted on a key the user never
confirmed) is unchanged and equally refused. The TLS verify block and the post-handshake second
layer both test membership.

**Verified on device 2026-11-25 (S24 Ultra, Wi-Fi ADB).** With a stale legacy pin the Mac refused as
`PIN MISMATCH … is not confirmed`; "Forget phone pin" logged `FORGET done peer-id=android-p08`; the next
connection was a first pairing with code `903 717` on both sides, and the reconnect after confirmation
logged `verify: pin matched … authenticated=true` with no code step. Cross-phone coexistence — both
phones' pins living in one set at the same time — is still untested: only one phone was paired at a time.

**Research note recorded on the way.** macOS 27 rejects `kSecReturnData` combined with
`kSecMatchLimitAll` with `errSecParam` (-50); multi-item keychain reads must fetch attributes first
and read data per item with `kSecMatchLimitOne`. The old `snapshot()` carried this broken query and
would silently have returned an empty map on this OS — the new store does not use that shape.

## ADR-055 — An unconfirmed pin is a pairing candidate on the Mac, never a dead handshake

**Status:** accepted 2026-11-25; device-verified 2026-11-26. The S24 Ultra presented a pin that was not
in a non-empty set and reached the code prompt; data moved only after the codes were compared.

**Context.** ADR-054 made trust set membership so both phones of record could be trusted at once. The
listener's decision code did not follow: `SecureListener` still read the set as three-state — empty
set meant "first pairing", non-empty set meant "member or refuse" — so with one phone confirmed, a
second phone's unknown pin failed the TLS verify block. Measured 2026-11-25: the OnePlus Nord's first
connection logged `verify: PIN MISMATCH pin 7eded50e… is not confirmed`, the handshake died with
`NWError -9808 bad certificate format`, and the phone sat in `connecting`. The only way to add the
second phone was "Forget phone pin", which throws away the first phone's trust — the exact problem
ADR-054 exists to remove.

**Decision.** One pure function, `PeerTrustPolicy.decide(pin:confirmedPins:explicitPin:)`, answers with
`.trusted`, `.candidate` or `.refused`, and the verify block and the post-handshake guard both call it
(they previously duplicated the three-state expression and could drift). A pin that is not in the
confirmed set is `.candidate`: the handshake completes so the short authentication code can be derived
and shown on both screens, the session is reported unconfirmed, no application data moves, nothing is
written to the keychain, and the existing confirmation timeout and the user's Reject still close it. `.refused` is now reachable only from the explicit-pin test mode.

**Residual consideration (recorded, not patched).** Refusing an unknown pin used to be instant; a
candidate session instead holds the single connection slot for up to `pairingTimeout` unless the user
confirms or rejects (300 s in the shipping mirror receiver, measured at 301 s on 2026-11-26; the test
harness defaults to 120 s). A peer on the LAN that keeps presenting a fresh identity can therefore keep
the listener busy for five minutes at a time, where before it was refused in milliseconds. The exposure
is availability only (it already existed on a fresh install, where every connection is a first
pairing), the user can stop and restart the listener, and the fix that mirrors the existing 3-strike
cap — stop accepting after N consecutive unconfirmed sessions and say so on screen — is deliberately
left for a build that can be measured on a device, because a blind change to the accept path is exactly
where an unattended session should not guess.

**Security analysis.** The control that admits a new peer is the code comparison, exactly as it was for
the first pairing when the set was empty: an impostor completes a TLS handshake with its own key, the
Mac derives a code from *that* key, the real phone shows a code derived from *its* key, the two do not
match, and the user rejects. Nothing about data access changed — `.candidate` sessions cannot send or
receive frames, and the store is written only on an explicit confirm. The refusal path was never a
security boundary against an unknown key; it was a policy gap that made the second phone unreachable.

**Consequences.** Adding a second phone is now: connect, compare the code on both screens, confirm on
each side that shows a prompt. The Mac may hold both phones' pins at once (ADR-054), and a phone whose
identity changed is recoverable through the same prompt if the user is told the code. The phone side
still refuses a Mac pin it does not know (its store is a single-Mac case and the revoke affordance is
one tap), so a Mac that is reinstalled needs "Forget the Mac's pin" on the phone — recorded as a
deliberate asymmetry, to be revisited if a second Mac ever enters the picture.

**Verification.** Seven unit tests cover the policy (`PeerTrustPolicyTests`, 79 macOS tests pass, 0
failures). The device row was blocked on the night of the change (Keychain ACL prompts, ADR-056) and
was measured the next morning on the S24 Ultra. The regression was first seen live: with the S24's pin
confirmed, the OnePlus's unknown pin produced `verify: PIN MISMATCH … is not confirmed` and the
handshake died with `NWError -9808`. After the change, the same shape of event — a pin absent from a
**non-empty** set — reached the code prompt instead: at 07:36 the S24's old identity connected with
`verify: pin matched f68f9062…`, and at 07:37 a freshly installed app presented a new identity
(`c0054183…`) which the Mac reported as `verify: unknown peer pin … awaiting confirmation` with
`authenticated=false code=419 987`. The phone showed `419987` and the instruction to compare; the codes
were checked equal on both sides before anything was confirmed; then the Mac logged `pairing confirmed
locally; pin stored` and `AUTHENTICATED`, and a listener restart reconnected with `verify: pin matched
c0054183… authenticated=true`. No frame, media or clipboard event crossed the connection during the
118 s it spent unconfirmed.

## ADR-056 — The Keychain ACLs make every ad-hoc rebuild need the owner's password (proposed)

**Status:** proposed 2026-11-25. Measurement incomplete; no code changed.

**Context.** The app is ad-hoc signed (`CODE_SIGN_IDENTITY: "-"`, ADR-026 defers a real identity to
Phase 9), and it keeps the Mac's TLS identity key and the confirmed peer-pin item in the login
keychain. The file-based keychain authorizes access per *binary*: a rebuild changes the cdhash, so the
new binary is a stranger to both items. Measured 2026-11-25: launching a freshly built app produced
three SecurityAgent prompts, each asking for the login-keychain password — "wants to export key",
"wants to use your confidential information stored in com.droidbridge.p08.peer-pins", and (during the
handshake) "wants to sign using key". Nothing can dismiss them without that password: Deny makes the
read and the signature fail, so the session stops at `NWError -9858 handshake failed`. Earlier sessions
got through because the owner was at the Mac; tonight's unattended run could not, which is why
ADR-055's device row is open.

**Options.**

1. *Document and live with it.* Cheap, honest, and unnecessary for users: a released build is signed
   with a stable Developer ID, so its ACL grant survives updates and the prompt appears at most once.
   It only taxes the development loop, where every rebuild needs one human click.
2. *Stable development signing identity.* A self-signed code-signing certificate in the login keychain
   gives every build the same designated requirement, so "Always Allow" is granted once. It needs a
   certificate the project does not have and a keychain password to create it — a Phase 9 companion.
3. *Move both items to the data protection keychain.* `kSecUseDataProtectionKeychain` plus a
   `keychain-access-groups` entitlement scopes items to the app's group rather than to a binary hash,
   which is also the shape Phase 9 needs (ADR-025). The open question is measurable: an ad-hoc signed
   app with no provisioning profile may be refused with `errSecMissingEntitlement` (-34018), and the
   migration cannot read the legacy items without the very prompt it is trying to remove, so it would
   be a user-visible re-pair, not a silent copy.

**Decision.** Do not migrate blind. Measure option 3 first in a throwaway build (does the group work
under ad-hoc signing; does a rebuild keep access), and if it does, migrate with an explicit re-pair
step and a recorded decision. Until then, option 1: the development workflow documents the prompt and
the fact that Deny leaves the app unable to complete TLS.



## ADR-057 — Media playback audio ships in the app, with `RECORD_AUDIO`
- Date: 2026-11-26
- Status: Accepted (owner-approved 2026-11-26: "ship media audio" and the `RECORD_AUDIO` permission)
- Context: ADR-018 kept audio out of the MVP because the permission was speculative. P12 has since measured the API: media playback capture is bit-exact for an app that allows capture, and silence (frames still advancing) for `ALLOW_CAPTURE_BY_NONE`, notification usage and voice-communication usage (ADR-045). The owner asked for the media half to ship. Call audio stays impossible: cellular voice is not capturable, and `USAGE_VOICE_COMMUNICATION` measures as digital silence anyway.
- Decision: Ship media playback audio in `:app`.
  - Capture with `AudioPlaybackCaptureConfiguration` for `USAGE_MEDIA`, `USAGE_GAME` and `USAGE_UNKNOWN` only. `USAGE_VOICE_COMMUNICATION`, `USAGE_NOTIFICATION`, `USAGE_ALARM` and `USAGE_SYSTEM` are deliberately absent, so calls, notifications and alarms are never captured.
  - Encode AAC-LC 44 100 Hz stereo 128 kbit/s with the platform encoder, one access unit per `AUDIO` (0x21) message; `AUDIO_FORMAT` (0x22) announces codec, rate, channels, bitrate and the base64 AudioSpecificConfig before the first access unit.
  - Decode on the Mac with `AVAudioConverter` and play through `AVAudioEngine`; never invent a container. The AudioSpecificConfig is the decoder cookie, not a guess.
  - Measure amplitude on both ends (phone PCM, Mac decoded PCM, 16-bit units) and treat frames without signal as silence, never as success (ADR-045 §4).
  - The toggle defaults on, asks for `RECORD_AUDIO` before the projection consent, and a denial continues with screen-only mirroring. An audio failure never stops the video pipeline.
- Alternatives: PCM over the wire (rejected: ~1.4 Mbit/s for the same content); Opus (rejected: the universal decoder is present, but the Android encoder set is less predictable across OEMs than `c2.android.aac.encoder`); microphone capture (rejected: records the room and everything the user says rather than the app's playback, and is a far larger privacy claim); capturing calls or notifications (rejected: impossible for calls, and ADR-045 measures notifications as silence).
- Consequences: `RECORD_AUDIO` and the `microphone` foreground-service type are now shipping permissions, so Play's microphone declaration and data-safety form must say "audio from other apps' media playback, for live listening on the user's own Mac, never stored or transmitted to a server"; the runtime prompt is part of the share flow; apps that opt out of capture are heard as silence and the UI must not claim otherwise; audio and video are not re-timed, so the offset must be measured rather than assumed (unmeasured at acceptance; the 2026-11-26 dry run measured a +276.5 ms mean audio-later-than-video offset on this path - see the P12 doc).

## ADR-058 — Media-rate faults drop frames; only a sustained flood closes the session
- Date: 2026-11-26
- Status: Accepted (dry-run evidence; review requested because it changes a documented refusal bound)
- Context: `SecureListener` limits media frames to 120/s with a 120-frame burst and closed the session on the first over-budget frame, as an anti-DoS bound. Measured on the S24 Ultra 2026-11-26: opening the notification shade during a single healthy capture produced `CLOSED framing/protocol violation: peer exceeded 120 media frames per second` (12:12:33). The shade's 120 Hz animation delivered more than one second of frames inside a second; the phone's own encoder was at 15-80 fps. Closing the session turns ordinary device animation into a dead mirror plus a full reconnect.
- Decision:
  - Over-budget media frames are dropped as they arrive. Nothing is queued or decoded, so receiver work stays bounded.
  - The session closes only after 600 consecutive over-budget media frames (~5 s at the 120/s budget), which separates a display-rate burst from a peer that keeps overrunning the budget.
  - Sustained 120 media frames/s is unchanged, and every control-frame violation still closes immediately (32/s).
  - Drop counts are logged (`media frames dropped=N accepted=M`) so a burst is visible in the evidence.
- Alternatives: raise the burst capacity to 240 (rejected: postpones the same close, a 3 s animation still dies); drop without any ceiling (rejected: removes the refusal bound); cap the sender instead (better long-term fix, not tonight - see consequences).
- Consequences: a display-rate burst costs a few dropped frames (a brief stutter) instead of a reconnect; a real flood still ends the session in ~5 s. Verified on the fixed build: HOME -> shade and five rapid shade toggles kept the session (`received=4680`), and the drop path fired once (12:02:59, `dropped=120 accepted=1721`, session alive). The phone can still emit 66-80 fps whenever the shade animates because its configured 30 fps is not enforced for surface input; a sender-side cap is the right next fix, with this change as the receiver-side safety net. The phone now also stops capturing about 15 s after the transport closes (a sender grace window that still allows the reconnect re-announce; measured 2026-11-26) instead of encoding into a dead connection. A sender-side cap (`KEY_MAX_FPS_TO_ENCODER = 60`, nominal rate 30) landed the same day and the S24's encoder accepted it (`cap=60`), so the worst-case burst is halved before it reaches the receiver (six shade toggles peaked at 55.9 fps, receiver dropped=0); an encoder that rejects the key starts without it.

