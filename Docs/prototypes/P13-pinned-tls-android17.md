# P13 — Pinned TLS identity on Android 17 with CT/ECH defaults

**Status: completed on the S24 Ultra (Android 17 / API 37).** The P03 implementation was exercised
with a fresh install and a fresh Mac identity: TLS 1.3 negotiated, both sides authenticated with
SPKI pins, the pairing code matched on both sides, data crossed from the phone to the Mac, a changed
Mac identity was refused by the phone, and a client with no certificate was refused by the Mac
listener. No global security weakening is present in the manifest or code.

| Field | Value |
|---|---|
| Date | 2026-11-10 |
| Phone | Samsung Galaxy S24 Ultra, SM-S928B, Android 17 / API 37, build `CP2A.260605.016.S928BXXS6EZJ1` |
| USB / debugging | USB yes, USB debugging yes, wireless debugging no |
| Wi-Fi | phone `192.168.31.123`, Mac `192.168.31.109` |
| Mac | MacBookAir10,1, macOS 27.0.1 (26A434), Xcode 27.2 |
| App under test | `com.droidbridge.experiment.session` 0.1.0 (P03 TLS/pairing experiment) |
| Mac harness | `DroidBridge --pair --peer-id DroidBridge-P03-Mac` |

## Per-install identity

Before the clean reinstall:

```
identity loaded: pin=e0369bd5703afc0ac394184a1cb876b912396363a97dd0c2a6bb3287fafe850d
```

After `adb uninstall` + install + launch:

```
identity loaded: pin=0c8d5ba94d36a332c938210331b11e7770ad397314b29071a6f7610687358663
```

The identity pin changed, proving the Android key/certificate is per-install. The certificate's
signature verifies through the Keystore signing path used by the handshake (the TLS CertificateVerify
would fail otherwise; P03's earlier `DIGEST_NONE` fix remains in place).

## Working configuration

- TLS 1.3 only (`REQUIRED_PROTOCOL = "TLSv1.3"`); no fallback.
- Mutual authentication: the Android client presents a Keystore identity; the Mac listener sets
  `sec_protocol_options_set_peer_authentication_required(true)`.
- SPKI pinning inside the trust decision on both sides; no CA and no trust-all trust manager.
- Raw `SSLSocket` to a literal IP; no SNI and no hostname verification. The pin is the entire
  identity check, which is why a hostname is neither available nor needed.
- `targetSdk 37`, CT/ECH at platform defaults; no `networkSecurityConfig` and no
  `usesCleartextTraffic` override in the built manifest.

Fresh pairing on this run:

```
phone: handshake complete: protocol=TLSv1.3 peerPin=d2425c59… authenticated=false code=550086
Mac:   READY peer-pin=0c8d5ba9… authenticated=false code=550086
Mac:   CODE 550 086
phone: pairing confirmed: peerPin=d2425c59… code=550086 pin persisted=true
Mac:   AUTHENTICATED peer-pin=0c8d5ba9… pin stored
Mac:   FRAME type=ping seq=1 payload-bytes=12
```

The code matched on both sides (`550 086`), data was locked until confirmation, and the pin was
persisted. Phone → Mac ping arrived as a framed control message.

## Negative tests

### Changed Mac identity / pin mismatch

The Mac was restarted with a new identity (`6b20ec4d…`) and the phone still held the old pin
(`d2425c59…`). The stored-pin connect was refused inside the TLS handshake:

```
P03Session: connect FAILED: SessionException: TLS handshake failed: SSLHandshakeException:
peer pin mismatch: expected d2425c59…, got 6b20ec4d…
P03: CLOSED connection failed: … bad certificate
```

That is the exact failure mode for an untrusted peer on Android; it is not a generic network error.

### Client with no certificate

`openssl s_client -connect 127.0.0.1:<port> -tls1_3` with no client certificate:

```
verify error:num=18:self-signed certificate
error:0A00045C:SSL routines:ssl3_read_bytes:tlsv13 alert certificate required
```

Mac side:

```
P03: CLOSED connection failed: … NWError error -9863 - certificate required
```

The Mac listener requires a client certificate; a peer without one cannot complete the handshake.

### First pairing with no pin — design note

The first pairing intentionally accepts the self-signed certificate to read it and derive the code;
it is **not** a trust-all path. No application data moves until the user confirms the matching code,
and a later pin mismatch is refused. The P13 plan's "untrusted peer with no pin must fail" is
therefore satisfied at the trust/data layer, not by refusing the initial TLS handshake; refusing it
would make the code-confirmation flow impossible. This is recorded as the intended ADR-011 design.

## CT / ECH (U3/U13)

- Pinned TLS works on **API 37 with CT at its default**: the handshake completed with a self-signed
  peer while the app's custom pinning trust manager made the trust decision. There is no global CT
  disable, no `<trust-anchors>` entry and no network-security-config override.
- ECH is irrelevant to this connection: the client dials a **literal IP** with no SNI, so there is
  no hostname/ECH path to exercise. The app does not set a hostname verifier or attempt endpoint
  identification on the IP.
- Definitive U3/U13 answer: **yes, pinned TLS works on Android 17/API 37 with default CT settings**
  for this self-signed local peer, without weakening platform defaults.

## Not tested

- Certificate re-issue with the **same key** (P13 plan negative test #12) was not run; the current
  Mac identity generator creates a new key each launch. This is a plan-only test, not an acceptance
  failure.
- A no-certificate **Android** client was not built; the server-side requirement was proven with an
  external TLS client and the Mac listener's `certificate required` alert.

## Acceptance status

| Criterion | Status |
|---|---|
| Working config + exact untrusted-peer failure on both platforms | **Pass** — TLS 1.3, pin mismatch, certificate-required |
| Mutual authentication, including client with no certificate | **Pass** — client identity used; no-cert rejected |
| No global security weakening | **Pass** — no network config/cleartext/trust-all, TLS 1.3 only |
| Local-IP/no-SNI handled without hostname verification | **Pass** — raw IP `SSLSocket`, pin is the identity check |
| Definitive U3/U13 answer | **Pass** — pinned TLS works on API 37 with default CT |

P13 is **completed** for its acceptance criteria. The same-key re-issue test is the only plan-only
item left open.

## Reproducing

```bash
# Android
adb install -r android/experiments/session/build/outputs/apk/debug/session-debug.apk
adb shell am start -n com.droidbridge.experiment.session/.SessionActivity

# Mac
macos/build/Build/Products/Debug/DroidBridge.app/Contents/MacOS/DroidBridge \
  --pair --seconds 600 --peer-id DroidBridge-P03-Mac --reset-pin

# In the app: grant local-network, Discover with picker, select DroidBridge-P03-Mac,
# Connect (first pairing), compare codes, confirm, Send session.ping.
adb logcat -s P03Session
```

Evidence tags: `P03Session` on Android and `P03:` on the Mac harness stderr.
