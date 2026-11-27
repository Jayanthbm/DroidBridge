# P17 — macOS Bluetooth Classic HIDP feasibility

**Status:** **GO on both the OnePlus Nord (Android 12 / API 31) and the target S24 Ultra (Android 17 / API 37)** with DroidBridge's own independent implementation. `P17ClassicHIDP`, authored from the HID Profile transaction set and Apple's public `IOBluetooth` surface, publishes the HID SDP record, opens the L2CAP control (`0x0011`) and interrupt (`0x0013`) channels, and delivers arrow press/release reports that Android routes to a focused app. On the S24 the counters went `0/0/0/0 → 1/1/1/1`, Android reported `BTHH_CONN_STATE_CONNECTED` over `BT_TRANSPORT_BR_EDR`, and a 48-press burst landed `+12` per direction with no drops. **Acceptance run passed on the S24 Ultra with USB unplugged, USB debugging off and wireless debugging off**: 48 presses in one session took the receiver from `14/14/14/14` to `26/26/26/26` — 48 of 48 delivered, no drops, driven entirely from the Mac with no ADB channel available (`adb devices` returned zero entries). This is a GO for the Classic HIDP input route on both Android versions tested. It is **not** a production-input approval. The promoted product app now implements a US keyboard mapping and paced ASCII text entry (`HIDKeyboardMap`, ADR-050), and one paced run on this device delivered `DroidBridge123` 14/14 characters (2026-11-27, below); that run is a recorded summary without an archived raw log, and pointer/touch-equivalent control, gestures and an input matrix remain unproven.

**Related:** [P16 BLE HID](P16-ble-hid.md) (NO-GO on Android for BLE), [evidence](evidence/p17/README.md), ADR-041, ADR-042, R-35, R-36.

## Objective

Determine whether a macOS app can act as a standard Bluetooth **Classic** HID device (`HIDP`) toward Android, and deliver keyboard input to a focused app, using only documented/public platform APIs within the project's constraints.

## Device and environment

| Item | Value |
|---|---|
| Android device | OnePlus Nord AC2001, Android 12 / API 31, build `AC2001_11_F.24` — **preliminary device; not the target acceptance device** |
| Android Bluetooth address | `5C:17:CF:B4:1B:B9` |
| Mac | MacBook Air `MacBookAir10,1`, Apple M1, macOS 27.2.1 build `26B131` |
| Mac Bluetooth address | `60:3E:5F:31:D0:6D` (same address used for LE advertising) |
| Xcode | 27.2 build `27B52` |
| USB connected | **No** (`dumpsys usb` → `connected=false`) |
| USB debugging enabled | **Yes** (`adb_enabled=1`) |
| Wireless debugging enabled | **No** (`adb_wifi_enabled=0`) |
| ADB transport | Wi-Fi `192.168.31.64:5555` |
| Bluetooth | On |

This run therefore does **not** satisfy final acceptance, which requires USB disconnected *and* both debugging modes disabled.

## Test vehicle

This run used **DroidBridge's own independent implementation**, `macos/Experiments/P17ClassicHIDP/` (target `P17ClassicHIDP`, bundle ID `com.droidbridge.experiment.p17classichidp`). An earlier feasibility pass used the unmodified upstream `jqssun/darwin-bt-remote` app (AGPL-3.0-only) purely as a probe; **no upstream source has been adopted**, and the AGPL-derived comparison artifact was deleted before it could be committed.

## Permissions and API surface used

- macOS: public `IOBluetooth` APIs only — `IOBluetoothSDPServiceRecord.publishedServiceRecord(with:)`, `IOBluetoothDevice.openConnection()`, `openL2CAPChannelSync(_:withPSM:delegate:)` for PSM `0x0011` and `0x0013`, and channel writes of `0xA1`-prefixed input reports.
- No Accessibility permission was granted or needed. The app displays an Accessibility alert for its unrelated *Direct Input* feature; it was dismissed with **Not now** and that path was never enabled.
- Android: no permission was added to the receiver. It continues to observe ordinary `onKeyDown`/`onKeyUp` arrow events.

**Research correction:** earlier project notes described the upstream Classic backend as containing "prohibited private-API behavior". Source review shows the KVC helper `HIDClassicDevice._privateBool` is declared and **never called** — dead code. The Classic HIDP path itself uses only public API. This removes the previously assumed private-API blocker for P17.

