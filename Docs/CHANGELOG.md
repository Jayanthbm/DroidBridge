## All four receiver counters measured over the bond, including the arrow pad (2026-11-27)

- `--harness-send DOWN,LEFT,RIGHT` over the existing fresh bond moved the receiver's `DOWN`, `LEFT` and
  `RIGHT` counters to 1 each (`P16HidReceiver` logged down/up for each), with `UP` left at 0.
- The receiver gained a four-button arrow pad that unfocuses the text field and counts locally; the
  owner's taps bumped `UP` (two logged pad presses → `UP: 2`), so the pad and HID paths share the same
  counters.
- Recorded with USB connected, USB debugging on and wireless debugging off; ADB did install/launch and
  observation only, never the keys or the tap.

## Receiver arrow counters work alongside the text field (2026-11-27)

- `HidReceiverActivity` counts arrow keys in the root's Compose `onPreviewKeyEvent`, so the focused
  text-entry field no longer hides them from the counters; the key is still passed on and the field's
  typing and cursor behaviour are unchanged.
- Re-measured over the existing fresh bond: one Mac `--harness-send UP` moved the receiver `UP: 0` →
  `UP: 1`, with `P16HidReceiver: UP down` / `UP up` in logcat. USB connected, USB debugging on, wireless
  debugging off; ADB did setup and observation only.

## Keyboard input verified over the fresh bond; the arrow counter was the problem (2026-11-27)

- **Corrects the entry below.** The keyboard does work over the re-paired bond. Three log lines (channels
  ready + protocol, every host control message, every report written) show well-formed reports
  (`id=2 protocol=report bytes=10 status=0`), the phone's kernel logs `EV_KEY KEY_UP DOWN/UP`, and text
  entry delivered **14/14** (`DroidBridge123`).
- The receiver's arrow counters stayed at zero because its newly focused `EditText` consumes arrow keys
  for cursor movement before `onKeyDown`; the counter is not a valid arrow instrument any more.
- No behaviour change: instrumentation only. macOS tests 104/104. Evidence:
  `Docs/evidence/p17/fresh-bond-input-2026-11-27.md`.

## Fresh-bond pairing restored by hand; keyboard input still missing (2026-11-27)

- **No code change.** After the fresh-bond test, the owner paired the phone by hand from the HID panel:
  the bond exists again and the HID channels open on connect.
- **Open defect:** a sent arrow did not reach the receiver over the new bond — its counters stayed at
  zero while the phone reports `HID connection state=2`. The earlier arrow/text-entry evidence used the
  previous bond and does not transfer.
- Programmatic pairing still cannot complete on macOS 27 (`replyUserConfirmation no-op` → `0x1F`, from the
  app and from `blueutil`); a human comparing the codes is what makes pairing work. Transcript:
  `Docs/evidence/p17/fresh-bond-2026-11-27.md`.

## Fresh Classic bond blocked by macOS 27's pairing reply (2026-11-27)

- **No code change.** Tested the Phase 5 pairing-prompt row on a genuinely fresh bond. Codes matched on
  five separate attempts, but macOS 27 reports
  `BluetoothHCIUserConfirmationRequestReply no-op. Should be done at CB Layer` and the pairing fails with
  `0x0000001F`, from both the app and `blueutil`. The Mac also cannot be made discoverable
  programmatically, so the phone-initiated path is unavailable.
- **Impact:** the HID keyboard has no bond until one is restored; recovery needs an Android-initiated
  bond or a macOS that honours the reply. Details in the P17 report.

## Personal-use scope settled for the remaining rows (2026-11-27)

- **No behaviour change.** The owner confirmed Mac→phone keyboard/text entry is in use, so those rows
  stay real gates. Second-device rows (P05 API 33+ sensitive clipboard, P12 OnePlus audio, P08 OnePlus
  stream) and other-machine/fault-injection extras (P01 plan-only extras, P07 corrupt-sample, P02
  router restart, P11 denied state) are **waived**; existing OnePlus evidence stays as historical.
- The **+276 ms audio offset is accepted as a documented limitation** — no video re-timing.
- A fresh-bond **pairing-prompt** row is being measured once; if this Mac's pairing-agent conflict
  blocks it again, it is recorded as waived rather than chased.
- P08 and P10 no longer read "acceptance pending": both debuggings-off sweeps were done on 2026-11-26.

## Phase 6 lightweight permission/security audit measured (2026-11-27)

- **No behaviour change.** Audited the freshly built artifacts: the APK declares exactly the seven
  expected permissions (no Bluetooth, SMS, contacts, storage or Accessibility), the macOS bundle
  carries exactly the audited four entitlements, no analytics or crash SDK is present on either
  platform, and clipboard content or the stored pin never reaches a log line. No permission diff
  since the Phase 6 audit. Recorded in `Docs/PERMISSIONS_MATRIX.md` §6.7.

## P17 report and evidence corrected after review (2026-11-27)

- **No behaviour change.** The P17 report had contradicted the status dashboard: it still called text
  entry unproven after the 2026-11-27 run. It now records that run, the pacing finding and what the run
  does not prove.
- The text-entry row is recorded as **measured with a summary only** — no raw Mac log was archived for
  it, so it is not yet captured evidence. A repeat run will save the log.
- `Docs/evidence/p17/pairing-prompt-verification.log` is renamed to
  `pairing-mismatch-refused.log`: it records a **refusal** (Mac `108952` vs phone `456266`), and the
  pairing-prompt row remains unmeasured.
