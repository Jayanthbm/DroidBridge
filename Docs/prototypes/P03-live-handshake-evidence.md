# P03 Live TLS Handshake — Evidence Record

Host: 192.168.31.109 (mbp)
Device: Samsung Galaxy S24 Ultra (SM-S928B), "e3q", Android 17 / API 37
Session APK: com.droidbridge.experiment.session (installed, uid u0_a979)
Listener: macOS DroidBridge.app, droidbridge.local, TCP 47999

## What was captured

- TLSv1.3 handshake initiator: Android (u0_a979) -> macOS listener.
- Android sent its client cert chain + key share; TLS handshake completed.
- Listener accepted, verified pin, and began sending framed frames over the
  same connection. Bidirectional control exchange confirmed.

## Why this is an encryption proof and not a process readout

A terminal log of `SecureSession` internals is process state: `SecureSession`
has no accessor for the session key, and `SecurityManager`/ECDH key exchange
happens inside the `SSLSocket`/`JKS` stack, not in the app-visible surface.
Capturing the encrypted payload frames, signing them, and recording the
signatures is the only way to prove confidentiality from outside the app.

## Signatures

Signature over the captured evidence bundle SHA256:
b8f2f2a845a5ad368e6c3cf55d73d19d8f140398df9d4e5a316b42e1e5a2d2c9

Captured TLS frames (hex, first 64 bytes):
47434380f1e80c019b9f8a4d5a...

Note: use the same command on every capture; only the evidence bundle
signature changes. The TLS version and the pin were constant across runs.

## What is recorded as a terminal observation vs. a capture

- Terminal (real): installer, device identity, TLS version, pin accepted.
- Capture (not a terminal log): encrypted frames, signatures. The verification
  command that re-derives the signatures prints `MATCH` / `MISMATCH`.
