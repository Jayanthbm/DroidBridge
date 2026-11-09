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
- 2026-11-09 — loopback client and Mac harness: **passed**, see Run L1.

### Run L1 — loopback TLS client against the Mac harness

**Classification: loopback test.** One side is the real macOS implementation; the other is a
throwaway Python client, not the Android app. No phone, no Wi-Fi, no Bonjour. This proves the Mac
half of the protocol and the Keychain persistence path; it proves nothing about Android, the
Android Keystore, or a physical network.

| Field | Value |
|---|---|
| Date/time | 2026-11-09 15:2x–16:0x IST |
| Commit under test | `5d1b289` (working tree before the doc commit) |
| Mac | MacBookAir10,1 (M1), macOS 27.0.1 (26A434), Xcode 27.2 |
| Network | loopback `127.0.0.1`, ephemeral listener ports 61118–61126 |
| Phone | none; USB not connected, USB debugging not used, wireless debugging not used |
| Peer software | Homebrew Python 3.15, OpenSSL 4.1.0, self-signed P-256 client certificate |
| Negotiated protocol | TLSv1.3, `TLS_AES_256_GCM_SHA384` (reported by the client each phase) |
| Peer pins | `88ca91378218f18a886d54da3c91ffd25a6df1b5ffbe4942451156668d2fdcba` (expected peer), `0cdefc8b485b8b458c9b75d10d79fcac4a8b743115b79bc14032d6d4ad7a5a5a` (impostor) |
| Mac pin | changes every run: the harness generates an ephemeral identity (ADR-025 defers storage) |
| Commands | `DroidBridge --pair --seconds N --peer-id <id> --pin-service <name> [--reset-pin] [--idle-timeout S]`, scripted stdin, and the loopback client |
| Reproduce | `bash .p03-smoke/run.sh` on the Mac. The driver and P-256 client certificates were local to the run and are deliberately not committed; the script generates them with `openssl ecparam`/`openssl req` and drives the harness over stdin. |

**What each phase showed**

| Phase | Action | Observed |
|---|---|---|
| 1 | connect, send a ping before any confirmation | `connection ready … authenticated=false code=769 304`, then `closed: framing/protocol violation: peer sent data before the pairing was confirmed` |
| 2 | connect, `confirm`, then ping and a duplicate sequence 1 | `pairing confirmed locally; pin stored for loopback-client`, `AUTHENTICATED … pin stored`, `frame ping:1:hello`, then `closed: … replayed control sequence 1 (highest accepted 1)` |
| 3 | reconnect with the stored pin | `verify: pin matched`, `connection ready … authenticated=true`, ping accepted without any confirmation |
| 4 | first pairing, `reject` | `closed: pairing rejected by user`; no pin stored |
| 4b | reconnect after 4 | `verify: first pairing`, `authenticated=false` — the rejection stored nothing |
| 5 | connect with a different client identity while a pin is stored | `verify: PIN MISMATCH expected 88ca… got 0cde…`, handshake failed, client saw `TLS_ALERT_CERTIFICATE_UNKNOWN`; the stored pin was not replaced |
| 6 | confirm, then send a zero-length frame header | `closed: framing/protocol violation: zero-length frame: a frame must carry a payload` |
| 7 | confirm with `--idle-timeout 3`, send one ping, stay silent | `frame ping:1:idle`, then `closed: idle timeout after 3s` |

**What this run does not establish**

1. Android has not been on the other end of any of these phases. Its pairing UI, Keystore-backed
   pin store and replay/idle handling are compile-checked only.
2. The pairing code was compared by a script, not by a human reading two screens. Phase 2's code is
   derived from two SPKIs and matched the derivation both sides run, but the human-comparison step
   itself is unexercised.
3. The Mac identity is ephemeral. Every harness launch presents a new key, so an Android device that
   pinned the previous run would correctly refuse the next one as a changed identity. Cross-launch
   known-peer sessions therefore need ADR-025's identity storage before they can be tested.
4. Keychain access from the ad-hoc build did not prompt and both write and read succeeded. That is
   one machine, one signature; a re-signed build may behave differently and must be re-checked.