- `DroidBridgeApp` now attaches the HID peripheral to the app delegate once, so a re-initialised SwiftUI
  app cannot leave the delegate holding an instance the UI never shows.

## P17 production text entry device-verified on S24 Ultra (2026-11-27)

- `DroidBridge123` typed over Classic HID into the focused receiver `EditText`: **14/14
  characters** (uppercase via shift, digits) on SM-S928B / Android 17 using the pre-existing
  Classic bond. Evidence: `Docs/evidence/p17/text-entry-s24.md`.
- Real-device finding: Android's HID stack drops back-to-back interrupt reports. `typeText` is now
  async and paced at 120 ms/character; the unpaced version delivered only 4/14. A Bluetooth cycle
  clears the stale HID link between harness runs.
- No pairing code was confirmed; no new bond was created. ADB was setup/diagnostics only.

## P17 production keyboard + text entry implemented (2026-11-27)

- The promoted HID peripheral now sends arbitrary key usages, modifier chords and US-layout ASCII
  text (`HIDKeyboardMap`, `sendKey`, `typeText`) from a new keyboard/text panel in the HID view.
  Non-ASCII is skipped and reported; no clipboard, Accessibility or protected-surface access.
  The Android P16 receiver gained a focused text field so typed characters can be observed.
- Harness wiring fixed so `--p17-hid --harness-*` runs headlessly: the app delegate owns the same
  peripheral instance and starts the harness at launch, independent of the Diagnostics > HID tab.
- 104 macOS unit tests pass, 0 failures (9 new mapper tests); `verify-all.sh` exits 0.
- **Not device-verified:** a fresh Classic bond could not be established. The Mac's programmatic
  numeric-comparison callback does not fire (the documented single Classic pairing-agent conflict),
  and the code-comparison harness correctly refused to confirm a mismatched pair (Mac `108952`,
  phone `456266` — the phone dialog was stale). No unverified code was accepted, per AGENTS.md.
  HID text entry stays unit-verified only until a bond is available.

## ADR-062 retry-budget fix device-verified (2026-11-26)

- **No behaviour change** from this verification; it proves the shipped `RetryCounter` fix on a real
  device. 12 sequential stored-pin reconnects on the OnePlus, 22 total reconnects, every attempt
  stayed at 1, zero give-ups. The old lifetime counter would have stopped at the 11th drop. This is
  the fix that keeps long autonomous sharing sessions alive.

## P10 private-only notification row waived for personal-use scope (2026-11-26)

- **No behaviour change.** The owner confirmed the private-only notification visual row is not needed
  for a personal app. The inconclusive result stays recorded as inconclusive, not a pass. The measured
  `FLAG_SECURE` withholding and public-version redaction are unchanged.

## P08 device-cost measurement waived for personal-use scope (2026-11-26)

- **No behaviour change.** The owner confirmed the one-hour battery/thermal measurement is not
  needed for a personal app. Both P08 instrumentation gates (glass-to-glass latency and device
  cost) are now explicitly waived. The failed idle-screen sample stays discarded; no redo planned.

## Personal-use scope review of remaining pending items (2026-11-26)

- **No behaviour change.** Waived P05 long auto-clear, P07 backpressure false state and P13
  same-key re-issue; reduced Phase 6 to a lightweight audit; accepted the Mac-restart and
  permission-revocation limitations. P17/Phase 5 keyboard rows are conditional on whether
  remote keyboard control is used. The first device-cost sample was invalidated (idle screen,
  Mac idle-timeout) and will be redone with a changing stimulus.

## P08 glass-to-glass latency gate waived for personal-use scope (2026-11-26)

- **No behaviour change.** The owner confirmed a one-developer, one-tester personal app, so the
  high-speed-camera latency measurement is recorded as waived rather than left pending. Startup
  latency and idle cost remain measured; device cost under a mirroring workload is still planned.

## A long session no longer gives up just because it reconnected a lot (2026-11-26)

- **The retry budget now counts consecutive failures, not lifetime drops.** A successful reconnect on a
  stored pin clears the streak, so a peer that keeps coming back can never exhaust the budget. Before this,
  the counter reset only on a fresh pairing, and the phone gave up after 10 successful drops — the overnight
  run had 21. A real outage still gives up after 10 consecutive failures.

## A phone that slept through its retries now reconnects by itself (2026-11-26)

- **Retries are no longer wasted while the phone is asleep.** With no share running, the display going off
  killed the connection, and the phone then spent all ten retry attempts without reaching the Mac — which was
  listening the whole time — so it settled on a failure that needed a manual tap. Attempts are now held until
  the phone can actually reach the network, so the budget stays intact and the first attempt after the screen
  comes back is a successful one. Measured on-device 2026-11-26: cable out, screen off 8 m 56 s with zero
  retries charged, then a self-reconnect on wake at attempt 7 of 10 with no manual tap and no code prompt.
- **The give-up message no longer blames the Mac.** It said "tap Try again when the Mac is back"; what usually
  ends the connection is the phone's screen staying off, and the text now says so.

## A first pairing completes whichever side confirms first (2026-11-26)

- **Confirming on the phone before the Mac no longer breaks the pairing.** The phone starts sending as soon as
  it is confirmed, so its device announcement reached the Mac while the Mac was still waiting for its own
  confirm; the Mac closed the connection as a protocol violation and the phone reconnected into the same wall.
  The Mac now holds frames that arrive before its own confirm and replays them in arrival order once the human
  confirms, so either order works - measured `connection ready authenticated=false code=836 347` →
  `replaying 1 frame(s) held before confirmation` → `AUTHENTICATED` in 9 s, with heartbeats continuing and no
  reconnect.
