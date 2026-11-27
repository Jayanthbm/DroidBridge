# DroidBridge — Engineering Backlog

Items deferred or blocked during phased development are recorded here with context, root cause, and unblock criteria.

| ID | Phase | Area | Description | Reason / Blocker | Status |
|---|---|---|---|---|---|
| B-001 | 1 → 2 | Pairing / trust | Pairing code and Bluetooth HID bond are mocked; no TLS handshake or trust store yet. | Phase 1 is the clickable UI shell. Phase 2 adds Bonjour discovery and pinned TLS 1.3. | Deferred |
| B-002 | 1 → 3 | Android capture | `Start Screen Mirroring` toggles a preview state; no MediaProjection, encoder, or stream transport. | Phase 1 mock state. Phase 3 wires capture and H.264 over the paired session. | Deferred |
| B-003 | 1 → 4 | Input routing | Mirror toolbar and Back/Home/Recents are UI controls; Bluetooth Classic HID input is not connected yet. | Phase 1 mock state. Phase 4 implements the HIDP L2CAP path and focus routing. | Deferred |
| B-004 | 1 → 5 | Clipboard | `Clipboard Sync` and `Sync Clipboard Now` update local UI state only. | Phase 1 mock state. Phase 5 adds foreground-tap fallback and real text sync. | Deferred |
| B-005 | 1 | Android permissions | Onboarding checklist toggles permissions locally; runtime permission prompts are not requested. | Real prompts belong with the connection layer; no blocker for the UI shell. | Deferred |

## Unblocked work

No blocked items at the close of Phase 1. Phase 2 starts with Wi-Fi discovery and pinned TLS pairing.