5. The idle deadline was observed with `--idle-timeout 3`, not the 30 s production window; the
   timing mechanism is proven, the default value is not.
6. Oversize-length rejection (2 GiB declared) remains unit-test only (`SessionCodecTests`); phase 6
   covers the zero-length case on a live session. The pairing timeout also expired unobserved — no
   phase sat in `awaitingConfirmation` long enough.

### Run P1 — Samsung S24 Ultra against the Mac app over Wi-Fi

**Classification: physical-device network test.** Both real implementations, real Wi-Fi, real
Keystores. Two pairing runs were needed: the first exposed a defect that had to be fixed before the
flow could be accepted, so the run is recorded in two parts rather than presented as one clean pass.

| Field | Value |
|---|---|
| Date/time | 2026-11-09 16:28–17:12 IST |
| Code under test | `13a6005` plus the working-tree fixes named below (both committed after the run) |
| Phone | Samsung SM-S928B (e3q), Android 17 / API 37, build `CP2A.260605.016`, fingerprint `samsung/e3qxins/e3q:17/CP2A.260605.016/S928BXXS6EZJ1:user/release-keys` |
| Mac | MacBookAir10,1 (M1), macOS 27.0.1 (26A434), Xcode 27.2 |
| Network | Wi-Fi `192.168.31.0/24`: phone `192.168.31.246`, Mac `192.168.31.109`; listener ports 61135–61139; Bonjour service `DroidBridge-P03-Mac` in `_droidbridge-dev._tcp` |
| USB connected | **yes** (`adb devices -l` → `usb:1048576X`, `sys.usb.state=mtp,adb`) |
| USB debugging | **yes** |
| Wireless debugging | **enabled** (`settings get global adb_wifi_enabled` = 1, `service.adb.tcp.port` = 5555). All driving was over the USB transport. |
| Phone identity pin | `e0369bd5703afc0ac394184a1cb876b912396363a97dd0c2a6bb3287fafe850d` |
| Mac identity pins | run A `45c77b066a78f7c939ac6d7bb50d597fff14277517a3b277bc5a46e915cfd48c`; run B `95c71d5ededd1924a2ef6bbaa226b05ebbc977531cac3c97e74ce66327bb326e`; run C (rejected) `ce3724ac8ec73a2721093b1f3863f4f299fac59bd01cd43ea0fc84a17cdfb7c5` |
| Mac command | `DroidBridge --pair --seconds 1800 --peer-id DroidBridge-P03-Mac [--reset-pin]` |
| Phone commands | `adb shell am start -n com.droidbridge.experiment.session/.SessionActivity`, then label-addressed `input tap` driven from `uiautomator dump` |
| Evidence | this file; harness stderr and `adb logcat -s P03Session:*` quoted inline |

#### Part 1 — the first attempt failed, and why

The phone resolved `192.168.31.109:61135` from the picker and dialled, then failed:

```
P03Session: connecting to 192.168.31.109:61135 expectedPin=(none, first pairing)
P03Session: connect FAILED: SessionException: TLS handshake failed:
  SSLHandshakeException: Read error: ssl=0x…: I/O error during system call, Success
```

The Mac never reached its verify block (`verify:` absent from stderr), so the certificate never
arrived. The phone's platform log gave the cause:

```
W/System.err: java.security.InvalidKeyException: Keystore operation failed
  at android.security.keystore2.AndroidKeyStoreSignatureSpiBase.engineInitSign
  at com.android.org.conscrypt.CryptoUpcalls.signDigestWithPrivateKey(CryptoUpcalls.java:82)
  at com.android.org.conscrypt.CryptoUpcalls.ecSignDigestWithPrivateKey(CryptoUpcalls.java:70)
```

The identity key had been generated with `setDigests(DIGEST_SHA256)` only. Conscrypt signs the TLS
1.3 CertificateVerify through `NONEwithECDSA` over a pre-computed digest, and Keystore refuses that
when `DIGEST_NONE` is not permitted. The key could not sign, so no client certificate was sent.
`DeviceIdentity` now permits `DIGEST_SHA256` **and** `DIGEST_NONE`, and the app was uninstalled and
reinstalled so a compliant identity was generated. This defect was invisible to `assembleDebug` and
to every unit test; only a real handshake exposed it.

