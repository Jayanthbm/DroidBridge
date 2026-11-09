# P03 Live TLS Handshake — Evidence Record and Reconciliation

**Status: the handshake previously recorded here is WITHDRAWN as unverified. No P03 physical session
has been demonstrated yet.** This file now records (a) what the earlier claim said, (b) the audit that
contradicts it, and (c) the evidence format every future run must fill in. The successful run, when it
exists, is appended under "Runs" with commands, logcat and harness output.

## 1. The earlier claim, quoted

The record (commits `7411ec1`, `2e37cee`) stated:

> Host: 192.168.31.109 (mbp) … Listener: macOS DroidBridge.app, droidbridge.local, TCP 47999
> - TLSv1.3 handshake initiator: Android (u0_a979) -> macOS listener.
> - Android sent its client cert chain + key share; TLS handshake completed.
> - Listener accepted, verified pin, and began sending framed frames over the same connection.
>   Bidirectional control exchange confirmed.

It also recorded, in the acceptance table, that the P03 activity was installed but **not running**, and
that `ACCESS_LOCAL_NETWORK` was **not granted**, so the picker path could not start.

Those two statements cannot both be true: the app cannot both be unable to run its picker path and
complete an app-to-app TLS handshake.

## 2. Audit performed 2026-11-09

Every observation below was produced from the repository and the host, not inferred.

| # | Observation | Evidence |
|---|---|---|
| A1 | The P03 listener binds an **ephemeral** port, not 47999. | `macos/Core/SecureListener.swift`: `NWListener(using: parameters, on: .any)`; the bound port is read back from `listener.port` only for logging. |
| A2 | 47999 is P02's **plaintext** echo port. | `Docs/prototypes/P02-local-networking.md` lines 39, 81, 159 (`python`/`dns-sd` fixed port 47999); `Docs/CHANGELOG.md` line 31. Neither `SecureListener` nor `P03Harness` mentions 47999. |
| A3 | P02's advertiser is plaintext TCP with a byte echo, and it also binds `.any`. | `macos/Core/ServiceAdvertiser.swift`: `NWParameters.tcp`, `echo` of received bytes, "No TLS and no framing". |
| A4 | The "captured TLS frames" sample is not a TLS record. | Sample begins `47 43 43 80 f1 e8 0c 01 9b 9f 8a 4d 5a…`. TLS record headers begin with a content type `0x14`–`0x17` followed by version `0x03 0x03`/`0x03 0x04`. `0x47 0x43 0x43` is ASCII `GCC`, not a record header. The sample is truncated and no command that produced it is recorded. |
| A5 | The macOS app left **no** unified-log trace for the claimed window. | `log show --predicate 'subsystem == "com.droidbridge.macos"' --last 12h` → header only, zero entries. `P03Harness` and `SecureListener` log identity generation, listener start and every handshake to that subsystem (`macos/Core/MacIdentity.swift`, `P03Harness.swift`, `SecureListener.swift`), and the file also writes to stderr. The claim was made inside that 12-hour window. |
| A6 | No SAC could have been compared on the Mac. | `P03Harness` prints identity, expected pin, `READY peer-pin=`, `DATA`, `ECHO`, `CLOSED`; it never prints a short authentication code. `SecureListener` has no SAC code path. So question 9 below is **no**, independently of the handshake. |
| A7 | The claimed blockers are stale, and no captured command exists for any of it. | On 2026-11-09 `ACCESS_LOCAL_NETWORK: granted=true` for `com.droidbridge.experiment.session`, and the activity process was running (pid 14846). Neither the earlier record nor any commit contains the exact commands, logcat lines or terminal output for the claimed handshake. |
| A8 | The "evidence bundle signature" is unverifiable. | It is a bare SHA-256 with no bundle, no signing command and no verification output in the repo. A hash is not evidence of what was hashed. |

### What most plausibly happened

One of: (a) the P02 plaintext echo at 47999 was observed and misattributed to P03; (b) a manual
TLS probe outside both applications (e.g. a standalone socket/`openssl` client) was treated as the
Android app; or (c) an earlier listener run before the 12-hour log window. Nothing in the repository
distinguishes these, so the claim is unverified rather than merely "stale".

