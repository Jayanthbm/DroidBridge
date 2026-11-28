# DroidBridge — Engineering Backlog

Items deferred or blocked during phased development are recorded here with context, root cause, and unblock criteria.

| ID | Phase | Area | Description | Reason / Blocker | Status |
|---|---|---|---|---|---|
| B-001 | 1 → 2 | Pairing / trust | Pairing code and Bluetooth HID bond are mocked; no TLS handshake or trust store yet. | Closed in Phase 2: Bonjour discovery, TLS 1.3 mutual auth, 6-digit SAC, Mac Keychain / Android Keystore trust, and automatic reconnect are implemented. Bluetooth HID remains Phase 4 work. | Closed |
| B-002 | 1 → 3 | Android capture | `Start Screen Mirroring` toggles a preview state; no MediaProjection, encoder, or stream transport. | Closed in Phase 3: MediaProjection + VirtualDisplay + MediaCodec H.264 over the pinned TLS session, with rotation-aware encoder rebuilds and a foreground service notification. | Closed |
| B-003 | 1 → 4 | Input routing | Mirror toolbar and Back/Home/Recents are UI controls; Bluetooth Classic HID input is not connected yet. | Closed in Phase 4: Classic HIDP over L2CAP PSMs `0x0011`/`0x0013`, SDP keyboard + relative mouse + consumer reports, focused-window routing, and paced report writes are implemented and verified with USB unplugged and ADB off. | Closed |
| B-004 | 1 → 5 | Clipboard | `Clipboard Sync` and `Sync Clipboard Now` update local UI state only. | Phase 1 mock state. Phase 5 adds foreground-tap fallback and real text sync. | Deferred |
| B-005 | 1 | Android permissions | Onboarding checklist toggles permissions locally; runtime permission prompts are not requested. | Phase 3 requests the real MediaProjection consent and notification permission when mirroring starts; onboarding checklist remains local-only UI. | Deferred |
| B-006 | 2 | Multi-device validation | Only one physical Android phone is available for Phase 2 acceptance. A second TLS test client was paired concurrently to verify the macOS multi-session manager and 2-phone UI, but a second handset is still needed for full hardware coverage. | Hardware availability. The server accepts 2+ concurrent sessions and the UI renders both peers; repeat with a second phone when available. | Deferred |
| B-007 | 3 | Audio | Phase 3 screen mirroring is video-only. The macOS Audio toggle and Android `RECORD_AUDIO` permission exist, but no playback capture is implemented. | The phase marks audio optional. Implement `AudioPlaybackCapture` + AAC/Opus framing and macOS playback before enabling the toggle. | Deferred |
| B-008 | 3 | Loss recovery testing | Keyframe requests are wired in both directions and decode failures trigger a throttled request, but packet-loss injection on a real Wi-Fi link was not exercised. | Needs a repeatable lossy-network harness; normal live streaming and both rotation directions passed. | Deferred |
| B-009 | 3 | Phone-initiated mirror UX | Tapping Start Screen Mirroring on Android starts capture and streams immediately, but the macOS mirror window currently opens from the Mac Start Mirroring button. | Auto-opening a mirror window on the first video frame needs a single-shot open request to avoid duplicate windows; deferred as UX polish. | Deferred |
| B-010 | 4 | Bluetooth class of device | The HID SDP record publishes correctly, but macOS 26 rejects the app-level `setClassOfDevice:forTimeInterval:` override; the Mac stays classified as a computer (CoD `0x000000`) while discoverable. | Private IOBluetooth class-of-device calls return success but do not reach bluetoothd/HCI on this BCM_4378 host; the same limitation is documented by the btkb reference project. Android still discovers the Mac and can open the HID service, but its Bluetooth list shows a laptop icon. Keep the request as best-effort and record the live CoD in diagnostics. | Open |
| B-011 | 4 | Recents semantics | Android has no standard HID consumer-control usage for the Overview/Recents screen. The Recents button sends `Alt+Tab`, which switches directly to the previous app instead of opening the card overview. | Standard consumer `0x0221` is AC Search and opens Google Assistant. `Meta+Tab` and `Ctrl+Tab` do not reach the Android app switcher over HID; `Alt+Tab` is the only keyboard chord that switches apps. A true overview needs Accessibility or ADB input, both prohibited. Keep `Alt+Tab` as the compliant approximation and label it in the UI/backlog. | Open |
| B-012 | 4 | macOS HID channel availability | After a `bluetoothd` restart, only the first freshly launched DroidBridge process receives incoming L2CAP channel callbacks. Later launches register the SDP record and PSMs but never get `handleL2CAPChannelOpened:`. | Private CoreBluetooth classic routing in `bluetoothd` retains a stale session owner; the workaround is `sudo killall bluetoothd`, relaunch the Mac app, click Bluetooth Setup, then reconnect from the phone. A production fix needs a supported HID API or a helper that survives app restarts; documented for local personal use. | Open |

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

## Phase 4 verification notes

- Mac ↔ OnePlus Nord (`AC2001`, Android 12, API 31) completed a Classic Bluetooth HID bond while USB was unplugged and Android USB debugging was off. Both L2CAP channels opened: control PSM `0x0011`, interrupt PSM `0x0013`.
- The phone lists **Jayanths MacBook Air → Connected** and receives a combined keyboard + relative mouse + consumer-control HID descriptor from the SDP record.
- Keyboard text was typed through the focused mirror window, including a full fast `Hello world` in one keystroke after report pacing was added. Arrow keys, Backspace, Enter, and modifier combinations were exercised.
- Fast bursts originally dropped reports; HID interrupt writes are now serialized per address and paced at a 15 ms minimum, with a FIFO queue and write-completion rescheduling. A zero mouse report heartbeat every 4 s keeps the ACL link alive past the observed ~55 s idle disconnect; a >170 s idle test stayed connected.
- Relative pointer movement, hover, left click, and middle/right mappings were verified in the mirror. Left click opened Android Settings rows and dialogs; Back closed them; Home returned to the launcher. Pointer speed uses the displayed video surface width rather than the whole window, so the pointer reaches all screen corners.
- Back (`0x0224`) and Home (`0x0223`) work. Recents sends `Alt+Tab`, which switches to the previous app rather than opening the card overview; this is the compliant HID approximation and is tracked as B-011.
- No Accessibility Service, runtime ADB input, clipboard injection, or screen capture bypass is used. ADB was used only for builds, installs, launches, and diagnostics while USB debugging was temporarily enabled; the acceptance run itself had USB unplugged and ADB off.
- The Mac HID shim only receives channels in the first app session after `bluetoothd` starts; the local workaround and remaining limitation are tracked as B-012.
- `setClassOfDevice:` still does not change the live CoD, so Android shows a laptop icon while the HID service works; tracked as B-010.
- The phone was set to a 30-minute screen timeout for the click tests. Without that, Android sleeps after ~60 s and the stream shows `Stream paused`; HID input wakes it but the first report may be consumed. This is a device power setting, not a protocol failure.
