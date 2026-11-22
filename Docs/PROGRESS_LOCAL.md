# Local Development Progress

**Status:** Phase 0 complete. Phase 1 in progress: P00, P01, P02 and P04 verified on the target
device; P03 implemented and verified on loopback plus three physical S24 Ultra runs (pairing, Wi-Fi
drop and reconnect, and the final USB-disconnected acceptance run). All six P03 acceptance criteria
are met.

**Last updated:** 2026-11-20

**2026-11-21 (toolchain upgrade: AGP 9.5.2, Compose 2026.11.01, compileSdk 37.1):** Took the scheduled upgrade instead of suppressing it. AGP `9.5.1 → 9.5.2`, Compose BOM `2026.09.00 → 2026.11.01`, and `compileSdk` moved from 37.0 to **37.1** via the new `compileSdkMinor` property (API 37 ships minor versions; Compose 2026.11 requires the 37.1 AAR metadata, which was the `checkDebugAarMetadata` failure blocking the bump earlier). `targetSdk` stays 37 (ADR-021) and `minSdk` stays 31. `platforms;android-37.1` is published, so CI installs it alongside 37.0.

Verified: `./scripts/verify-all.sh --skip-macos` passes end to end (694 tasks, assemble + unit tests + lint for every module). With the upgrade applied, lint reports **no** version-availability findings at all — checked by temporarily removing the `libs.versions.toml` ignores and re-running `:app:lintDebug`. The ignores stay as insurance against the next release, which is a deliberate policy: a periodic "newer version exists" notice must not turn a green commit red.

**2026-11-21 (P10 core claim measured; P08 keyframe recovery measured; P15 ACL answered):** P10 now has the number it was missing. Instead of a second live decoder — which deadlocks (`RemoteVideoDecoder_DecodeFrameWithOptions` blocks in a semaphore wait) and, run inline, blocked the main thread so the sink went silent after one queue depth — the receiver records every received frame (`P08_RECORD_DIR`) with a wall-clock timeline, and the phases are decoded **offline** afterwards (`ffmpeg` → 64×128 gray → mean luma). Results on the S24: baseline 196.4 (645 frames), **`FLAG_SECURE` 1.0 (809 frames, range 0.9–1.1)**, off again 196.4 (646), password screen 238.1, OTP private 80.3 / public 80.5 with the shade open, shade 64.2 and 114.4. A live stream of 809 black frames is exactly the evidence byte counts could not give. Locking the device then produced no frames at all, and the device said why: `MIRROR state=stopped: system onStop (lock / screen-sharing chip / user stop)` — locking ends the projection, so lock-screen content cannot leak and the private/public redaction row is unobservable in this path rather than unresolved.

Three defects were fixed on the way, one of them user-visible: the stream validator could wait for a recovery IDR that nothing requested, stalling a real mirror indefinitely; evidence now runs before the render gates; and luma logging is rate-limited because os_log drops per-frame info lines at stream rates, which had made a working sink look dead.

P08: keyframe recovery measured — `FAULT_INJECTED` → `FAULT_FLUSH` → `KEYFRAME_REQUEST reason=scripted-fault` → `status=rendering`, `dropped=0`. A 30-minute live session is running (`svc power stayon usb`, since a sleeping screen ends the projection). Rotation is blocked: `cmd window user-rotation lock 1` had no effect and `mCurrentOrientation` stayed 0, so that row needs a human hand. The phone's window manager wedged mid-run (constant stale `NotificationShade` focus, no hierarchy) and a reboot recovered it.