- **A phone whose Mac vanished no longer pretends to be connected.** During the confirmation wait the phone
  now checks that the socket is still there, so a peer that died ends the attempt with a handshake failure
  instead of leaving a session that cannot carry anything.

## The device picker no longer opens over a live session (2026-11-26)

- Coming back to the app while its session was still connected used to re-run discovery, so Android's
  `Choose a device to connect` dialog covered a connection that had never dropped. Discovery now starts only
  when the app actually needs it - never while a session is connecting, confirming, connected or
  reconnecting.
- Agreeing with it, a 21-minute share with audio (9210 frames, 128.6 MB, 54 492 audio frames) ran with one
  dropped frame, `maxSendMs=2`, and an audio unit count that matches the run length exactly.
## A Mac app restart reconnects by itself (2026-11-26)

- **Killing the Mac app no longer costs a re-pair.** The phone noticed the peer was gone, scheduled its
  reconnect, retried while nothing was listening, and connected on the stored pins once the Mac was back -
  no code comparison, no taps. Measured: Mac killed 14:36:06, phone `session closed: peer lost` ->
  `reconnect scheduled attempt=1` -> two `ConnectException` attempts -> `CONNECTED` at 14:36:18, about 12 s.
- The Mac's identity now survives a relaunch (login keychain), so this is a real silent reconnect rather
  than a fresh pairing that happened to succeed; ADR-025's interim note is corrected to say so.
- **The phone's environment line is logged once per launch, not once per counter tick.** It was read from
  the composition, so a connected dashboard logged `device samsung SM-S928B ...` every second and buried
  the session lines in a capture; measured after the fix: one line, 12 s of session, still connected.
## Picking the Mac in the system picker connects (2026-11-26)

- **The picker's answer is the connect.** Choosing the Mac row in Android's system picker used to resolve
  the service and then leave the dashboard's `Nearby` card waiting for a second, easily-missed `Connect`
  tap. The app now connects to the peer the picker returns, with no second decision.
- **Only the picker arms it, and only before a session exists.** Direct browsing (used below Android 17)
  lists every Mac on the network, so it never auto-connects; and a drop, a `Disconnect`, or a peer from
  the same list can no longer be overridden, because the arming flag clears on the first peer list.
- The peer list also renders immediately in every case - the first version returned before publishing it,
  so a connected app still said "no Mac found yet".
- **Measured on the S24 Ultra** (wireless, USB disconnected): picker row -> `auto-connecting to the peer the
  system picker returned: DroidBridge-P08-Mac` -> `CONNECTING` -> `CONNECTED` within 240 ms, then
  `device.info`; `Disconnect` -> `IDLE` and still `IDLE` 10 s later.
## The Mac can name the phone (2026-11-26)

- **The sidebar says `samsung SM-S928B`, not "Android device".** The handshake carried no model name, so the
  card said what was actually known. The phone now sends `device.info` (0x40) each time a session reaches
  CONNECTED - including silent reconnects, because a Mac that restarted has forgotten the name. The card
  also shows `Android 17 (API 37) - app 0.1.0` under it and keeps the placeholder until the message arrives
  (screenshot: `Docs/design/shell-main-device.png`).
- **The payload is deliberately boring.** Manufacturer, model, Android release, API level and app version -
  the facts the phone's own About screen shows. No serial number, no build fingerprint, no user-set device
  name, no account: a payload that never contains personal data cannot leak it into a log later. Both
  fields are length-bounded so a peer cannot fill the control channel with one message.
- **Unknown control types are a framing error on both sides** (they throw and the connection closes), so this
  needs both peers on the same build - the same property every added control type has had. The Mac asserts
  the Kotlin encoding byte-for-byte from the same JSON literal the Kotlin test pins.
- **Measured on the S24 Ultra**, 14:24: phone `device info sent=true samsung SM-S928B android=17` (the log
  line predates the app-version field in the message it prints), Mac `DEVICE_INFO samsung SM-S928B Android 17
  (API 37) - app 0.1.0`, and the sidebar rendered that name and detail line in the captured window.
## Stopping from the viewer stops the phone's share (2026-11-26)

- **Either side can stop a share.** The phone has always sent `mirror.stop` when its stop control ran; the
  Mac could not send one, so the viewer's stop glyph only closed the local session and the phone kept
  capturing until its 15 s sender grace expired. The Mac now asks the phone to stop (`STOP_SHARING_REQUESTED`),
  the phone runs the same teardown as its own Stop button (`stop requested by the Mac reason=mac request`
  -> `MIRROR state=stopped: mac stop` -> `teardown complete reason=mac stop` -> `mirror.stop` back); the
  TLS session and the pairing stay up, and sharing again starts on the same session.
- **The request runs in-process, not as a service intent.** A `startService(ACTION_STOP)` from the phone app
  is refused once the app is in the background - exactly when the viewer on the Mac is what the user is
  looking at - so the request goes through a `MirroringService.requestStop()` hook onto the main thread; the
  notification's own Stop action keeps using its PendingIntent.
- **The viewer falls back to waiting instead of showing a frozen picture.** A stopped share leaves the last
  decoded frame in the video layer, and the 'Waiting for the phone to share' text drawn over it read as both
  states at once; the layer is now hidden while nothing is being shared (the layer keeps its frame).