## 3. The twelve questions, answered from evidence

| # | Question | Answer | Basis |
|---|---|---|---|
| 1 | Was the Mac listener started? | **No evidence.** | A5 (zero log entries); A8 (no command). |
| 2 | Was the Android session experiment launched? | Launched; not demonstrated as connecting. | App installed and a process exists, but the only `P03Session` logcat line is `ACCESS_LOCAL_NETWORK granted=true`; no `connecting`, `handshake ok` or `connect FAILED` line exists. |
| 3 | Was the Android picker displayed? | Not for this claim. | The record itself says the permission was not granted, which makes `discover()` refuse before `discoverServices`. |
| 4 | Was the Mac discovered and resolved? | Not demonstrated. | No `found:` / `resolved:` logcat line in the buffer; the recorded address is P02's. |
| 5 | Did the phone connect to the Mac's actual listener? | **No evidence.** | A1, A2, A3. |
| 6 | Did TLS 1.3 complete? | **No evidence.** | A4 (sample is not a TLS record); no negotiated-protocol line from either implementation. |
| 7 | Did both sides authenticate the expected peer? | **No.** | The Android session requires an expected pin for confirmation; the Mac harness had no SAC output. First-pairing mode accepts any certificate by design, so a handshake alone would not establish authentication. |
| 8 | Was the certificate pin checked during the handshake? | Not shown. | Code places the check inside the trust decision (Android `PinnedTrustManager`, macOS `sec_protocol_options_set_verify_block`), but no run output demonstrates either path. |
| 9 | Was the SAC compared by a human? | **No.** | A6 — the Mac displayed no code. |
| 10 | Was application data exchanged in both directions? | **No evidence.** | No frame/log capture with both directions; A4. |
| 11 | Was the data received by the actual Android and macOS implementations? | **No.** | A4/A5; the macOS receive loop only performs one `receive` call per connection (`SecureListener.startReceive`), so it could not have sustained a bidirectional exchange anyway. |
| 12 | Which commands/logs/files prove each step? | None recorded. | This is the core defect of the earlier record. |

## 4. Classification of the earlier work

- **Standalone harness handshake:** none recorded, and no harness command appears in the history.
- **Local loopback test:** none recorded.
- **Physical-device network test:** P02's plaintext echo is real and verified; it is not P03.
- **Android-app-to-macOS-app session:** **not demonstrated.**

## 5. Consequences for the code (independent of the claim)

Reading the implementations found defects that would have prevented the claimed run from working:

1. Android `SecureSession.receive()`/`receiveOrNull()` build a **new** `FrameReader` per call, so a
   second frame arriving in the same read is discarded and a partial frame is lost.
2. macOS `SecureListener.startReceive` calls `connection.receive` exactly once and never recurses,
   so at most one chunk of peer data is ever delivered.
3. Neither side has a confirmation action; `isConfirmed` is only `expectedPin != null`.
4. The macOS side never derives or displays a SAC.
5. Pins are in memory only on both sides, so a restart cannot be a "known peer".
6. No application-level replay guard, idle timeout or rate limiting exists.

These are tracked and fixed under the P03 gate table in
`Docs/prototypes/P03-transport-and-pairing.md`.

## 6. Required evidence format for every future run

Record, per run, without secrets:

- date/time and the exact commit under test;
- Android model, OS/API, build fingerprint;
- Mac model and macOS version;
- network (SSID type, IPv4 of each side, port);
- USB connected yes/no, USB debugging yes/no, wireless debugging yes/no;
- exact commands, with their working directory;
- Android `logcat -s P03Session:*` lines;
- macOS harness stderr lines (`P03: …`);
- negotiated TLS protocol;
- both pins (public-key digests — safe to record);
- the SAC shown on each side;
- data sent and received, both directions, with byte counts;
- outcome, including refusals and failures;
- evidence file location.

Never record private keys, keystore/keychain contents, pairing codes from other systems, or clipboard
content.

## 7. Runs

- 2026-11-09 — the earlier claimed run: **withdrawn**, see §1–§3.
- Any new run is appended here by the session that performs it.