## Procedure

1. Phone paired to the Mac at OS level and listed in the app's device picker.
2. App switched to **Classic** transport; HID SDP record published.
3. App connected to the phone (Mac-initiated ACL). Phone screen awake, P16 receiver foregrounded.
4. Arrow keys pressed in the app's ArrowPad; results read from the receiver's own on-screen counters.
5. ADB was used only for setup (launching the receiver, collapsing the notification shade) and read-only sanitized diagnostics (`dumpsys`, and a narrowly scoped UI dump limited to the receiver's fixed counters). **No ADB command generated any functional input.**

## Results

**SDP and L2CAP (Mac log, 2026-11-20):**

```
17:58:37.651  SDP service published; handle=1330188580
18:00:05.634  opening ACL to 5c-17-cf-b4-1b-b9
18:00:07.636  control L2CAP open        (PSM 0x0011)
18:00:07.787  interrupt L2CAP open      (PSM 0x0013)
```

**Android HID host binding:**

```
Profile: HidHostService
  mInputDevices:
    60:3E:5F:31:D0:6D : 2
ConnectionState: STATE_CONNECTED
```

**Android input devices registered from the Mac:**

```
10: Jayanth's MacBook Air Mouse
11: Jayanth's MacBook Air Keyboard
12: Jayanth's MacBook Air Consumer Control
13: Jayanth's MacBook Air System Control
```

**Key delivery to the focused receiver** (machine-captured counter transition):

```
BASELINE 18:05:50 :: UP: 0  DOWN: 0  LEFT: 0  RIGHT: 0
CHANGED  18:05:56 :: UP: 2  DOWN: 2  LEFT: 1  RIGHT: 0
```

Later reading of the receiver screen:

```
UP: 8    DOWN: 3
LEFT: 3    RIGHT: 1
Events: UP down / UP up / UP down / UP up / UP down / UP up
        DOWN down / DOWN up / LEFT down / LEFT up / RIGHT down
```

Press **and** release pairs are present, so reports are well-formed and no key is stuck. Key events reached the app through Android's normal input pipeline — not through ADB.

## Independent DroidBridge implementation — delivery demonstrated

Independently authored from the HID Profile transaction set and Apple's public `IOBluetooth` surface; **no third-party implementation source is included**.

Implemented: HID SDP record (service class `0x1124`, L2CAP control `0x0011` and interrupt `0x0013` protocol lists, `HIDDescriptorList` with the report descriptor, boot-device and normally-connectable flags); outbound ACL plus both L2CAP channels; HIDP transactions (`GET_REPORT`, `SET_REPORT`, `GET_PROTOCOL`, `SET_PROTOCOL`, `GET_IDLE`, `SET_IDLE`, `HID_CONTROL`, handshakes); `0xA1`-prefixed input reports with report ID in Report Protocol and the boot layout in Boot Protocol; temporary local Class of Device `0x2540`; `disableAutomaticTermination`/`disableSuddenTermination`; a manual *Connect / Pair* control with an address field, a numeric-comparison panel that shows the code for verification, and an arrow send gate; and a harness mode (`--harness-connect <addr>`, `--harness-send UP,DOWN,LEFT,RIGHT`).

### Result (2026-11-20, OnePlus Nord AC2001 / Android 12)

```
19:54:51.413  SDP published (handle 1330188580)
19:54:51.414  class of device -> 0x2540
19:54:51.414  opening ACL to 5c-17-cf-b4-1b-b9
19:54:52.922  control channel open        (PSM 0x0011)
19:54:53.123  interrupt channel open      (PSM 0x0013)
19:54:59.329  harness sent UP
```

Receiver counters on the phone:

```
BASELINE            UP: 0    DOWN: 0 / LEFT: 0  RIGHT: 0
AFTER run 1 (UP)    UP: 1    DOWN: 0 / LEFT: 0  RIGHT: 0
AFTER run 2 (all 4) UP: 2    DOWN: 1 / LEFT: 1  RIGHT: 1
```

Run 2 sent `UP, DOWN, LEFT, RIGHT` over a **single** control/interrupt pair, and Android's HID host reported `60:3E:5F:31:D0:6D [BR/EDR] … ConnectionState: STATE_CONNECTED`. Full transcript: [evidence/p17](evidence/p17/README.md).

### Stability (2026-11-20, 48-press burst)

`UP,DOWN,LEFT,RIGHT` repeated 12 times, 900 ms apart, in one session:

```
DELTA  UP +12   DOWN +12   LEFT +12   RIGHT +12      (48 reports sent)
```

Every press was delivered exactly once — no drops, no duplicates, no stuck keys.

### Reconnect and recovery

| Scenario | Result |
|---|---|
| Quit while connected, relaunch 2 s later | **Reconnects cleanly** — `stop()` on `applicationShouldTerminate` closes both channels, closes the ACL and withdraws the SDP record, so the next session opens `0x0011`/`0x0013` and delivers a key |
| App killed (no cleanup) while connected | Next session refused with `0xE00002BC`; Android already reports `STATE_DISCONNECTED`, so the stale state is Mac-side. Persisted **≥ 8 min** |
| Recovery after an unclean exit | Cycling the phone's Bluetooth adapter clears it; the bond survives (`[BR/EDR] Jayanth's MacBook Air`) |
| Phone Bluetooth off/on | Bond retained; next session connects and delivers input |

### Host-side obstacles found and resolved

1. **macOS 26 requires Bluetooth privacy consent.** Until `kTCCServiceBluetoothAlways` was granted, every IOBluetooth call blocked and surfaced as `L2CAP control channel (0x0011) failed (0xE00002BC)` / `openConnection failed (0xE00002D6)`. `tccd` logged `AUTHREQ_PROMPTING … service=kTCCServiceBluetoothAlways`, and re-signing the app invalidated the previous grant until a stable Apple Development identity was used. **These error codes are not protocol failures** — do not diagnose them as pairing or SDP faults. The target is ad-hoc signed like the other experiments, so for repeated runs it was re-signed with the owner's Apple Development identity after each build (`codesign --force --deep --sign … --entitlements … --options runtime`); otherwise every rebuild re-prompts. The grant itself is per bundle ID and is a manual, one-time user action.
2. **`IOBluetoothDevice.pairedDevices()` deadlocks on the main thread.** Its first use initialises IOBluetooth's CoreBluetooth coordinator, which waits on a semaphore signalled from the main thread, and touching IOBluetooth from two threads at once makes them wait on each other's `dispatch_once`. The app now warms the framework up off the main thread and refreshes peers on a background queue.
3. **Programmatic pairing loses the single Classic pairing-agent slot.** `bluetoothd` showed our session registering with `programmatic pairing disabled`, then `programmatic pairing enabled` → `Classic pairing agent already exist`, because CleanMyMac4's `bluetooth-device-fetch-xpcservice` re-registers a Classic agent about once per second. The numeric-comparison value arrived and the user accepted it, but `replyUserConfirmation(_:)` was never delivered: the bond failed with `0x0000001F` after the peer's SSP timeout and the Mac side also logged `BT_ERROR_AUTHENTICATION_FAILURE`. The bond that made this run work was **phone-initiated** (`btif_dm_create_bond`), which does not need that slot. Pairing is an OS-level concern here; HIDP itself is unaffected.
4. **An unclean exit locks the next session (Mac-side).** If the app dies without withdrawing its SDP record and closing its channels, the next launch is refused with `0xE00002BC` even though Android already reports `STATE_DISCONNECTED`; the lock persisted at least 8 minutes and only a phone Bluetooth cycle cleared it. Calling `stop()` from `applicationShouldTerminate` fixes the ordinary quit path — an immediate relaunch then reconnects and delivers input — but a crash or force-quit still needs the adapter cycle. All reports of a run must also travel over one control/interrupt pair.
5. **A stale local bond blocks everything.** If the phone drops its half while the Mac keeps its record, the Mac reuses the dead link key, paging times out (`0xE00002D6`) or authentication fails, and there is no public API to delete the local record (`IOBluetoothDevice` exposes only `removeFromFavorites`). Forgetting the device on the Mac is a manual prerequisite.

## Known host-side fragility

macOS **automatic termination** killed the upstream probe 16 seconds after the channels opened in an earlier attempt (`18:00:24 App termination approved`), silently dropping the HID connection. The app must stay running and visible during use; the independent implementation suppresses automatic and sudden termination and surfaces connection state, and no termination occurred during the runs above.

## Limitations — what this does not prove

- **No S24 Ultra evidence (historical, for the 2026-11-20 run).** The target device rows follow in the Phase 5 sections; the OnePlus Android 12 result remains preliminary for One UI differences.
- **Keyboard usages only.** Arrows, and on the promoted build US-ASCII characters typed into a focused `EditText`. Pointer, consumer/system controls, drag, scroll, gestures and arbitrary touch-equivalent control are unproven. Text entry is proven for one 14-character string in a Debug build with ADB attached; it is not a password-entry path (R-19).
- Arrow presses are measured at 48 in one session (12 per direction, zero drops or duplicates) plus the earlier runs. Modifier handling, rollover, key repeat, simultaneous multi-key chords and long-session stability beyond one minute are untested.
- Force-quit or crash recovery still requires cycling the phone's Bluetooth adapter.
- Android 17 / One UI 9 masks password characters from physical keyboards (R-19), so HID typing is not a password-entry path.
- No claim about secure/`FLAG_SECURE` apps, banking apps, or protected surfaces.
- **Debugging was enabled** (`adb_enabled=1`) with ADB over Wi-Fi; final acceptance requires both debugging modes disabled. Out-of-range behaviour, supervision-timeout recovery and bond revocation were not measured.
- Pairing had to be phone-initiated. Programmatic pairing from this app could not be completed on this Mac while another app held the single Classic pairing agent, so the app's own `IOBluetoothDevicePair` flow is implemented but **not proven end-to-end** here; the 2026-11-26 attempt that compared codes refused a mismatch rather than confirming an unverified pair.
- The AGPL test vehicle from the earlier feasibility pass cannot be shipped; nothing from it is in the repository.

## Conclusion

**GO** for the Classic HIDP route on Android using DroidBridge's own implementation, demonstrated on one OnePlus Nord AC2001 / Android 12 device with USB disconnected. **BLE HOGP from this Mac to Android is NO-GO** (see [P16](P16-ble-hid.md) and ADR-041). The S24 Ultra reproduction and the both-debugging-modes-disabled run have since passed (Phase 5 rows below), and keyboard mapping plus paced text entry were added on 2026-11-27. What remains before a production input claim: the **pairing-prompt row** (a fresh bond answered and verified on both screens) and a **captured raw log** for the text-entry row. Treat the macOS Bluetooth privacy grant, the single pairing-agent slot and the one-session-per-connection rule as known environment constraints (ADR-042).

## Phase 5 device-acceptance checklist (promoted build, opened 2026-11-25)

The peripheral now lives in `DroidBridge.app` (ADR-050). The P17 evidence does not transfer to the
promoted build without at least one verification run, so the next device session measures:

| # | Row | Procedure | Pass criterion |
|---|---|---|---|
| 1 | Publish from the product app | `DroidBridge --p17-hid`, tap **Publish HID service** | `published: yes`; the button self-disables |
| 2 | Pairing prompt on the phone | Connect from the Mac's **Paired peers** list (or by address); answer the SSP prompt on the phone | Pairing completes; the Mac's log shows the HID handshake (control + interrupt channels open, `HID channels ready: yes`) |
| 3 | Protocol negotiation | Observe the negotiated mode with the phone connected | `Negotiated protocol: Report` (or Boot if the host asks); no error line |
| 4 | Arrow delivery (one per confirmed check) | On the phone, open the DroidBridge receiver (or any harmless screen the owner chose); on the Mac, toggle the foreground confirmation and press one arrow | The phone receives the key event; `Input reports written` increments by 1; the toggle resets |
| 5 | Clean shutdown | Quit the app (⌘Q and separately `pkill -TERM`) while connected | `stopped` in the activity log; the next launch publishes and reconnects without `0xE00002BC` |
| 6 | Cross-device | Repeat 2–4 on the OnePlus Nord (Android 12) and the S24 Ultra (Android 17) | Both PASS |

Run conditions to record per row: USB connected yes/no, USB debugging, wireless debugging. The one-key-per-
confirmation rule is unchanged for single keys; the US keyboard mapping and paced text entry described below
were added to the promoted build on 2026-11-27 under the owner's remote-keyboard scope. An input matrix
(chords, key repeat, rollover) and pointer control remain out of scope until separately approved.

**Row status at 2026-11-27:** rows 1 (publish), 3 (negotiation), 4 (arrows), 5 (clean shutdown) and 6
(cross-device) pass on both devices — S24 Ultra 2026-11-24 and OnePlus Nord 2026-11-24. Row 4 also passes
with USB unplugged and both debugging modes off from the P17 acceptance run (48/48 presses). **Row 2, the
pairing prompt, is still unmeasured**, and the one attempt at it ended in a correct refusal (see below).

Outstanding from 2026-11-24 (same session or next): the Mac→phone clipboard ack needs one reconnect; the P10
cable-out variant wants the password dialog open while unplugged.

## Phase 5 device-acceptance rows measured 2026-11-24 (promoted build, S24 Ultra)

Run conditions: USB connected, USB debugging on; ADB drove the phone (receiver launch, taps) and AppleScript
clicks drove the Mac's own UI; no ADB input entered the HID path. The S24 was already bonded from P17, so no
SSP prompt was exercised in this run.

| Row | Result |
|---|---|
| Publish from the product app | **Pass** — `published: yes`, button self-disabled |
| Connect | **Pass** — `Connected peer: c0d5e2f6d7c2`, `HID channels ready: yes`; activity log `SDP published (handle …) → opening ACL → class of device 0x2540 → control channel open → interrupt channel open` |
| Protocol negotiation | **Pass** — `Negotiated protocol: Report`, no errors |
| Arrow delivery (one per confirmed check) | **Pass** — UP/DOWN/LEFT/RIGHT each incremented the receiver's counters exactly once per press (UP: 1 → 1/1/0/0 … 1/1/1/1), and again after a reconnect (UP: 2) |
| Clean shutdown | **Pass (AppleEvent quit)** — quit while connected, relaunch republished without `0xE00002BC` |
| Cross-device | **Pending** — the OnePlus Nord was not attached; row 2–4 repeat there |

**Measured follow-up (not papered over):** killing the connected app with SIGTERM left stale L2CAP state
that the next instance's connect failed with (`L2CAP control channel (0x0011) failed (0xE00002BC)`), which
survived cycling Bluetooth on the phone; it cleared after cycling the Mac's Bluetooth and relaunching the
app. P17's earlier note said one side's cycle sufficed. The likely cause is the SIGTERM handler calling
`exit(0)` immediately after `stop()`, racing bluetoothd's teardown. Follow-up: make the shutdown path
awaitable before exit.

The pairing-prompt row and the OnePlus repeat remain for a session where the OnePlus is attached (and for a
freshly unpaired device if one is convenient).

## Cross-device row measured 2026-11-24 (promoted build, OnePlus Nord AC2001, Android 12 / API 31)

Run conditions: USB connected, USB debugging on; ADB drove the phone, AppleScript clicks drove the Mac's
own UI. The OnePlus was bonded from P17, so no SSP prompt was exercised.

| Row | Result |
|---|---|
| Connect | **Pass** — `Connected peer: 5c17cfb41bb9`, `HID channels ready: yes`, `Negotiated protocol: Report` |
| Arrow delivery (one per confirmed check) | **Pass** — receiver counters `UP: 1 DOWN: 1 LEFT: 1 RIGHT: 1`, one press per confirmation toggle |

Both devices of record have now verified the promoted build end to end (publish → connect → negotiate →
four confirmed arrows). What remains open for Phase 5: the **pairing-prompt row**, which needs a device
whose HID bond is removed first (unpair on one side, reconnect, answer the SSP prompt), and that row is
shared with the Android-side acceptance evidence for pairing UX.

### Pairing-prompt attempt 2026-11-26 — refused, row still unmeasured

A fresh bond could not be established: the Mac's programmatic numeric-comparison callback never fired (the
documented single Classic pairing-agent conflict, above), and the harness compared the two six-digit values
instead of guessing. They did **not** match (Mac `108952`, phone `456266` — the phone dialog was stale), so
neither side was confirmed. That is the correct outcome, not a pairing pass. The transcript is
`Docs/evidence/p17/pairing-mismatch-refused.log`; it was previously stored as
`pairing-prompt-verification.log`, a name that implied a verification that never happened. The bond used by
every other Phase 5 row is the pre-existing Classic bond, so those rows are unaffected.

## Fresh-bond attempt 2026-11-27 — macOS 27 cannot reply to the pairing code (row still unmeasured)

Run conditions: USB connected, `adb_enabled=1`, `adb_wifi=0`. ADB navigated the phone's Bluetooth
settings and, **after** verifying that the two six-digit values matched, tapped **Pair**; that consent
was granted through automation at the owner's instruction. Nothing else on the phone was touched.

What was measured:

- The existing Classic bond was removed on both sides (phone Settings, Mac `blueutil --unpair`) to test
  the row on a genuinely fresh bond.
- Mac-initiated pairing reached the phone reliably. **Five separate attempts** produced matching codes
  on both screens: `111169`, `160297`, `821954`, `820963`, `482504`.
- Answering the prompt through the app produced:
  `pairing confirmation: accept` →
  `IOBluetoothDevicePair BluetoothHCIUserConfirmationRequestReply no-op. Should be done at CB Layer` →
  `Pairing failed (0x0000001F)`. On macOS 27 the public `IOBluetoothDevicePair.replyUserConfirmation`
  path no longer delivers the numeric-comparison reply, so **the app's own Accept button cannot complete
  a fresh bond on this OS**, even though the codes matched.
- `blueutil --pair` failed identically (`0x1f`) with the code answered yes on the Mac and Pair tapped on
  the phone, so this is not specific to the app's implementation.
- The phone-initiated path used in 2026-11-20 is unavailable on macOS 27: `blueutil --discoverable 1`
  fails (`Failed to switch bluetooth discoverable on in 10 seconds`) and reads `0` even with the
  Bluetooth settings pane frontmost, and the phone's scan lists no available devices.
- Net result: **no bond exists on either side** after these attempts. The row is still unmeasured, and the
  earlier Phase 5 rows remain valid only because they used a bond created on an earlier macOS.

Consequence recorded rather than papered over: the HID keyboard needs a Classic bond, and on macOS 27
this build cannot create one. Recovery candidates are an Android-initiated bond to a known address (no
discovery needed, but it requires `BLUETOOTH_CONNECT` in a receive-side experiment module — a permission
addition that needs owner approval) or a macOS version that still honours the reply.

## Production keyboard and text entry measured 2026-11-27 (promoted build, S24 Ultra)

Run conditions: USB connected, `adb_enabled=1`, `adb_wifi=0`, screen held on. ADB was used for install,
diagnostics, screen wake and focusing the receiver field only; every functional keystroke travelled over the
Classic HID link under test.

- Receiver: `:experiments:bluetooth-hid` `HidReceiverActivity` with a focused `EditText`.
- Harness: `--p17-hid --harness-publish --harness-connect c0-d5-e2-f6-d7-c2 --harness-type "DroidBridge123"`,
  reports paced at 120 ms/character.
- Result: the receiver field contained exactly `DroidBridge123` — **14/14 characters**, including
  shift-uppercase and digits.
- First, unpaced run delivered only `Droi` (4/14): Android's HID host drops back-to-back interrupt reports.
  `typeText` is now async and paces each character; a Bluetooth cycle cleared the stale link before the
  passing run.
- **Evidence limitation:** the passing result is recorded from the harness and receiver as a summary. The
  Mac unified log for that run was not archived, so this row is not yet a captured-evidence row; the next
  run repeats it with the log saved under `Docs/evidence/p17/`.
- Note: [text-entry-s24.md](../evidence/p17/text-entry-s24.md).

## SIGTERM teardown — measured 2026-11-24, four variants, still open (ADR-052)

On the promoted build with the S24 Ultra connected (`channels ready: yes`), `kill -TERM` was measured
four ways. All four left the next launch's connect failing until the Mac's Bluetooth was cycled:

| Variant | Next connect |
|---|---|
| `stop()` + immediate `exit(0)` | `0xE00002BC` |
| `stop()` + 1.5 s hold + `exit(0)` | `0xE00002BC` |
| SDP withdrawn first + ACL dropped + close callbacks awaited (≤4 s) + `exit(0)` | `0xE00002BC` |
| Same awaited teardown + `NSApp.terminate(nil)` | `0xE00002BC` |

The failure symptom alternates between `0xE00002BC` (bluetoothd holds the session) and `0xE00002D6`
(`kIOReturnTimeout` — the phone's HID host stops answering the PSM 17 open while holding a half-open
session). Recovery in every case: cycle the Mac's Bluetooth, relaunch, publish, connect.

One false lead worth recording for the next attempt: during these measurements a connect dialled the
wrong peer (an offline earbud) and produced `0xE00002D6` — check the ACL address in the activity log
(`opening ACL to <address>`) before blaming Bluetooth state.

Leading suspect for the half-open state: the phone's HID host auto-reconnects when the control
channel drops while the SDP record is still advertised, so the dying process briefly owns a fresh
session. The current code withdraws the record first; whether the reconnect still happens in the
sub-second gap is not yet measured.

Kept (correct regardless): record withdrawn before close, ACL dropped explicitly, close callbacks own
the cleanup, termination awaits them (bounded) and exits through AppKit rather than `exit(0)`.

### Instrumented SIGTERM teardown 18:19 (same day) — the phone is clean; bluetoothd is the holder

With the phone's full Bluetooth stack captured over `logcat` across a SIGTERM of the connected app:

- `.417/.418` — the phone's HID host receives proper L2CAP disconnect indications for both CIDs
  (`hidh_l2cif_disconnect_ind`), closes the UHID fd, and the HID host state machine goes
  `2 (CONNECTED) → 3 (CONNECTING) → 0 (NONE)` within 30 ms. **The phone ends clean and does not
  retry.** No reconnect attempt followed, despite the record-withdrawal gap being sub-second.
- 90 s later (ruled out a slow-cleanup delay), a relaunch connect still failed `0xE00002BC`, with
  bluetoothd logging `No known channel on <peer> with psm 17`.

Conclusion: the half-open state is Mac-side bluetoothd, not the phone, and not a transient cleanup
window. It survives every measured app-side teardown; only cycling the Mac's Bluetooth clears it.
The next fix attempt needs bluetoothd's own view (what it thinks it is holding), not more app-side
ordering changes.

## Pairing-revocation device verification 2026-11-24 (forget-pin flow, S24 Ultra)

Run conditions: USB connected, USB debugging on; ADB drove the phone, AppleScript clicks drove the Mac's
own UI. The state under test was real, not staged: the Mirror-tab listener uses the default peer id
`android-p08`, whose stored pin was stale from an earlier identity era, so the phone's connect produced
a genuine `PIN MISMATCH` (expected `4ba527ba…`, got the phone's current `f68f9062…`).

Measured flow:
1. `PIN MISMATCH` on connect → after repeated failures the listener closes itself
   (`CLOSED too many failed handshakes`) — the failure is loud, not silent.
2. **Forget phone pin** → `FORGET done peer-id=android-p08`.
3. Listener restart → phone connects → Mac `awaitingConfirmation`, code `903 717`, verified against the
   phone's recorded pairing code (the same device identity — the code matches the Phase 2 record).
4. Confirm pairing → `PAIRING_CONFIRMED`, `pin stored for android-p08`.
5. Clipboard over the re-paired session: offer → `reason=accept` → held → applied on tap →
   `clipboard.ack` on the Mac. The re-paired session is fully functional.

Findings recorded on the way:
- **Two peer ids, two pins.** The harness listener (`com.droidbridge.app`) and the Mirror-tab listener
  (`android-p08`) store pins under different keychain keys. The stale-pin mismatch this session was
  between them. Worth a follow-up decision: share one peer-id/pin namespace, or surface which peer id
  a pin belongs to in the UI.
- **Asymmetric re-pair gap (minor):** after a one-sided forget (Mac forgot, phone still holds its
  pin), the phone authenticates immediately and displays *no code* while the Mac demands confirmation.
  The Mac's code has no counterpart to compare against in the phone UI unless the phone also forgot.
  The phone should surface its own short code while the peer is unconfirmed.

The Android revocation button ("Forget the Mac's pin") is in the installed build and renders; its
behaviour (session dropped, pins cleared, next connection is a first pairing) follows the same
controller path already verified by unit review and awaits its own device pass.
