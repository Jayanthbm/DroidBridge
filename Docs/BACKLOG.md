# DroidBridge — Engineering Backlog

Items deferred or blocked during phased development are recorded here with context, root cause, and unblock criteria.

| ID | Phase | Area | Description | Reason / Blocker | Status |
|---|---|---|---|---|---|
| B-001 | 1 → 2 | Pairing / trust | Pairing code and Bluetooth HID bond are mocked; no TLS handshake or trust store yet. | Closed in Phase 2: Bonjour discovery, TLS 1.3 mutual auth, 6-digit SAC, Mac Keychain / Android Keystore trust, and automatic reconnect are implemented. Bluetooth HID remains Phase 4 work. | Closed |
| B-002 | 1 → 3 | Android capture | `Start Screen Mirroring` toggles a preview state; no MediaProjection, encoder, or stream transport. | Phase 1 mock state. Phase 3 wires capture and H.264 over the paired session. | Deferred |
| B-003 | 1 → 4 | Input routing | Mirror toolbar and Back/Home/Recents are UI controls; Bluetooth Classic HID input is not connected yet. | Phase 1 mock state. Phase 4 implements the HIDP L2CAP path and focus routing. | Deferred |
| B-004 | 1 → 5 | Clipboard | `Clipboard Sync` and `Sync Clipboard Now` update local UI state only. | Phase 1 mock state. Phase 5 adds foreground-tap fallback and real text sync. | Deferred |
| B-005 | 1 | Android permissions | Onboarding checklist toggles permissions locally; runtime permission prompts are not requested. | Real prompts belong with the connection layer; no blocker for the UI shell. | Deferred |
| B-006 | 2 | Multi-device validation | Only one physical Android phone is available for Phase 2 acceptance. A second TLS test client was paired concurrently to verify the macOS multi-session manager and 2-phone UI, but a second handset is still needed for full hardware coverage. | Hardware availability. The server accepts 2+ concurrent sessions and the UI renders both peers; repeat with a second phone when available. | Deferred |

## Phase 2 verification notes

- Mac ↔ OnePlus Nord (`AC2001`, Android 12, API 31) paired over Bonjour with matching SAC `875297`; TLS 1.3 cipher `TLS_AES_128_GCM_SHA256`.
- Both apps force-stopped and relaunched; each side reconnected from pinned trust without re-pairing.
- Mac killed and relaunched on a new ephemeral port; Android re-discovered the new port and reconnected without a code prompt.
- A second TLS client paired while the phone was connected; macOS showed both sessions and `2 phones paired`.
- Wi-Fi disabled on the phone produced the macOS `Wi-Fi connection lost` banner and `Reconnecting` state; re-enabling Wi-Fi re-established the same pinned session.
- Mac manual disconnect now sends `disconnect` before closing, so Android shows `Your Mac is offline` and does not auto-reconnect until the user chooses Reconnect.
- Android pinned-Mac JSON is encrypted with an AES-GCM key in Android Keystore; the legacy plaintext value is migrated on the next save.