- **Measured on the S24 Ultra** (same session, no re-pairing): share -> click the viewer's stop glyph ->
  `STOP_SHARING_REQUESTED` at 14:09:03 -> phone `stop requested by the Mac reason=mac request` ->
  `MIRROR state=stopped: mac stop ... frames=859 durationMs=86272 stopReason=mac stop` ->
  `teardown complete reason=mac stop` -> Mac `MIRROR_STOP reason=mac stop audioFrames=3704`, no `CLOSED`;
  'Share this screen' then started a second share on the same session (`mirroring started` at 14:12:41).
- **The viewer HUD needs a hover before its controls accept a click** - it fades out, and a hidden control
  should not be clickable, so this is the intended behaviour rather than a defect; noted because it took a
  second attempt to reproduce during the check.
## The app opens as a real two-window shell (2026-11-26)

- **The main window is a sidebar app, not a tab strip.** `Overview`, `Clipboard`, `Screen`, `Audio`,
  `Settings` and `Diagnostics` in a `NavigationSplitView`; the device card at the top of the sidebar
  carries the status dot and one line of state, and `Overview` shows the two primary actions plus a
  status list. Everything live-measured in the same session: the overview read
  `Sharing - 590x1280 - 35 fps`, `Clipboard: phone accepted the offer`, `Audio: playing`,
  `Pairing: stored, silent reconnects` while the phone was sharing (screenshot:
  `Docs/design/shell-main-live.png`).
- **The viewer is its own window.** `Open viewer` (overview and the menu bar) or `--open-viewer` opens a
  380x780 black window that renders the live H.264 stream with a HUD that fades in on mouse movement:
  status dot, `590x1280 - 5 fps`, a full-screen glyph and a stop glyph
  (screenshot: `Docs/design/shell-viewer-live.png`, captured with `--p08-allow-capture` because the
  video layer refuses screen capture by default).
- **A menu-bar extra** shows the phone glyph while idle and a radiating phone while sharing, with a
  popover that opens the viewer and mirrors the clipboard line (captured: `Docs/design/shell-menubar.png`;
  measured later the same day, status item at x=1031 on this display).
- **Only one SwiftUI window scene survives.** Two window scenes made the system launch the viewer scene
  alone: `MainWindowView` was never constructed, the bootstrap never ran, no listener started and the
  phone could not find the Mac. The viewer is now an AppKit `NSWindow` hosted by `ViewerWindow`
  (ADR-059), and the main window opens on every launch.
- **Known and unmeasured:** launch is still window-first (menu-bar-only launch needs an app-level
  bootstrap) and the viewer does not capture input yet (click-to-control is P17 work). The viewer's stop
  glyph now asks the phone to stop sharing instead of closing the session (see the section above).

## The stop control works, and an open notification shade no longer drops the mirror (2026-11-26)

- **`Stop sharing` is enabled during a share again.** The dashboard decided whether it was capturing by
  checking for a `"streaming"` state the pipeline never writes (it publishes `"mirroring WxH"`), so the
  button was disabled - and `Share this screen` stayed enabled, which is how a second projection could be
  started on top of a working one. The check now matches the published state, and the dashboard's
  once-a-second refresh keeps running after a share ends so the buttons and the `stopped: …` line update.
  Measured on the S24 Ultra: tap -> `mirror.stop` on both sides, session survives, card returns to idle.
- **Opening the notification shade no longer ends the mirror.** On a 120 Hz phone the shade's animation
  delivered more than one second of frames inside a second, and the receiver closed the session for
  exceeding its 120 media frames/s bound. Over-budget media frames are now dropped (up to 600 in a row,
  ~5 s) and only a sustained flood still closes; the sustained 120/s budget and the control-frame limit
  are unchanged (ADR-058).
- **The phone stops sharing when the Mac goes away.** The sender used to ignore a failed write and keep
  encoding into a dead connection (measured at 66-80 fps for 13+ s). It now waits up to 15 s for a
  reconnect - long enough for the format re-announce - and then tears the pipeline down; verified on the
  S24: `session lost for 15 s; stopping capture` → `teardown complete reason=session lost`, service and
  notification gone, no encoder activity.
- **Frames delivered to the encoder are capped at 60 fps** (`KEY_MAX_FPS_TO_ENCODER`; the nominal rate
  stays 30). A shade animation on the 120 Hz panel was doubling the rate; with the cap, six shade
  toggles peaked at 55.9 fps (previously 79.7) and the receiver took 2 709 media frames with zero drops. An
  encoder that rejects the cap still starts without it.
- **Known and unmeasured:** the notification's own `Stop` action still has no measured row (the shade
  cannot be inspected with `uiautomator` while it is open, and screen capture is prohibited), and the
  phone keeps encoding for a while after the receiver closes (recorded as an open defect in the P08 doc).

## Pre-release cleanup — the audit trail now matches the build (2026-11-26)

- **Documentation matches what ships.** The app manifest, README, architecture, requirements, permissions
  matrix and research notes now describe the shipped playback audio instead of the pre-approval state;
  the dated research decisions keep their original text with a superseding pointer.
