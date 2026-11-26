# Phase 3 — Clipboard MVP

Phase 3 turns the P04/P05/P06 feasibility findings into product behaviour on both platforms:
Mac pasteboard monitoring, Android manual send, Android manual/foreground receive, Mac pasteboard
update, loop prevention, pause, deduplication and a size limit — and no history by default.

## What was built

| Layer | File | What it owns |
| --- | --- | --- |
| Wire contract | `shared/protocol/.../session/ControlMessage.kt` | message codes `0x30`–`0x35` |
| Shared rules + tests | `shared/protocol/.../clipboard/ClipboardSync.kt` (+ `ClipboardSyncTest.kt`, 12 tests) | hashing, limits, `shouldSend`, `evaluateIncoming`, offer JSON |
| Android | `android/feature/clipboard/ClipboardBridge.kt` | clipboard reads/writes, echo suppression, pause, pending transfer |
| Android UI | `android/feature/clipboard/ClipboardCard.kt`, `:feature:dashboard` | send / apply / dismiss / pause |
| Android transport | `android/core/network/.../SessionController.kt` | hands clipboard frames to the feature, single-writer outbox |
| macOS | `macos/Core/ClipboardBridgeMac.swift` | pasteboard polling, concealed-item refusal, apply + ack |
| macOS UI | `macos/Features/Video/LiveMirrorView.swift` | send button, pause toggle, status |
| macOS session | `macos/Core/LiveMirrorReceiver.swift` | routes `0x30`–`0x35`, starts polling only once authenticated |

Rules that both sides implement, because a rule only one side follows prevents nothing:

1. **Origin.** Text that originated here is never applied back when the peer echoes it.
2. **Content hash.** Offer, body and ack carry the same SHA-256; a mismatched body is refused before
   the clipboard changes.
3. **Sensitive clips are not sent.** Android `ClipDescription.EXTRA_IS_SENSITIVE` (API 33+) and the
   macOS `org.nspasteboard.ConcealedType` marker both suppress a send.
4. **Nothing persists.** No text, no hash history, no file. Hash and pause state live in memory.
5. **Refuse rather than pretend.** Every refusal is reported to the peer as `clipboard.reject` with
   the wire reason, and shown on screen.

## Measured evidence