P15: the ACL question is answered from a real failure rather than a hypothesis — ad-hoc rebuilds invalidate the Keychain ACL, `securityd` logs the stale cdhashes, and the app blocks silently before startup with no window and no output; signing with the stable Apple Development identity keeps the ACL valid across rebuilds and the identity pin (and therefore the Android peer's stored pin) survived.

Measurement builds sign with the new `macos/App/DroidBridge.measurement.entitlements`, which adds one scoped `temporary-exception.files.absolute-path.read-write` for `/private/tmp/p10-rec/`; shipped and acceptance builds keep the audited three-key entitlement set unchanged.

**2026-11-21 (ADB operator mode adopted; P10 attempted and blocked):** Per owner instruction, `AGENTS.md` now permits ADB to act for the operator at steps that would otherwise need a human — tapping, typing, navigating app or system UI, confirming a verified pairing prompt, accepting a consent dialog for an owner-directed test. Boundaries kept: ADB may never deliver or shape the functional input under test (the Mac's Bluetooth HID reports stay the sole P16/P17 input source); a pairing code must be verified on both sides before ADB confirms it; device screen capture and clipboard read/write stay prohibited; every report must state what ADB did and the USB/debugging state.

Used it to drive P10 end to end without a human at the phone: discovered the Mac through the NSD picker (the picker listed both `DroidBridge` and the leftover `DroidBridge-P03-Mac`, so the instance must be chosen deliberately), resolved `192.168.31.109:59599`, then connected. That surfaced three real defects, none of them the mirroring protocol:

1. **The receiver advertised but never accepted.** The P08 tab needs its "Listen" button pressed; the advertisement comes from the separate P02 advertiser, so an unattended run connected to a listener that was not there and failed with `SSLHandshakeException: UNEXPECTED_MESSAGE`. Added a `--p08-listen` harness flag that starts the listener with the window.
2. **A stale Keychain ACL blocked startup.** `securityd` logged `displaying keychain prompt ... ACL ... cdhash H"73999..."`, listing older build cdhashes, because the P08 pin item survives rebuilds while each ad-hoc build has a new cdhash. This is the P15 ACL question arriving in practice, and it also hides behind a silent stall.
3. **After purging the items, the app starts but never listens and emits nothing** — consistent with a silent Keychain stall in identity/pin-store creation under the sandbox. Not yet diagnosed or worked around; the P10 live matrix cannot run until it is.

Methodology correction: `--p08-allow-capture` exists precisely because the display layer sets `preventsCapture`, which is why earlier `screencapture` frames were byte-identical. Also, redirecting the sandboxed app's stdout to `/tmp` is now refused (`sandbox_extension_issue_file_to_process failed`), so macOS-side evidence must come from `os_log` or the in-app event list, not a shell redirect.

macOS tests still pass (67/67).

**2026-11-21 (build health: Android lint now fails on version-availability checks only):** `scripts/verify-all.sh --skip-macos` fails at `:app:lintDebug` with three errors that are **not** code defects — `AndroidGradlePluginVersion` (AGP 9.5.1 → 9.5.2 available) and `GradleDependency` (Compose BOM 2026.09.00 → 2026.11.01). They are time-dependent: lint only sees them when it can reach the network, and the last green run predates those releases. Proven independent of this session's work: `git diff` shows zero changes to `android/gradle/libs.versions.toml` and `android/app` in every commit here. Attempted the routine bump and **reverted it**: Compose BOM 2026.11.01 requires `compileSdk 37.1+` (`feature:dashboard` compiles against android-37.0, and the newer platform SDK is not installed), so the upgrade is a deliberate maintenance task, not a patch. State is otherwise green: `assembleDebug` + `testDebugUnitTest` pass for every module, and 67/67 macOS tests pass.

**2026-11-21 (P14 rows A–F re-measured autonomously on the S24 Ultra):** Ran the matrix unattended with the app driven by `am start` extras rather than injected touch. Results: foreground start succeeds with no prompt; backgrounded 5 min keeps the session (`HEARTBEAT 2 → 22`, `cgroup 5:freezer:/`, 0 stalls); screen off 5 min keeps it (`Dozing`, `22 → 42`); Wi-Fi off/on gives `RECONNECT n=2,3` → `state=connected` with 0 false stalls; a real reboot gives `BOOT_COMPLETED received` → `startForegroundService accepted` → `state=connected` → `HEARTBEAT n=1 / PONG n=1` 0.3 s after the broadcast; and the notification shows live content (`connected · 52 heartbeats · 52 pongs · 3 reconnects · last heartbeat 1s ago`, importance 2, ONGOING/NO_CLEAR). Two operational findings recorded: the app requires the peer pin up front, so an unattended run must learn the Android pin from one failed attempt and restart the listener with `--expect-pin` (then `authenticated=true`); and the Mac receiver was dying to AppKit automatic termination between phases, now held open for `--p08-mirror`/`--advertise-on-launch`/`--listen`. Deep Doze and overnight remain open — they need the handset unplugged, since `force-idle` refuses while charging. USB connected, USB debugging on, wireless debugging off. See [P14](prototypes/P14-background-persistence.md), ADR-043.

**2026-11-21 (P11 complete — macOS Local Network is not enforced on 27.2.1; methodology error corrected):** Finished P11's three open rows and found the assumption behind them was wrong. The product app never triggered the Local Network control because it only **advertises** (registration runs in `mDNSResponder`) and accepts **inbound** connections (peer-initiated) — neither is gated. Built an isolated probe (`macos/Experiments/P11LocalNetwork/`, sandboxed with the production entitlements: `network.client` + `network.server`, `NSLocalNetworkUsageDescription`, `NSBonjourServices`) that performs the gated operations, and measured four launch paths and two install locations. Result: Bonjour browse and outbound TCP to `192.168.31.112:5555` **both succeed, no alert is presented, and the app never appears in Settings → Privacy & Security → Local Network**, including under a **Finder double-click by the owner** and under a **`launchd` agent**, which TN3179 says is not exempt. So there is no deniable state on this OS build and the rows are unreachable rather than failed. **Correction to earlier evidence:** `nehelper` logged `Local network allowed by preference for Terminal (com.apple.Terminal)` — macOS attributes the operation to the responsible process and auto-allows Terminal's children, and `open` from a shell does not break that chain, so the original 2026-11-17 "allowed-state" measurement measured Terminal, not the app. Also confirmed: the Settings list **does** have `+`/`−` (TN3179 correct, the widely cited blog wrong), and `nehelper` logs `Failed to find <bundle> using neagent` for dead app identifiers, which is why stale rows accumulate. Recorded as [P11](prototypes/P11-local-network-permission.md), **R-38**, **ADR-044** (do not depend on Mac-side consent, keep declarations and the `.waiting` recovery path, never measure permission from a script). Not tested: any macOS other than 27.2.1, Developer ID builds, second user account. Mac-only run: no Android device used.

**2026-11-21 (P17 ACCEPTANCE PASS — S24 Ultra, USB unplugged, both debugging modes off):** Ran the acceptance burst with **USB disconnected, USB debugging off and wireless debugging off**; `adb devices` returned zero entries, so no ADB channel existed. Driven entirely from the Mac, one Classic HIDP session: SDP published (handle `1330188580`), CoD `0x2540`, control `0x0011` + interrupt `0x0013` open, 48 reports sent (`UP, DOWN, LEFT, RIGHT` × 12, 900 ms apart), and the receiver on the phone went **`14/14/14/14 → 26/26/26/26`** — 48 of 48 delivered with no drops, duplicates or stuck keys. Counters were read from the phone screen by the owner, since no debugging channel was available by design. Environment restriction was enforced, not merely declared: the device was unreachable by ADB for the whole run. This closes P17 as **GO** for the Classic HIDP input route on Android 12 and Android 17; it is not a production-input approval, because the experiment sends one key per explicit user action and open mapping, text entry and the rest of the input matrix are still unproven. See [P17](prototypes/P17-classic-hidp.md), [evidence](prototypes/evidence/p17/README.md), ADR-041, ADR-042.

**2026-11-21 (P17 delivery on the target S24 Ultra; SIGTERM shutdown fix):** Paired the S24 Ultra to the Mac through a **phone-initiated** bond: the phone showed code `695843`, the owner read the same code on the Mac's pairing dialog and clicked Pair on both sides — the agent could not read the Mac dialog itself (no screen-capture capability), so the numeric match was confirmed by the owner, as required. Both sides then held a bond (phone `Bonded devices: 10`, Mac `Jayanth's S24 Ultra C0:D5:E2:F6:D7:C2`). Delivery on the target device: SDP published (handle `1330188580`), CoD `0x2540`, control `0x0011` + interrupt `0x0013` open, receiver `0/0/0/0 → UP 1, DOWN 1, LEFT 1, RIGHT 1`, Android `BTHH_CONN_STATE_CONNECTED` over `BT_TRANSPORT_BR_EDR`; a second delivery reached `2/2/2/2`, and a 48-press burst landed `+12` per direction (`2 → 14` each) with no drops. **Defect found and fixed:** `pkill` (SIGTERM) killed the app without running `applicationShouldTerminate`, leaving the SDP record published and the L2CAP channels open, so the next launch was refused with `0xE00002BC` while Android reported `BTHH_CONN_STATE_DISCONNECTED`; recovery was a phone-side Bluetooth cycle (`svc bluetooth disable` → `enable`), and `P17ClassicHIDP` now installs `SIGTERM`/`SIGINT` handlers that run the same `stop()` path (verified: an AppleEvent quit logs `stopped` and reconnects without an adapter cycle). Test state: USB connected, USB debugging enabled, wireless debugging disabled — **so this is not the debugging-off acceptance run**. Also noted: the S24 carries a stale LE HID host record for the Mac from the P16 attempts next to the BR/EDR record. See [P17](prototypes/P17-classic-hidp.md), [evidence](prototypes/evidence/p17/README.md).

**2026-11-20 (P14 on the S24 Ultra: ADR-023's premise holds on the target device):** Re-ran the P14 matrix on S24 Ultra `SM-S928B` / Android 17 / API 37 with the same build used on the OnePlus. Backgrounded for ten minutes the session never stalled — `/proc/<pid>/cgroup` stayed `5:freezer:/` and `HEARTBEAT n=3 → n=44`; five minutes with `mWakefulness=Dozing` gave `n=6 → n=26`; a Wi-Fi off/on cycle produced 3 reconnect attempts with 0 false stalls and resumed in the same process; a real reboot gave `BOOT_COMPLETED received` → `startForeground succeeded` → `state=connected` → `HEARTBEAT n=1 / PONG n=1` within 450 ms. So the OnePlus freeze is an OEM deviation and ADR-023's premise holds on the target device; ADR-043 is revised accordingly (background sync allowed on this device class, with stalled-session detection and foreground-only degradation elsewhere). Two new findings: (1) **API 37 requires `ACCESS_LOCAL_NETWORK`** — without it every LAN connection failed as `SocketTimeoutException`, which is indistinguishable from a dead listener, and the experiment now declares and requests it; (2) the first stall watchdog was too aggressive and replaced a healthy worker during a genuine Wi-Fi outage (`worker stalled: no heartbeat for 50678ms`), so the stall check now applies only while the session claims to be `connected`; re-verified with 0 false stalls. Observation to follow up: on the S24 the first `KeystorePinStore.remember()` after a clean install threw `IllegalStateException: could not persist the pin` before succeeding on retry. Not run: deep Doze/overnight (handset was charging over USB, `force-idle` refused with `Unable to go deep idle; stopped at INACTIVE`), notification dismissal and battery drain. Test state: USB connected, USB debugging enabled, wireless debugging disabled. See [P14](prototypes/P14-background-persistence.md), ADR-043, R-37.

**2026-11-20 (P14: connectedDevice FGS is frozen once the app is backgrounded on the OnePlus):** Ran the P14 matrix on OnePlus Nord AC2001 / Android 12. Passing: (1) start from a visible activity needs no runtime prompt; (2) a background start is rejected 30 s after backgrounding with `ForegroundServiceStartNotAllowedException` (accepted at 6 s, inside the post-visibility grace window); (3) `BOOT_COMPLETED` starts the service after a real reboot and it reconnects with `HEARTBEAT n=1 / PONG n=1` within 500 ms; (4b) a Wi-Fi off/on cycle in the foreground reconnects in one backoff step (`RECONNECT n=6` → `state=connected` 17 s after Wi-Fi returned). **Failing: the backgrounded case.** Heartbeats stopped ~40 s after the app left the foreground while the service stayed `isForeground=true` and `dumpsys activity oom` reported `fg +50 F/S/FGS`, `state: cur=FGS` — yet `/proc/<pid>/cgroup` showed `6:freezer:/frozen`. Foregrounding moved it to `freezer:/thaw` and beats resumed; backgrounding returned it to `/frozen`, so it is an OEM freeze, not a session defect. Consequence: on this handset background clipboard sync does not survive backgrounding under our constraints, so ADR-043 records foreground-only as the default until the S24 Ultra is measured; Doze, overnight, notification and battery rows are unreachable here and remain unmeasured. Two defects found and fixed: a start with a new host/port was ignored while running (stale target for the whole run), and the notification was refreshed inside the synchronised log write, deadlocking the worker against the main thread — the session then froze with no error while the service still claimed "connected"; notification updates now run on a main-thread supervisor, the notification shows the heartbeat age, and a watchdog restarts a worker that has not beaten for 50 s. Test state: USB connected (`adb tcpip 5555` also armed), USB debugging enabled, wireless debugging disabled. ADB was used for setup, reboots and read-only diagnostics only. See [P14](prototypes/P14-background-persistence.md), ADR-043, R-37.

**2026-11-20 (P14 background session persistence started; blocked on device access):** Added the isolated `android/experiments/background-session` app (applicationId `com.droidbridge.experiment.backgroundsession`) which reuses the production `SecureSession` (TLS 1.3, mutual pinning) and `KeystorePinStore`, so a pass reflects the real client rather than a toy socket. It is a `connectedDevice` foreground service started only from the UI or `BOOT_COMPLETED`, sending a real `PING` every 15 s and requiring the `PONG` before the next beat, with 2/4/8/16/32/60 s backoff, a persistent low-importance notification with state and a Disconnect action, and no wake lock or battery-optimisation exemption. Manifest carries exactly the plan's permission set plus `INTERNET` and `RECEIVE_BOOT_COMPLETED`; no Bluetooth permission. Measured: (1) start from a visible activity passes — `startForeground succeeded for connectedDevice` with no runtime prompt on API 31; (2) a background start 6 s after backgrounding is *accepted* (post-visibility grace) while 30 s after it is rejected with `ForegroundServiceStartNotAllowedException: startForegroundService() not allowed due to mAllowStartForeground false`; (3) `BOOT_COMPLETED` is inconclusive — the receiver is `exported="false"` so the adb broadcast shortcut is impossible, and a real reboot removed the ADB transport (`persist.adb.tcp.port=5555` did not put adbd into TCP mode after boot) and left the handset off Wi-Fi, so the result could not be read; the survival matrix, Doze forcing, notification and battery tests have not run. Fixed a defect found during bring-up: a start request with a new host/port was ignored while a session ran, silently keeping a stale target. Test state: USB disconnected, USB debugging enabled, wireless debugging disabled. See [P14](prototypes/P14-background-persistence.md).

**2026-11-20 (P17 stability and recovery measured on OnePlus Nord):** Measured robustness on top of the initial delivery result. A 48-press burst (`UP,DOWN,LEFT,RIGHT` × 12, 900 ms apart, one HID session) advanced the receiver from `UP 3, DOWN 1, LEFT 1, RIGHT 1` to `UP 15, DOWN 13, LEFT 13, RIGHT 13` — `+12` per direction, so every report was delivered exactly once with no drops, duplicates or stuck keys. Reconnect behaviour: quitting while connected used to leave the next session refused with `0xE00002BC` even though Android already reported `STATE_DISCONNECTED`, i.e. the stale state was Mac-side, persisted at least 8 minutes, and was cleared only by cycling the phone's Bluetooth adapter (bond survived as `[BR/EDR]`). Fixed by calling `stop()` — close both L2CAP channels, close the ACL, withdraw the SDP record — from `applicationShouldTerminate`; a quit while connected now logs `stopped` and an immediate relaunch reconnects and delivers a key with no adapter cycle. A force-quit or crash still needs that cycle, which is recorded as a known limitation. Test state: USB disconnected, USB debugging enabled, wireless debugging disabled, ADB over Wi-Fi. ADB was used for setup and read-only counter reads only. See [P17](prototypes/P17-classic-hidp.md) and [evidence](prototypes/evidence/p17/README.md).

**2026-11-20 (P17 independent implementation delivers input on OnePlus Nord):** DroidBridge's own `P17ClassicHIDP` app now delivers real HID input to Android, so the Classic route no longer depends on the AGPL probe. On OnePlus Nord AC2001 / Android 12 / API 31 the app published the HID SDP record (`handle 1330188580`), applied Class of Device `0x2540`, opened the L2CAP control (`0x0011`) and interrupt (`0x0013`) channels from the Mac, and moved the focused P16 receiver's counters from a zeroed baseline to `UP 2, DOWN 1, LEFT 1, RIGHT 1` across two sessions (`UP` first, then `UP, DOWN, LEFT, RIGHT` over a single control/interrupt pair). Android's HID host reported `60:3E:5F:31:D0:6D [BR/EDR] … STATE_CONNECTED`. Test state: USB disconnected (`connected=false`), **USB debugging enabled** (`adb_enabled=1`), wireless debugging disabled (`adb_wifi_enabled=0`), ADB over Wi-Fi `192.168.31.64:5555`. Five host-side obstacles were found and fixed or documented: (1) macOS 26 gates IOBluetooth behind `kTCCServiceBluetoothAlways`, and until consent every call blocks and surfaces as `0xE00002BC`/`0xE00002D6` — these are **not** protocol failures; (2) `IOBluetoothDevice.pairedDevices()` deadlocks on the main thread, so IOBluetooth is now warmed up off-main and peers are refreshed on a background queue; (3) the single Classic pairing-agent slot was held by CleanMyMac4's `bluetooth-device-fetch-xpcservice`, so `replyUserConfirmation` was never delivered and the bond failed `0x0000001F` — the working bond was phone-initiated; (4) Android holds the previous HID link after the Mac process exits, so reconnects return `0xE00002BC` and each run must use one HID session; (5) a stale Mac-side bond cannot be removed by any public API. Also added the missing numeric-comparison accept/decline UI and a manual *Connect / Pair* control. Not proven: S24 Ultra, a both-debugging-modes-off acceptance pass, reconnect/relaunch/bond-recovery, sustained typing, and the app's own programmatic pairing. ADB was used for setup and read-only counter reads only; no ADB command produced functional input and no screen capture was taken. See [P17](prototypes/P17-classic-hidp.md), [evidence](prototypes/evidence/p17/README.md), ADR-041, ADR-042, R-36.

**2026-11-20 (independent P17 Classic HIDP implementation added; delivery not yet demonstrated):** Added `macos/Experiments/P17ClassicHIDP/` (target `P17ClassicHIDP`, bundle ID `com.droidbridge.experiment.p17classichidp`), independently authored from the HID Profile transaction set and Apple's public `IOBluetooth` APIs. It implements the HID SDP record, outbound ACL, L2CAP control `0x0011`/interrupt `0x0013`, HIDP transactions (`GET_REPORT`/`SET_REPORT`/`GET_PROTOCOL`/`SET_PROTOCOL`/`GET_IDLE`/`SET_IDLE`/`HID_CONTROL` + handshakes), `0xA1` input reports with report-ID and boot-layout handling, temporary Class of Device `0x2540`, and automatic/sudden-termination suppression. `xcodegen generate`, `xcodebuild` and strict `codesign --verify --deep --strict` pass. Result so far on OnePlus Nord AC2001 / Android 12: the SDP record publishes (`handle 1330188580`), the class of device applies, and the ACL opens, but the control channel is refused (`L2CAP control channel (0x0011) failed (0xE00002BC)`) while the handset is dozing with the screen off; `HidHostService` keeps the device at state `0`. **Key delivery from this app is therefore not demonstrated**, and the earlier preliminary GO still rests on the upstream app as test vehicle. No AGPL-derived source is in the repository: the earlier reference comparison was deleted before commit. Test state: USB disconnected, USB debugging enabled, wireless debugging disabled, ADB over Wi-Fi. See [P17](prototypes/P17-classic-hidp.md) and ADR-041.

**2026-11-20 (P16 BLE NO-GO on Android; P17 Classic HIDP working on OnePlus — preliminary):** Mac-to-Android HID input now works over **Bluetooth Classic HIDP**, not BLE. BLE HOGP is a closed negative result for Android: the phone bonds the Mac but never creates a HID input device (`HidHostService.mInputDevices` empty, `mTargetDevice: null`) and the Mac receives no GATT reads, writes or subscriptions. The same outcome was reproduced with the **unmodified** upstream `darwin-bt-remote` app in its default BLE mode, so this is host classification rather than a GATT defect: the Mac advertises on public address `60:3E:5F:31:D0:6D`, which is also its BR/EDR address, so Android lists it `[ DUAL ]` and routes HID over Classic. Running the upstream app in Classic mode on the same hardware published an HID SDP record (`handle=1330188580`), opened L2CAP `0x0011` control and `0x0013` interrupt channels, made Android register `MacBook Air Keyboard/Mouse/Consumer Control/System Control` with `STATE_CONNECTED`, and delivered arrow press/release reports counted by the focused P16 receiver (`UP 8, DOWN 3, LEFT 3, RIGHT 1`, with matching down/up pairs; machine-captured baseline `0/0/0/0` at 18:05:50 → `UP 2, DOWN 2, LEFT 1` at 18:05:56). This is a **OnePlus Nord AC2001 / Android 12 / API 31** preliminary result and used the **AGPL upstream app as the test vehicle**, not DroidBridge code; no S24 Ultra evidence exists and no upstream source may be adopted. Source review corrected an earlier note: the upstream Classic path uses only public `IOBluetooth` APIs and its `_privateBool` KVC helper is dead code. macOS automatic termination killed the Mac app 16 s after the channels opened in one attempt, silently dropping the connection — an independent implementation must prevent that and surface state. Test state: USB disconnected (`connected=false`), USB debugging enabled (`adb_enabled=1`), wireless debugging disabled (`adb_wifi_enabled=0`), ADB over Wi-Fi; this is not final acceptance. ADB was used for setup and read-only sanitized diagnostics only; no ADB command produced functional input, and no screen capture was taken. See [P17](prototypes/P17-classic-hidp.md), [P16](prototypes/P16-ble-hid.md), ADR-041.

**2026-11-20 (P16 BLE comparison POC built, not device-tested):** Added an isolated reference-derived BLE-only comparison at `macos/Experiments/P16BLEHIDReference/` (target `P16BLEHIDReference`, bundle ID `com.droidbridge.experiment.p16blehid-reference`) containing upstream `LICENSE` and in-app AGPL attribution, excluding Classic HIDP, Accessibility/Direct Input and the upstream service-changed workaround. Build and strict `codesign --verify --deep --strict` pass and the bundled license is present, but the target is unvalidated beyond build/sign and produced no input evidence. It remains local isolated research pending the owner's AGPL decision; do not push, share or merge it into production. See ADR-040.

**Current gate:** Phase 1 (prototypes P00–P17). P00 baseline complete for both devices. **P01 is
validated on the S24 Ultra (Android 17 / API 37)**: consent, foreground service, surface-fed H.264,
rotation, lock/`onStop`, user stop, 20 start/stop cycles, secure-content protection and consent-token
replay were exercised. P04 is verified on both Android 12 and Android 17. **P05 is completed on Android 12 and Android 17:** the Mac→Android
clipboard matrix passes on both, including Android 17's generic "Copied." confirmation and the
`ACCESS_LOCAL_NETWORK` requirement outside the picker; the active-mirroring-FGS integration context
is deferred to P08. **P07 is completed:** the Annex-B → AVCC → format-description → `AVSampleBufferDisplayLayer`
pipeline decoded and displayed a generated 640×360 fixture (`readyForDisplay=true`,
`status=rendering`), recovered after a flush, measured 496.6 ms startup latency, rendered 480×640
and 1280×720 fixtures, and was verified with a screen capture: the app UI was captured but the video
area was black (`preventsCapture=true`). **P15 personal-use identity persistence is verified locally:** on 2026-11-17 the Release app built and launched locally; its macOS bundle ID is now `com.droidbridge.app`; the canonical `~/Applications/DroidBridge.app` was verified and resolved Bonjour alone. Its ad-hoc signature, sandbox entitlements, hardened-runtime flag and absent `get-task-allow` were verified. Gatekeeper rejected the ad-hoc build as expected. The corrected app-specific login-Keychain implementation produced the same identity pin in two separate Release harness processes. Disposable-Keychain persistence and orphan-key fail-closed tests pass, as does the full macOS suite (67/67); the Release build succeeds. Android reconnect without re-pairing and Keychain ACL behavior across code-signing changes remain untested. The earlier generated key item remains untouched and is not adopted by the new versioned tag. The owner confirmed personal-use-only scope and deferred Developer ID signing, notarisation and quarantined distribution checks; see [ADR-033](TECHNICAL_DECISIONS.md#adr-033--defer-macos-developer-id-distribution-while-droidbridge-is-personal-use-only), [ADR-034](TECHNICAL_DECISIONS.md#adr-034--persist-personal-use-mac-identity-in-the-login-keychain-until-a-profile-is-available), and [P15](prototypes/P15-signed-build.md).
**P06 is completed on macOS 27.0.1 with a platform limitation:** `accessBehavior=alwaysAllow`, no
prompt appears, self-write suppression works, and TCC logs contain no pasteboard service. Apple
Development signing did not change it; the deny/ask UX must be re-tested on a macOS version that
implements the prompt.

**2026-11-12 — P08 live mirroring now works end to end on the S24 Ultra; P10 in progress.** A
three-part fix chain made the live view usable: the Android start/pipeline moved off the main thread
(`92bb313`), and the macOS receiver rebases sender encoder PTS onto a host-clock control timebase
(`8937f3d`) — before that the Mac buffered ~2 s and froze (constant `max PTS` in FigVideoQueue
logs). A synthetic `FLAG_SECURE` toggle was then added to the mirroring experiment (`899cc87`) for
P10: with mirroring live, enabling it made the Mac show **black** and disabling it restored the
live image, and opening the Slice banking app (non-invasive observation only) also produced
protected/black output. P10 remains in progress for its remaining cases; P08 acceptance (rotation,
stop/teardown, keyframe recovery, 30-minute stability, final wireless-only run) is **not** closed.
Evidence: `Docs/prototypes/P10-secure-screen-protection.md`.

**2026-11-17 — P08/P10 receiver hardening and live-device follow-up.** The historical
notification-shade freeze is not attributed to a proven root cause: the old report called the
120 fps media limiter a control-frame guard, but code review showed media and control are separately
accounted. Implemented a monotonic bounded-burst token bucket (sustained limits retained),
summary-only media logging, 30-frame cadence for SwiftUI counter publication, signed PTS decoding,
AVCC/NAL validation with IDR detection, monotonic PTS and keyframe-boundary checks per encoder-format
epoch, IDR-gated decoder recovery, and Android non-increasing PTS rejection. Activity recreation
reattaches to the service-owned process-held authenticated session. **Automated:** Mac tests 65/65
(macOS 27.2.1 arm64); Android mirroring tests 22/22 and Debug APK assembled. These results alone do
not prove the shade defect fixed. In a later live S24 Ultra run (`RZCY81TKBSV`, SM-S928B, Android
17/API 37, `CP2A.260605.016.S928BXXS6EZJ1`), the shade appeared on the Mac mirror and was dismissed
without session closure; this is one non-failure reproduction, not a confirmed root-cause fix. The
synthetic notification with a public version showed its redacted public text on phone and Mac. The
private-only synthetic notification was posted, but its contents were not visible in the captures,
so that result is inconclusive. Rotation UI reattachment remains untested on-device. **Device test
transport/state:** USB connected; USB debugging enabled (`adb_enabled=1`); wireless debugging
enabled (`adb_wifi_enabled=1`); ADB connected over USB. P08's final USB-disconnected/debugging-off
acceptance, glass-to-glass latency and device-cost measurements remain open. No device settings or
app data were changed; mirroring was left active. See [P08](prototypes/P08-live-mirroring.md) and
[P10](prototypes/P10-secure-screen-protection.md).

**P02 is validated and accepted on the target device (2026-11-09).** Picker approval, approved-only
rediscovery, 60-second sustained duplex (198/198 messages, 0 mismatches), recovery from a real
airplane-mode outage without re-approval, Android process recreation and Android reboot all passed.
**Mac sleep/wake passed on the third attempt**: `pmset` recorded Sleep at 21:41:50 and Wake at
21:42:15, and the phone reconnected to the same Mac port 64615 at 21:42:14 with no picker
(536/535 messages, 0 mismatches, 1 expected lost reply). The **Mac advertiser/cold restart** is also
measured and exposes one required re-approval when the Mac's service instance changes. Advertising
and inbound need `ACCESS_LOCAL_NETWORK`; the picker, approved-only discovery and outbound TCP do
not. That permission's revocation was not applied to a running process until reboot.

**P03** has matching codes on both screens, duplex data on a confirmed session, authenticated
reconnect with stored pins, and a changed Mac identity correctly refused. A real Wi-Fi drop closed
the session on both ends and the phone reconnected with the stored pin without re-pairing. The
final acceptance run passed with USB disconnected and USB debugging, wireless debugging and
Developer Options all off; that wireless-only run is P03 evidence, not a P01/P02 re-run.

## Device and environment

- Primary target device: Samsung Galaxy S24 Ultra, model **SM-S928B** (device `e3q`), region India.
- Android: **17, API 37** — verified on the device via `getprop`, not owner-reported.
- One UI: **9.0** (`ro.build.version.oneui=90000`) — verified on the device.
- Build: `CP2A.260605.016.S928BXXS6EZJ1` (`S928BXXS6EZJ1`), security patch **2026-10-05**,
  `ro.build.type=user`, `ro.boot.verifiedbootstate=green`.
- `android.permission.ACCESS_LOCAL_NETWORK` is present on the device and the NSD service
  (`android.net.nsd.INsdManager`) is live, so the API 37 discovery path is testable here.
- Mac: MacBook Air (M1, 2020), 8 GB, macOS 27.0.1 (26A434), arm64.
- Xcode 27.2 (27B52), Swift 6.4.2, macOS SDK 27.2, xcodegen 2.46.0.
- JDK: Zulu 17.0.21 on PATH (build daemon runs JDK 27 from Homebrew); Gradle 9.8.2; AGP 9.5.1.
- Android SDK: platforms 34/36/37.0, build-tools 34–37, adb 37.0.2.
- Temporary test device: OnePlus Nord CE (AC2001), Android 12 / API 31, build `AC2001_11_F.24`,
  security patch 2023-12-05, `ro.debuggable=0`, bootloader unlocked (verified boot `orange`).
  Treated as an ordinary non-rooted device throughout.
- USB debugging: development-only allowance; final release must not require it.
- Bluetooth: optional and non-core.

## Repository state

- Branch: `development`.
- Content: `AGENTS.md`, `README.md`, `Docs/*`, `.gitignore`, `shared/protocol/` (pure Kotlin/JVM),
  `android/` (10 modules), `macos/` (xcodegen project), `scripts/`, `.github/workflows/`.
- Build/test tooling: Gradle wrapper 9.8.2 + AGP 9.5.1 for Android; Xcode 27.2 + xcodegen for
  macOS; Gradle wrapper 8.10.2 for `shared/protocol`. Both native toolchains are present on this
  machine, so both shells build here.

## Completed

- [x] Product concept and constraints documented.
- [x] Two-native-app direction documented.
- [x] Wi-Fi-first / optional Bluetooth decision recorded.
- [x] Research baseline added.
- [x] Prototype plan added.
- [x] Security and permission policies added.
- [x] Agent workflow added (2026-11-07).
- [x] **2026-11-08 research pass — official-source verification of every high-risk dependency:**
  - Android 17 local network permission model and the NSD advertising/discovery asymmetry.
  - Android 17 certificate-transparency default and its interaction with pinned custom anchors; ECH default.
  - MediaProjection consent/FGS/stop rules, selected-app capture, resize/visibility callbacks.
  - Android 15+ screenshare protections (notifications, password fields, OTP activities) and `FLAG_SECURE`.
  - Android clipboard focus/IME gate, clipboard toast, sensitive-clip flag.
  - macOS local network privacy (TN3179) and the requirement that the Mac advertise under the new design.
  - macOS 15.4+ pasteboard access prompts — newly identified risk to automatic clipboard sync.
  - Initial macOS Bluetooth HID refusal evidence and Android `BluetoothHidDevice` reverse direction; R-32 source review identified separate BLE/Classic routes. P16's first attempt used short-form registered UUIDs and received CoreBluetooth's UUID-not-allowed error. Reference-source review revealed its use of canonical Bluetooth-base UUIDs for GATT registration; our POC now applies that independently and awaits manual service-publication retest. P17 Classic HIDP remains independent and pending.
  - Knox `RemoteInjection` permission/authorisation eligibility and DeX API removal on Android 16+.
  - Audio playback capture boundaries; impossibility of call audio; Android 17 background-audio hardening.
  - Transport comparison (TLS/TCP vs WebRTC) and the macOS mDNS/local-network interaction.
  - Background session persistence: `connectedDevice` foreground service type, its zero-prompt prerequisite, the absence of a timeout, boot-start legality, and Doze's device-wide network suspension.
  - Foreground-service notification visibility (`POST_NOTIFICATIONS`) and the Play Console FGS declaration.
  - Identity and TLS mechanics: Keystore-generated self-signed certificate on Android, `swift-certificates` on macOS (Security framework cannot create certificates), SPKI pinning, and the macOS server-side peer-authentication default.
  - Distribution requirements: Play's target-API floor and the restricted `ACCESS_LOCAL_NETWORK` permission declaration, per-type foreground-service declarations with demo videos, macOS Developer ID/hardened runtime/notarisation, and the App Sandbox network and keychain entitlement rules.
  - Media pipeline: surface-fed encoding (no colour handling in app code), the API-31 quality floor applying only to VBR, profile rejection and `KEY_LEVEL` not being a cap, B-frames defaulting to zero, the Annex-B→AVCC mismatch between MediaCodec and CoreMedia, and the deprecation of `AVSampleBufferDisplayLayer`'s enqueue API in favour of a macOS 27 replacement.
- [x] Research register, decisions, permissions matrix and prototype instructions updated from that pass.
- [x] **2026-11-08 shared protocol module — H.264 media framing (`shared/protocol/`):** Annex-B start-code splitting (both start-code lengths, trailing-zero padding, rejection of unframed or empty NAL units), codec-specific-data parsing into bare SPS/PPS, and Annex-B→AVCC conversion with 4-byte big-endian lengths plus IDR detection; `mirror.format` payload with deterministic JSON and Base64 parameter sets. 31 unit tests, all passing on JVM 21 (`./gradlew test`); two mutation checks confirmed the start-code and padding tests fail when the rule they cover is broken. This is the producer side of `NETWORK_PROTOCOL.md` §7 and ADR-027. **Not device-tested** — no phone was involved, and nothing here proves encoder output, only the framing rules.

- [x] **2026-11-09 Phase 0 — repository and environment (complete).**
  - `android/`: Kotlin + Compose shell, Gradle KTS, AGP 9.5.1, `compileSdk`/`targetSdk` 37,
    `minSdk` 31. Modules: `app`, `core:{common,protocol,network,pairing,security}`,
    `feature:{dashboard,clipboard,mirroring,settings}`. SDK levels and lint policy live once in the
    root build file; versions and SDK numbers live once in `gradle/libs.versions.toml`.
  - `shared/protocol` is consumed as a **composite build** (`includeBuild` + dependency
    substitution), not copied. `core/protocol/MediaFraming.kt` is a facade whose only job is to make
    that linkage a compile-time fact.
  - `macos/`: Swift + SwiftUI shell, `project.yml` (xcodegen) as source of truth with the generated
    `.xcodeproj` committed. App Sandbox on from the first build with exactly the three audited
    entitlements; `NSLocalNetworkUsageDescription` and `NSBonjourServices` declared.
  - `scripts/`: `env-baseline.sh` (regenerates the P00 baseline), `verify-all.sh` (Phase 0 gate and
    CI entry point), `android-install.sh` (development install helper).
  - `.github/workflows/verify.yml`: shared protocol + Android assemble/test/lint on a clean runner,
    read-only token, no secrets.
  - **Verified:** 31/31 shared protocol tests; Android `assembleDebug` + `lintDebug` clean (5 real
    lint findings fixed at source); macOS Debug and Release builds; 4/4 macOS unit tests; all three
    rebuilt from a clean checkout with no `local.properties`; APK installed and launched on the
    OnePlus with no crash and no ANR.
  - **Audited:** Android manifest declares no permissions (only the AndroidX-injected app-private
    `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` appears in the APK); macOS Release carries exactly
    `app-sandbox` + `network.client` + `network.server` and **no** `get-task-allow`.
  - **Defect found and fixed:** Xcode injected `get-task-allow` into Release builds, which the
    notary service rejects. Fixed with `CODE_SIGN_INJECT_BASE_ENTITLEMENTS: NO` on Release only.
  - Reports: `Docs/prototypes/P00-environment-report.md`,
    `Docs/prototypes/PHASE0-repository-and-environment.md`.
  - **Not verified:** the dashboard's rendered content on the device — the phone was PIN-locked, so
    `screencap` returned nothing and the UI hierarchy belonged to the keyguard. The app's launch,
    resumed state and absence of crashes are confirmed; the pixels are not.

## Not started

- [x] Create the Android project shell (`android/`, Kotlin + Compose, Gradle KTS).
- [x] Create the macOS project shell (`macos/`, Swift + SwiftUI, Xcode project).
- [x] Add `.gitignore` (Gradle, Android, Xcode, IDE, secrets).
- [x] Add CI/build scripts.
- [x] Capture exact host toolchain and temporary-device details (P00 host half).
- [x] Capture S24 Ultra build/API details (P00 device half).
- [x] Run P01 MediaProjection prototype (Android 12 and Android 17).
- [x] Run P02 Android 17 LAN strategy prototype (picker path, sustained duplex, recovery).
- [x] Run P03 pairing/transport prototype.
- [x] Run P04 clipboard prototype (Android 12 + Android 17).
- [x] Run the remaining P05 clipboard prototype (Android 12 + Android 17).
- [x] Run P06 macOS pasteboard prompt prototype (completed on macOS 27.0.1 with a platform limitation).
- [x] Run the P07 Mac video receiver prototype.
- [ ] Run the P08 end-to-end stream prototype. **Live mirroring works (2026-11-12)**; measured stream evidence exists. On 2026-11-17, one physical shade run mirrored normally and the session remained active; historical freeze root cause, rotation UI restoration, debugging-disabled final acceptance, latency and device cost remain open.
- [x] Run P09 initial remote-input checks (historical, scoped NO-GO; not a final verdict on the newly identified Mac Bluetooth HIDP route).
- [ ] Run P10 secure-screen/screenshare-protection tests. **In progress:** historical evidence shows synthetic `FLAG_SECURE`, Slice (non-invasive), masked password output and no app-persisted frames. On 2026-11-17 one shade run mirrored normally; the synthetic OTP with a public version showed its redacted text. The private-only notification content was not visible in the captures, so that outcome remains inconclusive. See P10 evidence; do not generalize these observations.
- [ ] Run P11 macOS local-network permission tests. On 2026-11-17, the ad-hoc Release app advertised and `dns-sd` browsed/resolved `_droidbridge-dev._tcp` on macOS 27.2.1. Prompt visibility/wording and denied-state behavior remain unverified because native UI inspection was unavailable; no TCC reset or permission change was made. Added a denied-state Settings recovery affordance; 67 macOS tests and the Release build passed. See [P11](prototypes/P11-local-network-permission.md).
- [x] Run P13 pinned TLS/CT prototype.
- [ ] Run P14 background-session persistence prototype (screen-off, Doze, overnight, reboot).
- [x] Implement and validate local Mac identity persistence for P15: two separate Release harness processes reported the same login-Keychain pin; disposable-Keychain persistence and fail-closed tests passed. Android reconnect without re-pairing remains outstanding; see [P15 evidence](prototypes/P15-signed-build.md).
- [ ] **Deferred until distribution is a goal:** Developer ID signing, notarisation, stapling, quarantined-copy launch and Apple Developer Program membership/Account Holder access. Phase 9 release remains blocked; no distribution claim is made.
- [x] Verify the owner-reported Android 17 / One UI 9 version on the device.

## Current blockers

- **Bluetooth HID control is unverified.** P16's first short-form UUID registration returned CoreBluetooth's UUID-not-allowed message. The isolated POC now uses canonical SIG UUIDs and includes Battery/Device Information/PnP ID services. Apple's public CoreBluetooth descriptor API cannot create Report Reference `0x2908`, so the POC is Boot Keyboard only; service publication remains untested after the change. The already-bonded OnePlus stayed disconnected with no active HID host input; no key was sent. S24 compatibility is unknown. P17 Classic HIDP remains independent and pending. Accessibility, ADB functional control/data paths, root, Knox and hidden APIs remain prohibited. See [P16 result](prototypes/P16-ble-hid.md).
- **Automatic background clipboard sync is not a release claim.** Android requires focus/IME for reads; macOS 15.4+ can prompt or deny programmatic pasteboard access.
- **Android 17 LAN path is resolved for the primary design.** P02 proved the picker path on the S24 Ultra: approval, resolve, connect, 60 s duplex and approved-only recovery all work. Discovery, approved-only discovery and outbound TCP work **without** `ACCESS_LOCAL_NETWORK`; advertising and inbound listening require it. Revoking it does not take effect for a running process until reboot. The product must keep the picker as its consent mechanism and must not treat the permission as the sole boundary. The one open P02 item is Mac sleep/wake; the Mac advertiser/cold restart is measured and requires a one-time re-approval for a new service instance.
- **Mac-as-Bluetooth-HID-peripheral remains conditional and unproven overall.** P16's short-form UUID attempt returned CoreBluetooth's uuidNotAllowed message; it now uses canonical UUIDs and the required Device Information/Battery services. Public CoreBluetooth cannot create HOGP Report Reference `0x2908`, limiting the test to Boot Keyboard. Manual Mac publication and Android Boot Host compatibility are untested; the earlier paired OnePlus attempt stayed disconnected. No S24 BLE compatibility is known. P17 Classic HIDP is independent.
- **Background clipboard sync is best-effort, not guaranteed.** The `connectedDevice` foreground service keeps the session alive in normal use, but Doze suspends network access device-wide, so the design is reconnect-on-resume rather than an always-on promise. Whether a socket survives a full night is unproven (U11).
- **The macOS app cannot be released without a paid Apple Developer Program membership.** Notarisation requires a Developer ID certificate, only the Account Holder can create one, and an unnotarised app is blocked by Gatekeeper on current macOS (R-27). This is an external dependency on the owner, not a technical uncertainty, and it blocks Phase 9 rather than Phase 0.

## Tests and evidence

- **2026-11-20 (P16 Boot-only profile correction; Mac retest pending):** Added isolated macOS CoreBluetooth and Android receiver-only keyboard endpoints. Android unit test, APK assembly and lint pass; APK has no Bluetooth permission. The initial P16 implementation used short-form SIG UUIDs; CoreBluetooth returned its uuidNotAllowed message. P16 now registers canonical SIG UUIDs and has the HOGP-required Battery and Device Information/PnP ID services plus the mandatory read-only Report Map. Battery remains a separate primary service because Battery Level is not referenced by the map. Research against the Bluetooth HOGP 1.2 spec and Apple's installed CoreBluetooth header found that public `CBMutableDescriptor` supports neither custom HID Report Reference `0x2908` nor Report Protocol construction; it supports only User Description/Presentation Format and auto-creates CCCDs. P16 is therefore intentionally Boot Keyboard only; Android must select Boot Protocol and subscribe. Build and strict signature verification pass. Native Mac window observation capability was unavailable, so post-correction service publication was not tested. Earlier setup on OnePlus Nord AC2001 / Android 12 tapped the already-bonded device while advertising; it remained disconnected, no input device/subscription existed, and no key was sent. USB connected yes; USB debugging yes; wireless debugging no. No S24 result. `darwin-bt-remote` commit `e64cae983b8816e7e2f067e79025ffc49faf7e2d` was only a linked reference; no code copied. See P16, R-34, ADR-039.
- **2026-11-17 (P11 partial, MacBook Air M1 / macOS 27.2.1):** Release app advertised Bonjour service `_droidbridge-dev._tcp` on ephemeral ports 59555 and 59558; `dns-sd -B` found it and `dns-sd -L` resolved the host, port and TXT fields before and after the `.waiting`-state handling change. Source declarations match the actual service. The first-launch alert and denied-state path were not visually verified (native UI capability unavailable); TCC was not reset and settings were not changed. Added Local Network explanation and an Open System Settings button on advertiser failure. macOS suite 67/67 and Release build passed. Full evidence and untested cases: `Docs/prototypes/P11-local-network-permission.md`.
- **2026-11-12 (P08 live + P10 partial, S24 Ultra):** live mirroring verified end to end after
  `92bb313` (Android start/pipeline off main thread) and `8937f3d` (macOS PTS rebased to a host-clock
  control timebase; the earlier freeze was a FigVideoQueue that stopped draining at a constant max
  PTS). Session evidence: `TLSv1.3`, pairing confirmed (`peerPin=3b3d878a…`), encoder
  `c2.qti.avc.encoder` at `590x1280` from `1080x2340` content, `dropped=0`, `stopReason=active`.
  P10 synthetic check (`899cc87`): `P10 FLAG_SECURE test: ON` (09:51:59.219) → Mac **black**;
  `OFF` (09:52:01.307) → live image restored. Slice banking app observed non-invasively → black.
  **ADB state at 2026-11-12 log capture:** device reachable at `192.168.31.106:5555` (Wi-Fi ADB);
  no USB transport listed by `adb devices -l` on this host at that moment. P08 rotation, stop/teardown,
  keyframe recovery, 30-minute stability and screen-off/keyguard stops were subsequently measured and
  are detailed in `Docs/prototypes/P08-live-mirroring.md`; P08 final debugging-disabled acceptance,
  latency/device-cost measurement and device re-test of rotation UI remain open. P10 password pixels,
  historical shade freeze and notification-service OTP posting were evidenced. The 2026-11-17 live
  follow-up observed one shade run with continuous mirroring and public-version redaction; private-only
  OTP visual outcome remains inconclusive. See `Docs/prototypes/P10-secure-screen-protection.md`.
- **2026-11-09 (Phase 0):** 31/31 shared protocol tests (JVM); Android `assembleDebug`,
  `testDebugUnitTest`, `lintDebug` all pass; macOS Debug + Release builds and 4/4 unit tests pass;
  clean-checkout rebuild of all three passes; APK installed and launched on OnePlus Nord CE
  (Android 12 / API 31), `ResumedActivity` held, 0 crash entries, 0 ANRs.
- **Not captured (Phase 0):** a device screenshot. The phone was PIN-locked, so `screencap`
  produced empty output and `uiautomator` dumped the keyguard.
- **2026-11-09 (P01, S24 Ultra):** encoder `c2.qti.avc.encoder`; 3120×1440, 2560×1440, 1920×1080 and
  1280×720 all accepted at 30 fps; output 1280×720 Baseline/level 3.1 VBR with SPS 21 / PPS 8; a
  189 s session produced 12,610 frames / 32,960,629 bytes / 211 keyframes at a 1,392 kbps mean with
  0 crashes; rotation produced `onCapturedContentResize` 1080×2340 ↔ 2340×1080 and `resize()` was
  accepted; lock fired `onStop()`; 20/20 start-stop cycles completed with 2,005 frames and 0
  crashes in the same process; FLAG_SECURE content measured luma 0 versus 159 for normal content.
- **2026-11-09 (P02, S24 Ultra):** 60 s duplex 198/198 messages, 0 mismatches, 0 errors; airplane
  outage recovery 475/474 with approved-only rediscovery and no picker; process recreation and
  reboot both rediscovered and connected; inbound requires `ACCESS_LOCAL_NETWORK` on a clean boot,
  while advertising throws `Missing local network permission` without it.
- **2026-11-09 (verification):** `JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
  ./scripts/verify-all.sh` passed end to end: **78/78 shared-protocol tests**, Android `:app` plus
  the media-projection, discovery and clipboard experiments assembled, unit-tested and linted, and
  **37/37 macOS tests** passed. The JDK 17 override is required because the shared-protocol Gradle
  8.10.2 wrapper cannot run on the default Homebrew JDK 27 (it fails with a bare version string).
  This is an environment fact to document in the reproduction steps, not a code defect.
- P00–P04 now have device evidence. P05 onward remain unrun; no later prototype has been started.

## USB / debugging state

- **Phase 0 device session (2026-11-09):** USB connected **yes**; USB debugging enabled **yes**;
  wireless debugging enabled **no**. Development session only.
- **P01/P02 validation sessions (2026-11-09):** USB connected **yes**; USB debugging enabled
  **yes**; wireless debugging enabled **no**; Developer Options enabled. The APKs were installed
  over ADB and the device UI was driven over ADB for the automated runs. The final wireless-only
  gate was not re-run for P01/P02; the documented USB-disconnected run belongs to P03. This is
  recorded so the P01/P02 evidence is not presented as wireless-only evidence.

## Workflow from here

1. P01 is validated on the S24 Ultra; its remaining items are plan-only extras (screen-sharing chip,
   `KEY_LATENCY`, still-screen repeat-frame comparison, `flush()` without `start()`).
2. **P02 is validated and accepted**, including Mac sleep/wake. The Mac advertiser/cold restart is
   measured: a new Mac service instance requires one picker re-approval before approved-only
   reconnect works. Record that as a product/architecture limitation for Phase 2, not an open test.
3. Update the detailed reports with the final results, review the diff, commit and push to
   `development`.
4. Do not begin P05 or later until the owner agrees. The Android 12 backup device cannot help P02
   because the API is 37-only; P01 already has Android 12 evidence.

## Next task

**Reconciled 2026-11-10.** The section headings and the "Not started" checklist below had drifted
behind the evidence: P05, P06 and P07 were still titled "partial" in this file while their own
reports, `PHASE_STATUS.md` and `PHASES_AND_ACCEPTANCE.md` recorded them as completed, and P05/P06/P07/
P09/P13 were still unchecked as "not run". The evidence was in this file all along — only the
headings and the checklist were stale. They now match the reports; nothing was promoted on the
strength of a commit message.

The next prototype is **P08 — end-to-end mirroring**, which is the critical path (P01 + P03 + P07 are
all accepted).


## Agent handoff status (2026-11-08)

Local Phase 0 is complete and pushed. The cloud agent may now review the Phase 0 commit — specifically
the build configuration, the manifest/entitlement audit in `PERMISSIONS_MATRIX.md` §6, and the two
Phase 0 reports — before local work continues into P01. Cloud must not modify app code or claim
prototype/device results.

### P02 — Android 17 local networking (blocked, 2026-11-09)

Built the Mac half of Path A step 1: `NWListener` advertising `_droidbridge-dev._tcp` with a
`v`/`sid` TXT record, an echo handler, and a UI showing live connection events.

**Verified:** 10 macOS tests pass; TCP duplex echo over loopback is byte-exact; the listener accepts
an inbound connection from the phone (192.168.31.64) and binds all interfaces; the phone reaches
the Mac (0% ping loss).

**Blocked, two independent reasons:**
1. The picker path needs `android.net.nsd.DiscoveryRequest`, which exists only in the API 37
   platform (confirmed against the installed SDK, not documentation). The OnePlus is API 31, so
   the prototype cannot run on it at all. U1, U2 and U16 are unanswered.
2. Bonjour does not publish from this host. The listener reports `.ready` but `dns-sd -B` never
   lists it, and an inbound connection from the phone times out before completing. A minimal
   non-sandboxed control binary fails the same way, so it is not the app's code or the sandbox.
   The macOS Local Network permission was never requested (no `kTCCServiceLocalNetwork` in `tccd`)
   and no prompt appeared. Little Snitch 6.2.3 and an Urban VPN configuration are present and may
   also interfere. Both are owner decisions.

**Defect found:** SwiftUI builds `TabView` content lazily, so a `@StateObject` inside a tab's view
is not created until the tab is selected — `--advertise-on-launch` silently did nothing. The model
is now owned by the `App`.

Report: `Docs/prototypes/P02-local-networking.md`.

### P04 — Clipboard Android to Mac (matrix unrun, 2026-11-09)

Built `android/experiments/clipboard/`, a separate app declaring no permission at all, with a probe
that runs one matrix row per action and logs a greppable observation line.

**Verified:** 9 unit tests pass over the reporting contract; lint clean with `warningsAsErrors`;
installs and launches with zero crashes and no clipboard-related permission.

**Two findings that change the matrix:**
1. `ClipDescription.EXTRA_IS_SENSITIVE` is API 33, so the sensitive row cannot be exercised on
   API 31 at all. The probe logs `NOT_APPLICABLE` rather than a misleading `false`.
2. The read gate is a focus gate, and the keyguard holds input focus, so no button can be pressed.
   Every matrix row is unrun.

**Defect found:** the log line interpolated clipboard content raw, so a newline would have split
one observation across two lines. Now escaped.

Report: `Docs/prototypes/P04-clipboard-android-to-mac.md`.

### P03 — Transport and pairing (protocol layer, 2026-11-09)

Built the two parts of P03 that can be proven without a live peer, and that carry the real risk:

**`shared/protocol/session/Framing.kt`** — length-prefixed framing per ADR-012. The length prefix is
validated before allocation, so a peer announcing 2 GiB is rejected on the header alone. The prefix
is decoded unsigned, because a signed read turns `0xFFFFFFFF` into `-1` and every bound check then
passes. A reader that hits a framing error latches failed rather than resynchronising.

**`ShortAuthenticationCode`** in both Kotlin and Swift — the pairing code from ADR-011. Inputs are
sorted by content so both sides agree without a role, and length-prefixed so `("ab","c")` and
`("a","bc")` cannot collide.

**Verified:** six fixed vectors are asserted in both languages, so the two implementations cannot
drift silently — which matters because a drift would show two different codes on two screens and
reject a valid device.

**Later superseded:** the TLS session, sequenced control envelope, pin stores and pairing
confirmation were built afterwards and exercised on the device — see "P03 — secure session, device
run 2026-11-09" below. This section is kept as the record of the protocol-only milestone.

Report: `Docs/prototypes/P03-transport-and-pairing.md`.

### P01 capture — first verified on the OnePlus (Android 12)

The OnePlus was unlocked and consent granted, so the full pipeline ran for the first time:

**124 frames, 359,092 bytes, 3 keyframes**, negotiated `video/avc` 1280x720, SPS 21 bytes, PPS 8
bytes, foreground service `isForeground=true`, 0 crashes. Frames kept arriving on a still screen,
which is what `KEY_REPEAT_PREVIOUS_FRAME_AFTER` is for.

**Defect found and fixed:** `configure()` failed with `CodecException 0x80001001`
(`OMX_ErrorUndefined`) and the stack named no key. A device-side bisect over the format keys
(`FormatBisect.kt`) isolated the rule: **this encoder requires `KEY_LEVEL` whenever `KEY_PROFILE` is
set**, and also requires `KEY_I_FRAME_INTERVAL`. Profile alone fails; profile plus level succeeds.
The first bisect pass was misleading — it looked like adding `I_FRAME_INTERVAL` was the fix — so the
identical format was run three times to rule out a first-configure quirk before re-basing the
bisect on the known-good format. Level 3.1 is now set alongside Baseline.

This bug is invisible to unit tests and would have blocked the entire mirroring path.

### P04 — VERIFIED on device (2026-11-09)

The full matrix ran. The decisive row is the negative one: with the launcher in front,
`hasPrimaryClip` returned **false** — the focus gate observed, not assumed. Screen off also returns
false.

Three findings that change the product:
- **`EXTRA_IS_SENSITIVE` does not block a read.** A clip written with the flag set was read in full.
  It is a preview-suppression hint, not an access control. DroidBridge must be cautious with it but
  must not claim the OS protects it.
- **There is no OS-side size cap.** A 100 KB clip came back complete, so the 100 KB figure in the
  plan was an assumption. DroidBridge must impose its own cap.
- **Nothing deduplicates.** Reading the same content twice returns it twice, so loop prevention must
  be DroidBridge's own, keyed on content hash and origin ID.

The Android-to-Mac user story wording is now written from observation and recorded in the report.

## S24 Ultra session — 2026-11-09

The target device was attached. Everything below is measured on the device, not reported.

### Device baseline (P00) — complete

**SM-S928B** (`e3q`), Android **17**, API **37**, One UI **9.0**, build
`CP2A.260605.016.S928BXXS6EZJ1`, security patch 2026-10-05, `ro.build.type=user`,
`ro.boot.verifiedbootstate=green`. USB connected, USB debugging on, wireless debugging off. The
owner-reported specs were all confirmed rather than assumed.

### P02 — first contact verified on the target device (picker path)

This was the prototype that could not run on the OnePlus, because `DiscoveryRequest` only exists from
API 37. This first run is superseded by the completion run at the end of this document; the
sustained-duplex, recovery and permission tests were added there.

- `ACCESS_LOCAL_NETWORK` requested and **granted**.
- `DiscoveryRequest` with `FLAG_SHOW_PICKER` → the **system picker appears**
  (`com.google.android.connectivity.resources/.NsdPickerActivity`), titled "Choose a device to
  connect", listing discovered peers.
- Selecting a peer → `onServiceFound` with `host=null port=0`, then `resolveService` →
  `host=192.168.31.109 port=47999`.
- TCP connect from the phone → the Mac app logged `connection ready`.
- **Duplex echo verified**: 26 bytes sent, echoed verbatim, received back.
- `FLAG_USER_APPROVED_ONLY` → found the same peer **with no picker**, after a single approval. That
  is the flag that makes silent reconnect possible.

**Correction to a previous conclusion:** the earlier session recorded that Bonjour and inbound
connections were blocked at the host level by Little Snitch. That was wrong. With no change to Little
Snitch, the firewall, or any VPN, the phone connected to a plain Python listener, to the real
`DroidBridge.app` listener, and completed a duplex echo. The Mac was never blocked. The previous
diagnosis was an inference from a failing test rather than a measurement of the host, and it sent
that session chasing a security control that was not in the way.

**Open question, recorded honestly:** after `pm revoke`, `ACCESS_LOCAL_NETWORK` reads `granted=false`
while discovery, the picker, resolve and approved-only all still work. A fresh install that had never
been approved found nothing. So the picker grant appears to be the real gate and the permission's
enforcement could not be demonstrated on this build. DroidBridge must keep the picker as its consent
mechanism regardless, and must not treat the permission as a security boundary until this is settled.

### P04 — re-run on Android 17

The full matrix re-ran and is **unchanged from Android 12**: focused read works, empty/image/long/
duplicate behave identically, and the unfocused read still returns `hasPrimaryClip=false`. The
sensitive row is now the stronger result it could not be on API 31 — the flag is a real API-33+ extra
here, and the clip was still read in full with `sensitive=true`. **The flag is a preview hint, not an
access control.**

The unfocused row was measured with a new **delayed read** (schedule a read, switch away, read fires
with another app in front) instead of the exported receiver, which is both a better test of the real
product path and does not require weakening the receiver's signature guard.

## P03 — secure session, device run 2026-11-09

Run on SM-S928B (Android 17 / API 37, `CP2A.260605.016`) against MacBookAir10,1 / macOS 27.0.1 over
Wi-Fi, USB connected, USB debugging on, wireless debugging enabled (but not used as the transport).
Full record: `Docs/prototypes/P03-live-handshake-evidence.md` §7 Run P1.

**What passed on the device**

- First pairing showed the same code on both screens (`642 575`, then `614 068` after the fix below),
  with data locked until confirmation; confirming persisted the pin (`pin persisted=true`) and only
  then enabled data.
- Data crossed both ways on the confirmed session (`session.ping` → `FRAME type=ping payload-bytes=12`;
  Mac ping → `received ping:1:hello-from-mac`).
- Stored-pin reconnect authenticated inside the handshake, with no code prompt.
- The 30 s idle deadline closed sessions during the run, observed rather than inferred.
- A restarted Mac with a new identity was refused by the phone: `peer pin mismatch: expected 95c71d5e…,
  got ce3724ac…`.

**Two defects found by doing this on hardware**

1. The Android identity key permitted only SHA-256 digests, so Keystore refused Conscrypt's
   `NONEwithECDSA` CertificateVerify (`InvalidKeyException: Keystore operation failed`) and no client
   certificate was ever sent. Fixed by also permitting `DIGEST_NONE`; the app had to be reinstalled to
   generate a compliant identity. No build or unit test could have caught this.
2. The macOS verify block captured the expected pin when the listener started, so a listener that
   began unpinned kept accepting "first pairings" after a confirmation and then granted data access
   because a pin existed. Fixed by reading the pin at handshake time and by refusing a mismatched pin
   in `connectionReady`. Regression is recorded with two loopback identities.

**Still open for P03**

- Wi-Fi drop/reconnect is **manual**: the drop closes the session on both ends and reconnecting with
  the stored pin needs no re-pairing, but nothing re-establishes the session automatically.
- The listening side learns of a drop only by its idle deadline, and a 30 s deadline drops a session
  a user pauses in. A production session needs a keepalive or a longer deadline.
- Reconnect after a DHCP address change was not tested (the phone returned on the same IP).
- At the time of this 2026-11-09 run, the Mac identity was ephemeral (ADR-025), so a Mac restart
  forced re-pairing. P15 later implemented and locally verified login-Keychain persistence; Android
  reconnect after a Mac process restart remains untested.
- On-device tamper/replay/oversize input has no UI; those paths remain loopback/unit-test evidence.
- The acceptance run used `P03Harness --pair` for the Mac side; the GUI app's pairing path is not
  exercised yet.

## P03 — final acceptance run, USB disconnected 2026-11-09

The acceptance run `PHASES_AND_ACCEPTANCE.md` requires, and the first one with no development
transport available.

- USB disconnected, USB debugging off, wireless debugging off, Developer Options off. Verified from
  the Mac: `adb devices` listed nothing and no adb-over-TCP device existed for the whole run.
- Phone UI driven by the owner on the device; Mac side by `P03Harness --pair`. First run where the
  phone was operated by a person rather than by `adb shell input tap`.
- Both screens showed **`996 498`**, compared by the owner. Mac log:
  `verify: pin matched e0369bd5…`, `authenticated=true code=996 498`, then
  `FRAME type=ping seq=1 payload-bytes=12`.
- A TLS 1.3 mutually authenticated session carried application data with no cable and no debugging.

Two things this run exposed, both recorded rather than papered over:

1. The first attempt authenticated and was then closed by the 30 s idle deadline before the ping was
   sent. A user who pauses half a minute loses the session; a production session needs a keepalive or
   a longer deadline.
2. Restarting the harness to get a longer deadline minted a new Mac identity (ADR-025), forcing a
   second re-pair. Persistent Mac identity is a P15 prerequisite, not a P03 defect.

Full record: `Docs/prototypes/P03-live-handshake-evidence.md` §7 Run P3.


## P01/P02 completion run — 2026-11-09 (S24 Ultra)

A dedicated validation pass closed the gap between the earlier first-contact evidence and the
P01/P02 acceptance lists. All sessions below are **SM-S928B, Android 17 / API 37, One UI 9.0,
build `CP2A.260605.016.S928BXXS6EZJ1`**, with USB connected, USB debugging on, wireless debugging
off, Developer Options on. Full reports: `P01-media-projection.md`, `P02-local-networking.md`.

### P01 — validated

- Encoder `c2.qti.avc.encoder`; hardware accelerated; 3120×1440, 2560×1440, 1920×1080 and
  1280×720 all accepted at 30 fps; 2-pixel alignment.
- Output 1280×720, Baseline, level 3.1, VBR; SPS 21 bytes / PPS 8 bytes; 4 Mbit/s requested is a
  ceiling (measured mean 1,392 kbps over a mixed-content 189 s session).
- 189 s session: **12,610 frames / 32,960,629 bytes / 211 keyframes**, 0 crashes, FGS
  `types=0x00000020` and notification id 4101. Capture survives backgrounding.
- Rotation: `onCapturedContentResize` reported 1080×2340 ↔ 2340×1080; `VirtualDisplay.resize()`
  accepted both; frame production continued. Android 17 **forbids a second
  `createVirtualDisplay`** on one `MediaProjection`, so the pipeline rebuild was replaced by
  `resize()`.
- Screen lock fired `MediaProjection.Callback.onStop()` and tore the service down; user stop also
  clean.
- **20/20 start/stop cycles**, 2,005 frames, same PID, 0 crash-buffer entries, service gone after
  the loop. Harness: `scripts/p01-capture-cycles.py`.
- **FLAG_SECURE:** normal white test screen mean luma 159; same screen protected mean luma 0; back
  to normal 159. No bypass attempted.
- **Consent-token replay:** on this build `getMediaProjection` with a used token returns another
  projection bound to the live session instead of throwing; stopping it ends the session. The
  one-shot rule is enforced at `createVirtualDisplay` (second call throws). Product code must never
  replay a token, and must not depend on retrieval throwing.
- Selected-app capture produced 64 frames and fired `onCapturedContentVisibilityChanged(false)` when
  the experiment returned to the foreground.
- Open plan-only items: direct screen-sharing-chip tap, `KEY_LATENCY`, still-screen repeat-frame
  on/off, `flush()` without `start()`. None blocks the P01 acceptance list.

### P02 — validated and accepted, including Mac sleep/wake

- **Picker without permission:** after a reinstall reset `ACCESS_LOCAL_NETWORK` to NOT GRANTED,
  the system picker still appeared and listed `DroidBridge`; selection produced
  `onServiceFound host=null hostAddresses=[] port=0`, and resolve returned
  `192.168.31.109:61169`.
- **60 s sustained duplex:** 198/198 messages, 0 mismatches, 0 errors, 1 connection; RTT
  8.0–327 ms, average 51.6 ms.
- **Real outage:** airplane mode for ~30 s during a 180 s run; 475/474 messages, 0 mismatches,
  1 error, 2 connections; approved-only rediscovery timed out during the outage, retried, found the
  Mac after restore and reconnected with **no picker**.
- **Android process recreation:** new process, approved-only found and connected (10.3 ms first
  RTT). **Android reboot:** new process, found, resolved, connected; 20/20 messages.
- **Inbound:** clean boot without the permission, Mac connect timed out; after grant, bidirectional
  line echo passed. `pm revoke` did not take effect for the running process (3/3 connects
  succeeded); after reboot, inbound was blocked again. `AdvertisingRequest` without the permission
  throws `SecurityException: Missing local network permission`; with it, registration succeeds.
- **`checkPermissionForService()`** returned `SERVICE_PERMISSION_DENIED` both before and after the
  runtime grant, so it is not a reliable grant indicator on this build.
- **Mac sleep/wake — passed (third attempt):** real `pmset` Sleep at 21:41:50 and Wake at 21:42:15;
  the phone disconnected at 21:41:55, rediscovered the approved Mac and reconnected to the same port
  64615 at 21:42:14 with no picker. Final run: 536/535 messages, 0 mismatches, 1 expected lost
  reply, 2 connections; RTT 5.8–430 ms, average 44 ms.
- **Mac restart finding:** both the advertiser restart (new ports 61172/61173) and the cold restart
  (new port 64615) required a fresh picker approval. Approved-only discovery did **not** accept the
  old approval for the new service instance; after one re-approval, approved-only reconnect worked
  again. The product must either use a stable service identity/port that survives a Mac restart or
  present the re-approval as expected UX.
- Router restart, Mac IP change, guest-Wi-Fi isolation and Mac interface change were not available
  in this environment.

### Verification

`JAVA_HOME` set to Zulu 17, `./scripts/verify-all.sh`: 78/78 shared-protocol tests, Android
`:app` + media-projection + discovery + clipboard builds/tests/lint, 37/37 macOS tests, exit 0.
P01 adds 13 unit tests (8 framing + 5 format policy); P02 adds 7 echo-payload tests.


## P05 — Clipboard Mac → Android (completed Android 12 + 17, 2026-11-09)

The OnePlus matrix ran against a real Mac→Android framed TCP harness
(`scripts/p05-clipboard-sender.py`); this is not TLS evidence — P03 owns TLS, and P05 measures the
Android clipboard write path. A separate installable app,
`android/experiments/clipboard-receive/`, receives length-prefixed UTF-8 text in a `dataSync`
foreground service and writes it without logging content.

| Case | Result |
|---|---|
| Foreground 21 B | written, readback hash matched |
| Background 21 B | written while activity gone, readback matched |
| Process recreation | new process wrote and read back |
| Write while another app foreground | P04 read the newly written text (21 B) |
| Unicode | 55 UTF-8 bytes → 47 UTF-16 units, hash matched |
| 100 KiB | 102,400 bytes written and read back |
| Duplicate ×2 | 1 write, 1 ignored duplicate |
| Empty / 102,401 B | rejected on the length header; clipboard unchanged |
| Auto-clear at 5 min 50 s | still present; 15/30/60 min and S24 comparison pending |

Findings: background clipboard **writes** are allowed while reads remain focus-gated (P04);
DroidBridge must enforce the 100 KiB cap and hash over UTF-8 bytes, not `String.length`; duplicate
suppression works at the receiver but end-to-end loop prevention still needs P06/origin IDs; no
content is logged or persisted. P05 is now **completed** on Android 12 and Android 17. The S24 run also showed that a raw local TCP
socket outside the picker path is blocked until `ACCESS_LOCAL_NETWORK` is granted, that Android 17
shows a generic "Copied." confirmation with no content preview, and that the clip was still present
after ~6 minutes. The active-mirroring-FGS integration context is deferred to P08.


## P06 — macOS pasteboard monitoring (completed with a platform limitation, 2026-11-09)

The P06 console ran on macOS 27.0.1 (26A434) with the bundled, ad-hoc signed, sandboxed app. It polls
`NSPasteboard.general.changeCount` at 500 ms and reads only on a change.

- Every run started with `accessBehavior=alwaysAllow`; **no access prompt appeared** for polling or
  a real `string(forType:)` read, foreground or background.
- `tccutil reset Pasteboard com.droidbridge.macos` and `tccutil reset All` both reported success,
  but the behavior stayed `alwaysAllow`.
- External `pbcopy` was detected and read; the app's own write was suppressed
  (`selfWrite=true suppressed=true`) so it cannot echo back.
- Logs contain length and SHA-256 prefix only — no clipboard content.
- A configuration defect was found and fixed: `macos/project.yml` had duplicate `settings:` keys, so
  the target's bundle ID/product/Info.plist settings were discarded and the app built as
  `com.droidbridge.DroidBridge`. The blocks are merged; the app now builds as
  `com.droidbridge.macos`.
- P06 is **completed for macOS 27.0.1**: the only observable state is allowed, and there is no
  pasteboard TCC service/prompt on this OS. Re-signing with the Apple Development identity did not
  change it. The core question — can DroidBridge monitor the Mac pasteboard automatically? — is
  answered **yes here**. The deny/ask UX is a documented platform limitation, to be re-tested on a
  macOS version that implements it.


## P07 — Mac H.264 receiver (completed, 2026-11-09)

A Mac-only P07 path now parses Annex-B, builds `CMVideoFormatDescription` from bare SPS/PPS, turns
access units into 4-byte-length AVCC `CMSampleBuffer`s and enqueues them on
`AVSampleBufferDisplayLayer.sampleBufferRenderer`.

- Fixture: 640×360, 48,029 bytes, 97 NALs, 90 access units; generated by ffmpeg.
- 7 new XCTest cases pass: fixture parse, AVCC lengths, emulation-prevention preservation,
  parameter-set start-code rejection, wrong `nalUnitHeaderLength` rejection, format/sample build,
  duplicate/non-monotonic PTS rejection. macOS test total is now 44/44.
- Live viewer: `LOADED sps=25 pps=5 units=90 preventsCapture=true`, `READY_FOR_DISPLAY true`,
  90 frames at `status=rendering`.
- Flush/recover: `RECOVER flush`, ready false → true, playback restarts at the keyframe, frames
  continue without error.
- Completed: screen-recording verification of `preventsCapture` (video area black), resolution
  changes, startup latency.
- Plan-only remaining: forced backpressure false state, glass-to-glass latency, corrupt-sample
  failure, real Android input.


## P16 — isolated BLE HID Boot Keyboard POC (Mac publication retest pending; 2026-11-20)

- Added a separately bundled, sandboxed macOS CoreBluetooth experiment target and Android receiver-only experiment. Mac sends only explicit arrow-button reports; Android observes normal key events. No production code, Accessibility, or global input capture.
- **Profile correction:** P16 now registers canonical SIG UUIDs and publishes the required Battery and Device Information services, including PnP ID, plus the mandatory read-only Report Map; Battery remains separate because it is not described in the Report Map. Apple's public macOS 27.2 `CBDescriptor.h` says local descriptors support only Characteristic User Description and Presentation Format; CoreBluetooth auto-creates CCCDs. The HID Service still has its mandatory read-only Report Map; HOGP Report Protocol additionally requires unsupported Report Reference `0x2908`, so the POC has no Report characteristics and is limited to Boot Keyboard (fixed eight-byte reports, no Report ID). It will send only after Android writes Boot Protocol and subscribes. HOGP Boot and Report Host modes are mutually exclusive.
- **Host verification:** current Boot-only POC build and strict signature verification pass; `git diff --check` passes. The Mac process is running. Native desktop observation was denied to this session (`MCP credential does not grant the computer capability`), so no post-correction Start Bluetooth click or service-publication result is recorded. The updated app must be manually exercised before advertising/phone testing.
- **Phone/device evidence:** OnePlus Nord AC2001 / Android 12/API 31 is preliminary only. On 2026-11-20, setup ADB was connected over Wi-Fi (`192.168.31.64:5555`); USB disconnected, USB debugging enabled (`adb_enabled=1`), wireless debugging disabled (`adb_wifi_enabled=0`). The P16 receiver was foregrounded, Bluetooth Settings opened and Bluetooth enabled via its visible system switch; the already-bonded **P16 Test Keyboard** row was tapped while the owner reported the Mac POC was advertising. Android remained `STATE_DISCONNECTED`, reported no LE connection attempts and had no active HID input device. Receiver counters remained 0; no subscription or key report was observed. No first-pairing flow occurred and no key was sent. Android host/profile compatibility remains unknown because Mac-side Boot selection/subscription status could not be inspected. S24 Ultra result unknown.
- Reference reviewed but no code copied: [`darwin-bt-remote`](https://github.com/jqssun/darwin-bt-remote), commit `e64cae983b8816e7e2f067e79025ffc49faf7e2d`; [AGPL-3.0-only](https://github.com/jqssun/darwin-bt-remote/blob/e64cae983b8816e7e2f067e79025ffc49faf7e2d/LICENSE). Attribution, official HOGP and Apple's descriptor limitation are recorded in the [P16 result](prototypes/P16-ble-hid.md), R-34 and ADR-039. Personal use is not treated as copying/distribution; review obligations before sharing/modifying for network access.
- P17 Classic HIDP remains an independent route and must be researched/tested separately.

## P15 — Sandboxed macOS build for personal use (identity persistence verified; reconnect remains; 2026-11-17)

On MacBook Air M1, macOS 27.2.1 (26B131), arm64, Xcode 27.2 (27B52):

- Release build passed; the Release app launched from `macos/build/Build/Products/Release/DroidBridge.app` and the canonical `~/Applications/DroidBridge.app` on this Mac. The macOS bundle ID is `com.droidbridge.app`; Android's `applicationId` was not changed.
- `codesign --verify --deep --strict --verbose=2` passed: valid on disk and satisfies the Designated Requirement.
- Signature is ad-hoc (“Sign to Run Locally”), hardened-runtime flag present, `TeamIdentifier=not set`.
- Embedded entitlements are exactly `app-sandbox`, `network.client`, and `network.server`; `get-task-allow` is absent.
- `spctl -a -vv` rejected the artifact (exit 3), expected for this unnotarized ad-hoc build. No Gatekeeper setting was changed.
- The app now stores the identity in the user's login Keychain under a versioned key application tag. Two separate Release `--identity` harness processes exited 0 and reported the same pin (`2178541711aede012c381f584e5ef2e1e39baf5b1b8772c3305005c6f7ff9ffb`). The earlier generated item from the failed experiment remains untouched and is not adopted by this new tag.
- Isolated disposable-Keychain persistence and orphan-key fail-closed tests pass; full macOS suite passes **67/67**. The canonical `~/Applications/DroidBridge.app` resolves Bonjour alone under the new bundle ID. Android reconnect without re-pairing and legacy Keychain ACL behavior across code-signing changes remain untested.
- The owner confirmed personal-use-only scope and deferred Developer ID signing, notarisation, stapling, and quarantined-copy distribution testing. This is not release-ready and no distribution claim is made. See [P15 evidence](prototypes/P15-signed-build.md), [ADR-033](TECHNICAL_DECISIONS.md#adr-033--defer-macos-developer-id-distribution-while-droidbridge-is-personal-use-only), and [ADR-034](TECHNICAL_DECISIONS.md#adr-034--persist-personal-use-mac-identity-in-the-login-keychain-until-a-profile-is-available).


## P13 — Pinned TLS on Android 17 (completed, 2026-11-10)

Fresh P03 install on the S24 Ultra and a fresh Mac identity proved the P13 acceptance criteria:

- Per-install Android identity changed after uninstall/reinstall (`e0369bd5…` → `0c8d5ba9…`).
- TLS 1.3, mutual certificate authentication, matching code `550 086` on both sides; pin persisted;
  phone → Mac `session.ping` arrived as a framed control message.
- A restarted Mac with a new identity was refused by the phone:
  `peer pin mismatch: expected d2425c59…, got 6b20ec4d…`.
- A TLS client with no certificate was refused by the Mac listener:
  `tlsv13 alert certificate required` / `NWError certificate required`.
- `targetSdk 37`, no `networkSecurityConfig`, no `usesCleartextTraffic`, no hostname-verifier
  workaround, TLS 1.3 only. ECH does not apply to a literal-IP/no-SNI connection.
- Definitive U3/U13: pinned TLS works on API 37 with default CT for this local self-signed peer.

P13 is completed. The same-key certificate re-issue test is plan-only and remains unrun.


## P09 — initial remote-input checks (historical, 2026-11-10; scoped NO-GO)

On the S24 Ultra (`ro.build.type=user`, no root, Accessibility disabled), the P03 experiment
requests only `INTERNET` and `ACCESS_LOCAL_NETWORK`. Public API checks confirm:

- no `INJECT_EVENTS` signature permission or `InputManager` injection path;
- `UiAutomation` is shell/privileged only;
- Knox `RemoteInjection` is signature + managed-device gated (ADR-017);
- Accessibility is prohibited and disabled;
- Android `BluetoothHidDevice` is the wrong direction for Mac-to-phone control; P09 did not test
  macOS as a HID peripheral. P16/P17 now test BLE HOGP and Classic HIDP independently (R-32).

P09's NO-GO applies only to the tested mechanisms and is not the final Bluetooth HID decision. Source review recorded in R-32/ADR-036 identifies separate BLE HOGP and Classic HIDP routes. P16/P17 will test those routes without Accessibility or ADB as a functional path. Until target evidence exists, no control capability is claimed and production mirroring remains view-only.

## P08 — end-to-end mirroring acceptance evidence (2026-11-12, in progress)

S24 Ultra (SM-S928B, Android 17 / API 37, build `CP2A.260605.016.S928BXXS6EZJ1`) against a Debug
Mac app on macOS 27.2.1, same Wi-Fi LAN. Full evidence: [P08](prototypes/P08-live-mirroring.md).

Measured this session:

- Rotation mid-session: `re-encode 590x1280 -> 1280x590` plus a fresh `mirror.format`, Mac swapped
  its description with `formats=2`, render queue stayed at 0 frames; rotating back produced
  `formats=3 reEncodes=2`. No stall or stuck frame.
- Keyframe recovery over the real transport: `--p08-fault-after` corrupt sample did not flip the
  renderer to `.failed`, so the receiver drove the same branch explicitly
  (`FAULT_FALLBACK scripted-flush` → flush → `KEYFRAME_REQUEST`), the phone logged
  `requesting an IDR` with `keyframeRequests=1` and the stream resumed (`dropped=0`).
- Stop/teardown: `MIRROR state=stopped: user stop`, `teardown complete`, `dumpsys media_projection`
  `null`, no service entry. Same for the screen-off case (`system onStop`).
- Stability: 30 m 43 s continuous, 9118 frames, 22 samples, `dropped=0` and
  `received == enqueued` at every sample, render queue 0 frames on all probes. Idle-screen cost with
  `KEY_REPEAT_PREVIOUS_FRAME_AFTER`: 4.6–5.1 fps, 244–321 kbit/s.
- Reconnect: repeated Listen reports the same Mac pin, and Connect after Close session returned
  `authenticated=true` from the stored pin without Forget or re-pairing.

Fixed: `29892b4` — `startListening()` regenerated the Mac identity on every Listen, so the phone's
stored pin went stale on every restart and reconnect recovery was impossible. The identity is now
created once per app run; persistent identity remains P15 scope.

Second pass the same day (11:04–11:15):

- Locked-screen stop: `KEYCODE_SLEEP` with `isKeyguardShowing=true` revoked the projection at once
  (`system onStop`, `durationMs=139182`), the Mac received `MIRROR_STOP` with that reason, and
  teardown was confirmed by an empty `dumpsys media_projection`, no service entry and released
  encoder; the app process is reaped right after, so the `teardown complete` line never reaches the
  buffer on this path.
- Idle cost, measured by differencing stats on the same static home screen: with
  `KEY_REPEAT_PREVIOUS_FRAME_AFTER` 3.7 fps / 342 kbit/s; with the key omitted (experiment switch
  `--ez omitRepeatFrame true`) 0.39 fps / 103 kbit/s, and keyframes stop advancing because nothing is
  encoded while the screen is still.

Not done, so P08 stays open: the final run with USB disconnected and both debuggings disabled
(these runs were wireless-only but needed wireless debugging on), and glass-to-glass latency plus
CPU/GPU/battery/thermal numbers.

## P10 — password case captured, shade defect found (2026-11-12; source update 2026-11-17)

The Mac app sets `preventsCapture` on its display layer, which is why every earlier `screencapture` of
the mirror came back black. A `--p08-allow-capture` switch now turns that off for evidence runs, and
with it the mirrored pixels are recordable (evidence:
`Docs/prototypes/evidence/p10/password-mirrored.png`).

- **Password field — pass.** `PELICAN-7731` typed into the `password="true"` field, stream live, and
  the mirrored frame shows eight mask dots. Captured from the Mac window, not inferred.
- **Notification shade — historical defect, not a pass.** Opening the shade froze the mirrored picture on the
  pre-shade frame (verified by re-capturing after collapsing the shade), and the receiver then closed
  the session twice on fresh sessions. The historical log named a 120 fps guard, but subsequent code
  review found media and control rates are separately classified, so that log does not confirm a
  control-frame flood or root cause. The phone's own stats froze at `frames=1240` and ended at
  `frames=1551 keyframes=26`. The earlier "shade streams normally" line in the P10 doc was inferred
  from `mCurrentFocus` plus a rising frame counter and is withdrawn.
- **OTP notifications — mirrored visibility not observed.** Both variants are confirmed at the notification service (`id=102`
  carries a `publicVersion` reading "Synthetic notification (no code shown)", `id=101` has none). The
  2026-11-12 visual attempts coincided with the shade/session failure. The 2026-11-17 follow-up
  visually confirmed public-version redaction; the private-only OTP result remains inconclusive, as
  detailed below.
- No frames cached in app storage still holds (`files/` holds only `profileInstalled`, `cache/` empty).

**2026-11-17 receiver hardening and live device evidence:** macOS has bounded-burst monotonic rate limiters, batched media logging, bounded-rate UI counter publication, strict AVCC/IDR/PTS validation per format epoch, and IDR-gated recovery; Android drops non-increasing encoder PTS per epoch and restores the process-held session reference after Activity recreation. Automated tests: Mac 65/65; Android mirroring 22/22 and Debug APK assembled. In a separate live S24 Ultra run, USB was connected, USB debugging and wireless debugging were enabled (`adb_enabled=1`, `adb_wifi_enabled=1`), and ADB used USB. The shade appeared on the Mac mirror and the stream stayed active through dismissal; this single run does not confirm a fix for the historical freeze. The public-version synthetic notification's redacted text appeared on phone and Mac. The private-only test was posted, but its contents were not visible in the captures, so its mirrored privacy outcome is inconclusive. Rotation UI restoration remains untested on-device. P08's final debugging-disabled session and latency/device-cost measurements also remain open. See [P08](prototypes/P08-live-mirroring.md) and [P10](prototypes/P10-secure-screen-protection.md).

**2026-11-22 P12 audio/calls measured (SM-S928B, Android 17 / API 37, USB connected, USB debugging enabled, wireless debugging disabled).** The last unstarted phase is now measured instead of approval-gated. The owner approved `RECORD_AUDIO` for an isolated experiment and directed that setup and prompts be driven through ADB.

Two experiment apps, neither depended on by `:app`: `:experiments:audio-capture` holds a `mediaProjection` foreground service and builds `AudioRecord` + `AudioPlaybackCaptureConfiguration` for `USAGE_MEDIA`/`GAME`/`UNKNOWN`; `:experiments:tone-player` emits a 440 Hz sine at quarter scale and selects its capture policy and usage class per launch, so the positive and negative runs differ in nothing but the policy. Measurement is the returned PCM (per-second frame count, RMS, peak), because every refusal in this area is silent — a blocked capture is a full-rate stream of digital silence, indistinguishable from "never asked" by frame count alone.

Results: allowed playback was captured **bit-exactly** (`rms=5791.5 peak=8190` against a source amplitude of 8192) for media, game and unknown usages, from the capturing app itself and from a separate app, and from a third-party YouTube client playing real content (`rms` 876–1217). Refused, with frames still advancing at 44.1 kHz: `USAGE_VOICE_COMMUNICATION` under `ALLOW_CAPTURE_BY_ALL` (the call-audio class), `USAGE_NOTIFICATION`, and any usage published with `ALLOW_CAPTURE_BY_NONE`. 17,463,600 frames captured over ~396 s with no stall. So R-15's call-audio NO-GO is now measured on the target device, and two details the documentation does not state were confirmed: `USAGE_NOTIFICATION` is excluded too, and a capturing app does capture its own audio.

The Android 17 consent picker now has a scope spinner; **Share entire screen** was selected deliberately, since single-app sharing would have produced a false negative for the cross-app rows. The consent dialog and the scope choice were driven through ADB at the owner's direction after reading the on-screen text; ADB generated no audio — the input under test came from `AudioTrack` inside the experiment apps. One reboot was needed to clear a wedged notification shade that was freezing launched activities. Decisions recorded in ADR-045: media audio GO (conditional), call audio closed NO-GO, call notifications/control deferred (they need `READ_PHONE_STATE` or a notification listener). See [P12](prototypes/P12-audio-calls.md).