- **Release builds ignore every development switch.** `--p08-allow-capture` (which disables the
  `preventsCapture` privacy guard), `--p08-fault-after`, `P08_RECORD_DIR`/`P08_DUMP_DIR` and
  `P17_AUTO_ACCEPT_PAIRING` are inert outside Debug builds. Verified with the same arguments on both
  configurations: Debug logs `CAPTURE_ALLOWED preventsCapture=false` and `LISTENING port=0`, Release logs
  neither.
- **The optional Bluetooth keyboard no longer kills the app.** Two crash reports (2026-11-25, 2026-11-26)
  were TCC aborts: the app touched `IOBluetooth` while `Info.plist` had no
  `NSBluetoothAlwaysUsageDescription`. The string is now declared, so the first use shows the standard
  macOS Bluetooth alert instead of terminating the process; the sandbox entitlement was already present.
- **Experiment apps are documented and off the phones.** `android/experiments/README.md` explains why each
  prototype app still exists and how to rebuild it; every `com.droidbridge.experiment.*` package was
  uninstalled from both test phones, leaving only the product app with its pairing data intact.

## Media audio ships — the phone's playback now comes out of the Mac (2026-11-26)

- **Playback audio mirrors with the screen.** A "Send playback audio" switch in the Screen card (on by
  default) captures media playback with MediaProjection's `AudioPlaybackCaptureConfiguration` (USAGE_MEDIA,
  USAGE_GAME, USAGE_UNKNOWN only), encodes AAC-LC 44.1 kHz stereo 128 kbit/s, streams it over the existing
  authenticated session (`AUDIO` 0x21 framed by `AUDIO_FORMAT` 0x22) and decodes it on the Mac with
  `AVAudioConverter` into an `AVAudioEngine` player. Calls, notifications and alarms are deliberately not in
  the usage set, so they are never captured (ADR-057 supersedes ADR-018 for media audio only).
- **Measured on the S24 Ultra, 2026-11-26:** phone PCM `audio rms=5791.3 signal` and Mac decoded `AUDIO
  rms=5781 signal frames=1024` from the same 440 Hz tone, ~100 frames/2 s on both sides; stopping the tone gave
  `rms=0 silence` on both ends *while frames kept advancing*; a tone that opts out (`ALLOW_CAPTURE_BY_NONE`)
  measured `rms=0.0 silence` on the phone and `rms=0 silence` on the Mac with the stream still flowing. That
  last pair is the honest limit: an opted-out app is silent, not broken.
- **The permission is real and visible.** `:app` now declares `RECORD_AUDIO` and the `microphone` foreground
  service type; the share flow asks for the microphone after the notification request and before the
  projection consent, and a denial continues with screen-only mirroring. Measured prompt: "Allow DroidBridge
  to record audio?" granted with "While using the app" (through ADB at the owner's instruction).
- **The switch is a real control, measured both ways.** Off: no `RECORD_AUDIO` request, no audio
  messages, video unaffected. Denied: `RECORD_AUDIO granted=false`, the switch turns itself off and the
  screen-only share continues. (The switch's own state was also fixed while measuring it: it was read
  inside a `remember` block, so it looked stuck after a tap even though the choice had changed.)
- **Audio never breaks video.** A capture or decode failure logs and drops frames; the screen mirror keeps
  running. Audio and video are not re-timed: the offset is currently unmeasured and documented as such rather
  than "fixed" with a guessed delay.
- New protocol types and payloads are unit-tested (11 Kotlin, 8 Swift), the Mac suite is 87/87 and the Android
  unit tests pass; the A/V offset, the Mac's UI amplitude readout under real speakers and the OnePlus path are
  still open.
## Phase 6 verified on a phone — forgetting, codes and honest failures (2026-11-25)

- **Forgetting a phone now really forgets it.** "Forget phone pin" on the Mac and "Forget the Mac's pin"
  on Android both clear every stored pin and drop the session, and the next connection is a fresh first
  pairing. Measured on the S24 Ultra: revoke → `pairing revoked for 1 peer(s)` → code `903 717` on both
  sides → `PAIRING_CONFIRMED`; the reconnect after that authenticated by pin with no prompt.
- **A live session shows its pairing code on the phone.** When the Mac asks you to confirm a re-pair, the
  phone displays the same code to compare instead of leaving the prompt one-sided.
- **A dead connection says why.** The phone settles on "the connection kept dropping, so retrying stopped;
  tap Try again when the Mac is back", and the Try again control recovers the session.
- **Adding a second phone no longer dead-ends.** A phone the Mac has not confirmed used to fail the
  handshake outright whenever another phone was already trusted; it now reaches the same
  code-comparison prompt as a first pairing, and data still moves only after you confirm (ADR-055,
  verified on the S24 Ultra against a non-empty key set).
- **Trust is a set of confirmed keys, not one key slot (ADR-054).** The two phones of record no longer
  overwrite each other's pin; the old per-label keychain items migrate on first read.
## Fixed — Mac-to-phone clipboard receive was silently dead (2026-11-24)

- **Found by running the Phase 3 rows again.** Since the Phase 4 hook work, the session dispatcher routed
  clipboard messages into the mirroring callback, which ignores them: offers arrived but the clipboard card
  stayed idle and no ack was ever sent, while the log claimed a handler existed. Clipboard types now go to
  the clipboard feature and mirroring types to the capture feature. Re-measured: offer → accept → held →
  applied on tap → ack (`phone applied the text`), with the echo-suppression rule firing correctly after.
- **Phase 5 HID device rows measured on the promoted build (S24 Ultra):** publish, connect (`HID channels
  ready: yes`), Report-protocol negotiation, and all four arrow keys delivered one per confirmed check.
  Clean quit and relaunch republish without the `0xE00002BC` lockout.