Run: **2026-11-22 20:19–20:24**, S24 Ultra `SM-S928B`, Android 17 / API 37 (`RZCY81TKBSV`),
MacBook Air M1 macOS 27.2.1, same Wi-Fi. USB **connected**, USB debugging **enabled**, wireless
debugging **disabled**. ADB was used to tap the app's own buttons and to read the UI hierarchy; it did
not produce any clipboard content under test. Text placed on the phone clipboard came from a real app
action (the P04 experiment's write button); text placed on the Mac pasteboard came from `pbcopy` and
one deliberately concealed write.

| # | Row | Result | Evidence |
| --- | --- | --- | --- |
| 1 | Mac → phone, foreground | **PASS** | Mac `CLIPBOARD sent 25 bytes` → phone `OFFER hash=5b117a46 bytes=25 reason=accept`, `TEXT_READY hash=5b117a46 bytes=25`, tap → `APPLIED hash=5b117a46 bytes=25`, `sent clipboard_ack:5:5b117a46…`, Mac `CLIPBOARD phone applied the text` |
| 2 | Phone → Mac | **PASS** | phone `SEND hash=c8887aa0 bytes=22 offered=true delivered=true` → Mac `offer accepted (22 bytes)`, `applied 22 bytes from the phone`, `pbpaste` = `p04 test 1795359008053` (the exact text the experiment app wrote) |
| 3 | Echo prevention, phone side | **PASS** | after row 1 the phone re-sent the text it had applied: `SEND_SKIPPED reason=echo hash=11d4347b`, UI `skipped: this text came from the Mac` |
| 4 | Echo prevention, Mac side | **PASS** | after row 2 no second `sent … bytes` line appeared for the 22-byte text — the last send remained the 25-byte Mac → phone offer |
| 5 | Multi-byte UTF-8 | **PASS** | 20-byte text containing `🚀` and `\n`: Mac `sent 20 bytes`, phone `OFFER bytes=20` / `TEXT_READY bytes=20`, hashes equal on both sides |
| 6 | Pause (phone → Mac direction) | **PASS** | phone `PAUSED value=true` → Mac `CLIPBOARD paused by the phone`; a `pbcopy` afterwards produced no offer |
| 7 | Receive while the app is backgrounded | **PASS (with the documented Android limit)** | phone backgrounded (HOME), Mac copied 42 bytes: `OFFER hash=c35d60b5 bytes=42 reason=accept` + `TEXT_READY`; on return the card showed `ready to apply 42 bytes`, and tapping applied it (`APPLIED`, Mac `phone applied the text`) |
| 8 | Sensitive clip, Android | **PASS** | experiment wrote a clip flagged `EXTRA_IS_SENSITIVE`: product app `SEND_SKIPPED reason=sensitive`, UI `this clipboard entry is marked sensitive and was not sent` |
| 9 | Concealed clip, macOS | **PASS** | a pasteboard item written with `org.nspasteboard.ConcealedType`: `CLIPBOARD skipped a concealed pasteboard entry`, no offer reached the phone |
| 10 | Size limit (64 KiB) | unit-tested | `ClipboardSyncTest` refuses an oversized offer before the body; not exercised end-to-end because neither side will send one |
| 11 | Pause (Mac → phone direction) | unit-tested | `shouldSend` returns false while paused |

## Defects found by building it

Three real defects, all found by running the product rather than by reading it:

1. **`NetworkOnMainThreadException` on the clipboard ack.** `sendControl` wrote the socket on the
   caller's thread, and the apply path runs in a Compose click handler, so the ack that tells the Mac
   "the phone applied it" was silently dropped. Fixed at the transport: one writer coroutine drains an
   ordered outbox, so every caller may send from any thread and offer/body pairs keep their order
   (ADR-046 decision 1 extended).
2. **The card never refreshed.** The revision counter was collected but never read in the composition,
   so an arriving offer left the UI showing `idle`. Fixed by keying the rendered clipboard state on
   the revision.
3. **Startup crash.** The bridge was constructed after `setContent`, so the first composition hit
   `lateinit property clipboard has not been initialized`. Fixed by constructing it before the
   composition, which is the same class of bug Phase 2 hit from starting work out of order.

## Limits, stated plainly

- **Android cannot read the clipboard in the background** (P04). Sending is therefore a button, not a
  watch, and the UI says so.
- **Android cannot write the clipboard reliably without focus.** An offer that arrives while the app
  is backgrounded is accepted and held in memory, then applied on a tap. Nothing is written behind the
  user's back, and the Mac only learns of success from the ack.
- **No `copy`/`cut` interception.** There is no clipboard listener in the product: a copy is sent when
  the user asks for it.
- **No history, no persistence, no logging of content.** Logs carry hashes and byte counts only.
- A second `createVirtualDisplay` limitation from P01 is unrelated here but still open for the audio
  product track.

## Not measured

- A live cross-network or throttled link (clipboard was measured on one Wi-Fi LAN).
- Clipboard during Doze or while the phone is locked — P14 covers session survival, not clipboard UX.
- Rich content types, images and file promises: text only, by design for the MVP.

## 2026-11-24: a Phase 4 regression in Mac→phone receive, found and fixed

Running the Phase 3 rows against the current build exposed a silent regression introduced by the Phase 4
`SessionController` hook work: the shared `when` block routed `CLIPBOARD_OFFER/TEXT/ACCEPT/ACK/REJECT` into
`onMirrorMessage` — the mirroring hook, which only handles `MIRROR_KEYFRAME_REQUEST` — so clipboard messages
never reached `onClipboardMessage` and the `ClipboardBridge`. The log still said `handler=true`, the card
stayed `idle`, and no ack was ever sent. Mac→phone receive had been dead since the 2026-11-22/23 Phase 4
commits; only the phone→Mac direction (which does not use the receive dispatch) kept working, which is why
the 2026-11-23 Phase 3 measurements and today's cable-out phone→Mac row both passed while receive failed.

The dispatch now routes clipboard types to `onClipboardMessage` and MIRROR types to `onMirrorMessage`.
Re-measured end to end on the S24 Ultra: `CLIPBOARD sent 26 bytes` (Mac) → `OFFER hash=ba6dd913 bytes=27
reason=accept` → `TEXT_READY` → card `ready to apply 27 bytes` → tap **Put it on the clipboard** →
`APPLIED hash=ba6dd913 bytes=27` → Mac `clipboard.ack:17:ba6dd913…` and `CLIPBOARD phone applied the text`.
The send path then logged `SEND_SKIPPED reason=echo`, the loop-prevention rule firing exactly as designed
(the phone's clipboard now holds the Mac's text).

Note: the apply step needed the card's button because Android can only write the clipboard while the app is
on screen; the offer was held pending foreground state at arrival time.

## 2026-11-26: cable-out, no-debugging rows (Phase 3 close)

The 2026-11-22 matrix ran with USB connected and USB debugging enabled. The one condition left for the
phase was the same behaviour with the cable out and both debugging modes off, which was performed as
part of the v1 acceptance sweep.

- **Device/build.** S24 Ultra `RZCY81TKBSV`, SM-S928B, Android 17, `com.droidbridge.app` 0.1.0 debug
  APK; Debug `DroidBridge.app` from `macos/build`, plainly launched, **Start listening** pressed by the
  owner.
- **State.** **USB connected: no · USB debugging: off · wireless debugging: off · Bluetooth: off.**
  Stated at the row and verified with `adb devices`, which listed only `192.168.31.64:5555` (the
  OnePlus, untouched); Developer options were switched off on the phone, so no ADB path to it existed.
- **Method.** Both directions were performed by the owner at the two keyboards. No ADB input, no
  `pbcopy`/`pbpaste`, no screen capture and no tool access to either clipboard - the point of the row is
  that the app moves the text, so the harness must not touch it.
- **Phone → Mac.** `droidbridge-clip-test1` copied on the phone by hand, `Send phone clipboard` tapped,
  the Mac's offer applied (`Put it on the clipboard`), pasted into TextEdit: exactly
  `droidbridge-clip-test1`.
- **Mac → phone.** `droidbridge-clip-test-2` copied on the Mac by hand (⌘C), the Mac's send action
  used, the phone's offer applied (`Put it on the clipboard`), pasted into a phone text field: exactly
  `droidbridge-clip-test-2`.
- **Result: PASS, both directions, cable out and all debugging off.** This closes the phase's remaining
  condition; the 2026-11-22 rows remain the detailed matrix (echo prevention, UTF-8, pause,
  sensitive/concealed refusal, size limit) and are unchanged.
- **Not covered by this row.** The 2026-11-22 limits still stand: no background clipboard read on
  Android, no write without foreground focus, no `copy`/`cut` interception, text only, no history. Echo
  prevention and the sensitive/concealed refusals were not re-exercised tonight - the sweep row tested
  the two sync directions only.