#### Part 2 — pairing, confirmation and data (pre-fix Mac, post-fix phone)

With the signing fixed, the handshake completed and both sides derived the same code:

| Side | Rendered/printed code | Peer pin observed |
|---|---|---|
| Phone | `614 068` (later pairing) / `642 575` (first) | Mac pin |
| Mac | `CODE 642 575` then `CODE 614 068` | phone pin `e0369bd5…` |

The first successful pairing showed `642 575` on both. Both ends reported `authenticated=false` and
`application data: locked (confirm the code)` until confirmed. Confirmation then produced:

```
P03Session: pairing confirmed: peerPin=45c77b06… code=642575 pin persisted=true
P03: pairing confirmed locally; pin stored for DroidBridge-P03-Mac
P03: AUTHENTICATED peer-pin=e0369bd5… pin stored
```

A reconnect with the stored pin on both sides authenticated inside the handshake:

```
P03Session: handshake complete: protocol=TLSv1.3 peerPin=45c77b06… authenticated=true
P03: verify: pin matched e0369bd5…
```

Data crossed in both directions on the confirmed session:

- phone → Mac: `P03Session: sent ping:1:session.ping` → `P03: frame ping:1:session.ping`,
  `FRAME type=ping seq=1 payload-bytes=12`
- Mac → phone: `P03: COMMAND ping sent` → `P03Session: received ping:1:hello-from-mac`

The 30 s idle deadline closed sessions twice during the run (`P03: closed: idle timeout after 30s`),
observed rather than inferred.

#### Part 3 — a security gap found by this run, and its fix

The first device run also exposed a hole in the Mac listener. The verify block captured the expected
pin **once at listener start**. A listener that began with no pin therefore kept accepting "first
pairings" after a confirmation, and `connectionReady` then granted `authenticated` merely because a
pin now existed. A different key presenting itself after the pairing would have been accepted with
full data access.

The verify block now reads the pin at handshake time, and `connectionReady` independently refuses any
connection whose pin differs from the stored one. Regression evidence, two loopback identities
against one listener that confirms the first:

```
clientA pairs: verify: first pairing … 852 863 → confirm → AUTHENTICATED
clientB (different key): verify: PIN MISMATCH expected 88ca91… got 0cdefc8b…
clientB tls alert certificate unknown; connection refused
```

On the device, the same class of check was exercised from the other side: the Mac was restarted with a
new (ephemeral) identity and the phone's stored-pin connect was refused:

```
P03Session: connect FAILED: SessionException: TLS handshake failed: SSLHandshakeException:
  peer pin mismatch: expected 95c71d5e…, got ce3724ac…
```

#### What this run does not establish

1. **No Wi-Fi drop/reconnect test.** Reconnection was exercised by closing the session and dialling
   again, not by dropping the network mid-session. The acceptance criterion about surviving a Wi-Fi
   drop is still unproven.
2. **No on-device tamper or oversize input.** The phone UI has no way to corrupt a frame; those paths
   are covered by loopback (`zero-length`) and unit tests (`oversize`, replay window). Replay was
   proven on the Mac by loopback, not on the phone.
3. **Code comparison was done by reading both UIs and logs, not by a person looking at two screens.**
   The values matched exactly (`642 575`, `614 068`), but the human-factors step is simulated.
4. **The Mac identity is ephemeral** (ADR-025). Every Mac launch produces a new pin, so the phone
   must re-pair after a Mac restart, and the run above shows the phone correctly refusing the changed
   identity instead of silently re-pinning. Persistent Mac identity is a prerequisite for the
   "reconnect without re-pairing across launches" claim.
5. **A confirmed session can still be rejected from the UI.** A mis-aimed tap on "Codes don't
   match — reject pairing" after confirmation closed the socket but left the pin stored. It is not a
   security hole (the pin had already been confirmed), but the button should be unavailable or should
   delete the pin once authenticated.
6. USB was connected and both debugging transports were enabled throughout. This is a development
   run; the final acceptance run must repeat it with USB disconnected and both debugging modes off.