- **Measured follow-up:** killing a connected HID instance with SIGTERM leaves stale L2CAP state that
  survived cycling Bluetooth on the phone; it cleared after cycling the Mac's Bluetooth and relaunching the
  app. The SIGTERM shutdown path likely exits before bluetoothd finishes tearing the channels down —
  recorded as follow-up work rather than papered over.

## Phase 5 started — the Bluetooth HID keyboard moves into the app (2026-11-24)

- The Mac app now has a **HID Keyboard** tab: the proven P17 Classic HIDP peripheral, promoted unchanged in
  behaviour. The Mac appears as a Bluetooth keyboard to the paired phone; one arrow press per confirmed
  foreground check, no pointer, no text entry, explicit publish/stop, and the same clean-shutdown path on
  quit and SIGTERM.
- The app adopts one new entitlement, `com.apple.security.device.bluetooth`, and links IOBluetooth —
  measured working under the App Sandbox (SDP publication succeeded), not assumed.
- The standalone P17 experiment target is removed from the project; its evidence stays in
  `Docs/prototypes/P17-classic-hidp.md`.

## Verified — a returning phone takes back its session without a human (2026-11-24)

- **Dropping and restoring Wi-Fi mid-session now recovers by itself.** The listener used to refuse every
  returning connection until its 120 s idle reap, while the phone gave up in ~60 s — every hiccup ended with
  `GAVE_UP` and a manual tap. The listener now parks the extra connection, verifies it is the same phone,
  and supersedes the dead session (`parking an additional connection` → `superseding the live session` →
  phone `CONNECTED` on the first attempt, heartbeats flowing).
- **P08 cable-out acceptance row closed**: ~33 minutes of mirroring with the USB cable physically out and
  USB debugging off, 10380 frames, 0 dropped, heartbeats every 15 s.
- **Phone→Mac clipboard verified cable-out**: 117 bytes applied on the Mac pasteboard, hash checked.
- **Protected content re-verified with fresh numbers**: the Wi-Fi password dialog mirrors at mean luma 0.18
  (black, status bar only) versus 12.75 with ordinary content on screen.
- The in-app Stop and the system screen-sharing chip remain unmeasurable on the S24: after the capture
  consent dialog its window manager loses focus and swallows every tap (reproduced four times).

## Verified — a first pairing completes, and the stop affordance exists (2026-11-24)

- **A fresh install can pair again.** Confirming the code on the phone used to be followed by the Mac closing
  the connection the instant the phone's heartbeat arrived, because confirming on the Mac is a human act that
  finishes a moment later; the confirm then failed and the two sides looped. The receiver now answers a
  keepalive before confirmation and still refuses every other frame, so the pin is stored once and normal
  heartbeats follow (`AUTO_CONFIRM_CODE_VERIFIED code=903 717` → `AUTHENTICATED`).
- **The ongoing capture notification — and its Stop action — is now visible.** `POST_NOTIFICATIONS` was
  declared but never requested, so a fresh install ran with `importance=NONE` and the notification was
  invisible; it is now requested exactly when capture starts, before the capture consent.
- **Starting capture no longer crashes after consent is granted.** The service validated its inputs before
  claiming the foreground state, which Android treats as a fatal violation
  (`ForegroundServiceDidNotStartInTimeException`); foreground is now claimed first.
- **30-minute continuous mirroring measured through the product path**: `received=10800 dropped=0`, phone
  `frames=10885 bytes=57106207 fps=5.5 maxSendMs=9`, no keyframe requests and no reconnects.
- **Reconnect re-announce measured live**: the listener was killed mid-stream and restored; the phone
  reconnected in 5.9 s, logged `re-announced format 590x1280 on new session gen=2`, and the new receiver
  resumed at `received=60 → 120 dropped=0`.

## Verified — a reconnect resumes the stream instead of showing black (2026-11-23)

- **Dropping the session and restoring it on the same address now resumes video.** Format re-announced on the new
  session (`MIRROR_FORMAT 590x1280`), then `received=60 → 120 → 180 dropped=0 status=rendering` — no restart, no
  tap, no re-discovery.
- **Three defects fixed, all found by watching frames not arrive:** the session generation only advanced on first
  pairing so reconnects never re-announced; the send queue's writer exited on the first post-reconnect failure and
  silently swallowed every later frame; and the re-announce was followed by a stale P-frame, which the Mac correctly
  rejects (`expected IDR after mirror.format`).
- **Adds `--p08-port`** to the Mac harness so a listener can be dropped and restored on a fixed address, which is how
  the reconnect scenario is reproduced without a human.

## Verified — background session survives a full night of deep Doze (2026-11-23)

- **The session is reconnectable overnight, not just while the screen is on.** 8 h 17 m unplugged in deep Doze:
  935 heartbeats, 21 automatic reconnects, every one authenticated on the stored pin with no prompt and no tap.
- **Doze freezes the app, and the session comes back.** 210 freezes of 20–45 s were survived in place; the long
  Doze windows (80, 45 and 30 minutes between 04:44 and 07:20) each ended with the phone reconnecting on its own.
- **The service is never killed.** `isForeground=true` after the night, no battery exemption, no wake lock, and the
  battery cost is 100 % → 75 % over 8 h (≈3 %/h) for the service, Wi-Fi and 21 reconnects.
