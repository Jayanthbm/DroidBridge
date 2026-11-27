# DroidBridge — Engineering Backlog

Items deferred or blocked during phased development are recorded here with context, root cause, and unblock criteria.

| ID | Phase | Area | Description | Reason / Blocker | Status |
|---|---|---|---|---|---|
| B-001 | 1 → 2 | Pairing / trust | Pairing code and Bluetooth HID bond are mocked; no TLS handshake or trust store yet. | Closed in Phase 2: Bonjour discovery, TLS 1.3 mutual auth, 6-digit SAC, Mac Keychain / Android Keystore trust, and automatic reconnect are implemented. Bluetooth HID remains Phase 4 work. | Closed |
| B-002 | 1 → 3 | Android capture | `Start Screen Mirroring` toggles a preview state; no MediaProjection, encoder, or stream transport. | Closed in Phase 3: MediaProjection + VirtualDisplay + MediaCodec H.264 over the pinned TLS session, with rotation-aware encoder rebuilds and a foreground service notification. | Closed |
| B-003 | 1 → 4 | Input routing | Mirror toolbar and Back/Home/Recents are UI controls; Bluetooth Classic HID input is not connected yet. | Phase 1 mock state. Phase 4 implements the HIDP L2CAP path and focus routing. | Deferred |
| B-004 | 1 → 5 | Clipboard | `Clipboard Sync` and `Sync Clipboard Now` update local UI state only. | Phase 1 mock state. Phase 5 adds foreground-tap fallback and real text sync. | Deferred |
| B-005 | 1 | Android permissions | Onboarding checklist toggles permissions locally; runtime permission prompts are not requested. | Phase 3 requests the real MediaProjection consent and notification permission when mirroring starts; onboarding checklist remains local-only UI. | Deferred |
| B-006 | 2 | Multi-device validation | Only one physical Android phone is available for Phase 2 acceptance. A second TLS test client was paired concurrently to verify the macOS multi-session manager and 2-phone UI, but a second handset is still needed for full hardware coverage. | Hardware availability. The server accepts 2+ concurrent sessions and the UI renders both peers; repeat with a second phone when available. | Deferred |
| B-007 | 3 | Audio | Phase 3 screen mirroring is video-only. The macOS Audio toggle and Android `RECORD_AUDIO` permission exist, but no playback capture is implemented. | The phase marks audio optional. Implement `AudioPlaybackCapture` + AAC/Opus framing and macOS playback before enabling the toggle. | Deferred |
| B-008 | 3 | Loss recovery testing | Keyframe requests are wired in both directions and decode failures trigger a throttled request, but packet-loss injection on a real Wi-Fi link was not exercised. | Needs a repeatable lossy-network harness; normal live streaming and both rotation directions passed. | Deferred |
| B-009 | 3 | Phone-initiated mirror UX | Tapping Start Screen Mirroring on Android starts capture and streams immediately, but the macOS mirror window currently opens from the Mac Start Mirroring button. | Auto-opening a mirror window on the first video frame needs a single-shot open request to avoid duplicate windows; deferred as UX polish. | Deferred |

## Phase 2 verification notes

- Mac ↔ OnePlus Nord (`AC2001`, Android 12, API 31) paired over Bonjour with matching SAC `875297`; TLS 1.3 cipher `TLS_AES_128_GCM_SHA256`.
- Both apps force-stopped and relaunched; each side reconnected from pinned trust without re-pairing.
- Mac killed and relaunched on a new ephemeral port; Android re-discovered the new port and reconnected without a code prompt.
- A second TLS client paired while the phone was connected; macOS showed both sessions and `2 phones paired`.
- Wi-Fi disabled on the phone produced the macOS `Wi-Fi connection lost` banner and `Reconnecting` state; re-enabling Wi-Fi re-established the same pinned session.
- Mac manual disconnect now sends `disconnect` before closing, so Android shows `Your Mac is offline` and does not auto-reconnect until the user chooses Reconnect.
- Android pinned-Mac JSON is encrypted with an AES-GCM key in Android Keystore; the legacy plaintext value is migrated on the next save.

## Phase 3 verification notes

- Mac ↔ OnePlus Nord (`AC2001`, Android 12, API 31) streamed H.264 at `1080×2400`, 30 fps; macOS VideoToolbox reported a hardware `avc1` decoder with no dropped frames during the live check.
- A static Android screen legitimately produces very few encoder frames (observed ~0.2–6 fps). The macOS watchdog was widened to 8 s so the footer stays `Live H.264` rather than falsely showing `Stream paused`.
- Rotation was forced with the window-manager test harness, not touch/ADB input: `adb shell cmd window user-rotation lock 1` produced a `2400×1080` virtual display, rebuilt the encoder, and resized the Mac window to `2400×1080`; `lock 0` restored `1080×2400` and the portrait Mac window. Rotation was returned to `free` and the phone finished portrait.
- `FLAG_SECURE` was verified with a temporary debug-only hook: mirrored content turned black while status/navigation bars remained visible; clearing the flag restored the image. The hook was removed before the final build.
- Mac Stop sends `stop_mirror`; the foreground service and MediaProjection stop, notification `4201` is removed, the phone returns to `Start Screen Mirroring`, and the Mac mirror window closes.
- Android notification Stop was verified to clear `MirrorService`, MediaProjection (`null`), and notification `4201`; the app process stayed alive.
- Final Android app Stop test: `mirror_stopped` is sent before the encoder is torn down, the Mac mirror window dismisses automatically, and the pinned TLS session remains connected. The hub shows `Wi-Fi TLS`, `Idle`, and `Start Mirroring` with no reconnect or connection-reset event.
- Restarting the Mac app while mirroring left `MirrorService` running; after the new Mac process started, the same pinned session reconnected and the mirror window resumed when opened.
- A send race between the encoder thread and the stop notice previously reset the TLS socket; `BridgeLink.send` and `sendMirrorStopped` are now synchronized, and the stop notice is best-effort so it cannot tear down a healthy session.
- Audio is not implemented (optional Phase 3 scope), and packet-loss injection was not run; both are tracked as B-007 and B-008.