- **Honest framing:** "connected overnight" means *reconnectable overnight*. Deep Doze drops the session about hourly,
  so the product must keep reconnect cheap, idempotent and invisible rather than promise one unbroken connection.

## Verified — Phase 4: the product streams the screen, survives rotation, recovers from a fault (2026-11-22)

- **Mirroring now runs over the one authenticated session.** Measured end to end: `mirror.format 590x1280`,
  60 → 644 frames, `dropped=0`, worst send 6 ms, rendered on the Mac. No second connection, no second pairing code.
- **A picture rotating no longer drops the connection.** Rotation used to log `session closed: stopped by app`
  inside 120 ms because the session lived in the activity and `onDestroy` stopped it. The session now belongs to
  the process, and rotating re-emits `mirror.format` (`1280x590` → `590x1280`) with frames continuing and no drops.
- **A scrambled frame recovers with a keyframe, not with P-frames.** The Mac's injected corrupt sample produced
  `KEYFRAME_REQUEST reason=validator-recovery`, the phone requested an IDR, and the stream resumed
  (`dropped=4`: the garbage sample and the frames before the IDR).
- **A reconnect no longer leaves a black stream.** A new session has no `mirror.format`, so the Mac had no SPS/PPS
  and silently discarded every frame; the sender now re-announces the format with a fresh IDR when the session
  generation changes. Implemented, not yet measured end to end.
- **Screen capture is a button, gated by the system dialog every time.** Scope selection is explicit
  (`Share one app` / `Share entire screen`); nothing starts at launch, and the capture service is `exported="false"`.
- **Defect found and fixed:** the app must not stop its session from `onDestroy` — that turned an orientation change
  into a dropped connection.

## Verified — Phase 3: clipboard sync both ways (2026-11-22)

- **Text copied on the phone can reach the Mac, and back.** Measured on the S24 Ultra against the Mac app
  (`p04 test 1795359008053` copied on the phone appeared on the Mac pasteboard; a 25-byte Mac copy was
  applied on the phone and acked). Both directions are explicit user actions.
- **A copy never bounces.** Text is identified by its SHA-256 plus the origin peer, so the phone refuses
  to send back what the Mac just gave it (`SEND_SKIPPED reason=echo`) and the Mac does not re-send what
  it just applied.
- **Sensitive clips stay where they are.** An Android clip flagged `EXTRA_IS_SENSITIVE` and a macOS
  pasteboard item marked `org.nspasteboard.ConcealedType` are refused by the sender with a visible
  explanation, so a password-manager copy is never moved by accident.
- **Sync can be paused from either side**, and a paused peer sends nothing (`CLIPBOARD paused by the phone`).
- **Nothing is stored or logged.** No history, no file, no clipboard text in logs — only hashes and byte
  counts. Transfers are capped at 64 KiB of UTF-8.
- **Android's real limits are surfaced, not hidden.** A clip sent while the app is backgrounded arrives
  and waits: the card shows "ready to apply" and the pasteboard is written only on a tap, because
  Android will not let a backgrounded app write it.
- **Defects the live run caught:** the clipboard ack died on `NetworkOnMainThreadException` (writes now go
  through one ordered writer coroutine), the card never refreshed because its revision was collected but
  never read, and the bridge was constructed after the first composition, crashing on `lateinit`.

## Verified — Phase 2: discovery, pairing and the authenticated session (2026-11-22)

- **The Android app can find the Mac.** Bonjour discovery resolves the advertised service and reads the protocol version from its TXT record (`pv=1`); a peer advertising a version this build does not speak is shown as incompatible and is never dialled.
- **Pairing requires the same code on both screens.** A first pairing shows a six-digit code on the phone and the Mac, and the session refuses to carry a single application byte until the user confirms the match. A stored pin skips the prompt; an unconfirmed session is never reported as connected.
- **The Mac refuses identities it has not paired with.** A rebuilt app with a new identity was rejected with a pin mismatch rather than accepted, so a silent re-pairing cannot happen.
- **A dropped connection reconnects once, with the stored pin, and keeps working.** Reconnect re-resolves the peer instead of dialling the remembered address, backs off exponentially with jitter, and never shows "connected" for a socket that has not answered a ping.
- **The Mac now publishes its protocol version.** The advertised TXT record was empty, so compatibility could not be checked and a stale advertisement looked identical to a live one.
- **Three defects found by the end-to-end run and fixed:** the socket connect ran on the main thread, the "needs confirmation" flag was inverted, and the reconnect path cancelled its own coroutine so every retry fired twice.

## Verified — audio capture measured, call audio closed (2026-11-22)

- **Media audio capture works and is bit-exact.** Under a MediaProjection consent, Android's playback capture reproduces an allowed app's audio exactly (`rms=5791.5 peak=8190` against a source amplitude of 8192), including a real third-party YouTube client. Public APIs only — no root, no Accessibility.
- **Call audio cannot be captured, and that is now measured rather than cited.** A stream published as `USAGE_VOICE_COMMUNICATION` with the most permissive policy an app can declare still arrives as silence. The exclusion belongs to the platform, so no prototype can reopen it.
- **A refused capture must not look like a quiet one.** Refusal is silent: the stream keeps its full frame rate and carries nothing. Anything built on audio has to measure amplitude, never frame arrival — otherwise it will report success while delivering silence.
- **Call notifications/control deferred**, because they need `READ_PHONE_STATE` or a notification listener and nothing in the MVP depends on them.
- **`RECORD_AUDIO` stays experiment-scoped.** It exists only in the isolated P12 experiment, never in the product app, and the experiment transmits nothing.

## Verified — P10 protection measured, P08 recovery, P15 ACL (2026-11-21)

- **Screen-capture protection measured as a number.** A synthetic `FLAG_SECURE` surface mirrors as black: mean luma 1.0 over 809 delivered frames, against 196.4 for the identical unprotected screen with the same motion. The mirrored password-field screen reads 238.1, i.e. it mirrors normally.
- **Locking ends the projection.** `system onStop (lock / screen sharing chip / user stop)` — nothing is mirrored while the device is locked, so lock-screen content cannot be disclosed through the mirror.
- **Mirror no longer stalls after a bad timestamp.** The stream validator could wait for a recovery keyframe that nothing requested; the receiver now asks for it, and an injected corrupt sample recovers to `status=rendering` with `dropped=0`.
- **Keychain prompts after a rebuild are explained and fixed for development.** Ad-hoc rebuilds invalidated the stored items' ACLs and blocked startup silently; builds now use the stable Apple Development identity, so no prompt appears and stored pairing survives rebuilds.

# Changelog

All notable project changes are recorded here.

## [Unreleased]

### Verified — P14 rows A–F green on the S24 Ultra (2026-11-21)
- Background session survives backgrounding (5 min), screen-off (`Dozing`, 5 min), a Wi-Fi off/on cycle, and a real reboot (`BOOT_COMPLETED` → connected 0.3 s after the broadcast), with live counters in the ongoing notification.
- The Mac receiver now holds AppKit automatic termination for `--p08-mirror`, `--advertise-on-launch` and `--listen`; it had been silently terminated between phases, which mimics a protocol failure.
- Deep Doze and overnight remain unmeasured: they need the handset unplugged, since `force-idle` refuses while charging.

### Changed — P11 measured: macOS does not prompt for Local Network here (2026-11-21)
- P11 closed on macOS 27.2.1 with a result that contradicts its premise: a sandboxed app browsing Bonjour and dialling a LAN address gets access with **no alert and no Settings entry**, so the denied-state rows are unreachable rather than failed. Reproduced under a Finder double-click and a `launchd` agent, from `/tmp` and `~/Applications`.
- **Correction:** the earlier "allowed-state" result measured **Terminal**, not the app — macOS attributes local-network access to the responsible process and auto-allows Terminal's children. Scripted launches are now explicitly invalid for this measurement.
- Added an isolated harness (`macos/Experiments/P11LocalNetwork/`) to re-measure this on future macOS versions; declarations, entitlements and the `.waiting` recovery path are kept (ADR-044, R-38).

### Verified — P17 accepted with USB unplugged and debugging off (2026-11-21)
- Acceptance run on the S24 Ultra with **USB disconnected, USB debugging off, wireless debugging off** (`adb devices` returned zero entries): one Classic HIDP session carried 48 reports and the receiver went `14/14/14/14 → 26/26/26/26` — every press delivered, no drops. Input came only from the Mac's explicitly triggered HID reports.
- P17 closes as **GO** for the Classic HIDP route on Android 12 and Android 17. Not a production-input approval: one key per user action, with open mapping, text entry and the wider input matrix still unproven.

### Added — P17 delivers input on the target S24 Ultra (2026-11-21)
- Bonded the S24 Ultra to the Mac with a **phone-initiated** pairing and the numeric code verified by the owner on both screens (`695843`).
- P17 Classic HIDP delivered arrow input on the target device: SDP `1330188580`, CoD `0x2540`, control `0x0011` + interrupt `0x0013` open, `0/0/0/0 → 1/1/1/1`, Android `BTHH_CONN_STATE_CONNECTED` over `BT_TRANSPORT_BR_EDR`. A 48-press burst landed `+12` per direction with no drops.
- Fixed a shutdown defect: `SIGTERM` (`pkill`) bypassed `applicationShouldTerminate`, leaving the SDP record published and the L2CAP channels open so the next launch was refused with `0xE00002BC`. The app now handles `SIGTERM`/`SIGINT` through the same stop path; a phone-side Bluetooth cycle clears an already-stale session.
- Both runs had USB debugging enabled, so the debugging-off acceptance run is still outstanding. See [P17](prototypes/P17-classic-hidp.md).

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

## Phase 6 hardening batch 2 (2026-11-24, internal)

- Connection card: when a session is live, the phone now shows its own short pairing code ("compare
  it if the Mac asks you to confirm a re-pair"). Closes the one-sided-forget gap measured on the S24:
  after the Mac forgot the pin, its screen displayed a code the phone gave nothing to compare against.
- One pin namespace: every Mac surface now uses the canonical peer id `android-p08` for the phone of
  record (ADR-053), so "forget the pin" clears exactly one record no matter which tab accepted the
  connection. The P03 harness default was unified to the same value.
- Diagnostics cheatsheet added to the P03 doc; permission matrix audited against the built APK and
  bundle (product permission diff vs Phase 5: none).

## Phase 6 hardening batch 3 (2026-11-25, internal)

- Pin store reworked (ADR-054): confirmed pins are a set keyed by nothing but the pins themselves, so
  two phones can be confirmed at once without fighting over one slot (measured problem: switching
  phones forced a forget-and-repair every time). Old per-peer-id keychain items migrate on first
  read. "Forget phone pin" clears the whole set.
