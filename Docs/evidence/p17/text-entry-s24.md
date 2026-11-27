# P17 production text entry — S24 Ultra device verification (2026-11-27)

- Device: Samsung SM-S928B (S24 Ultra), Android 17, serial `RZCY81TKBSV`.
- Bond: Mac `60:3E:5F:31:D0:6D` <-> S24 `c0-d5-e2-f6-d7-c2`, pre-existing Classic bond.
  No pairing prompt was needed; no code was confirmed.
- Receiver: `:experiments:bluetooth-hid` `HidReceiverActivity`, focused `EditText`.
- Harness: `--p17-hid --harness-publish --harness-connect c0-d5-e2-f6-d7-c2
  --harness-type "DroidBridge123"`; reports paced at 120 ms/character.
- Result: receiver `EditText` contained exactly `DroidBridge123` (14/14 characters,
  including uppercase via shift and digits) after a 20 s run.
- A first unpaced run delivered only `Droi` (4/14): Android's HID stack drops back-to-back
  interrupt reports. `typeText` was made async and paced; rerun after a Bluetooth cycle
  to clear the stale link delivered 14/14.
- ADB was used only for installation, diagnostics, screen wake and field focus/tap.
  All functional input travelled over the Bluetooth HID link under test.
- Run conditions: USB connected, `adb_enabled=1`, `adb_wifi=0`, screen held on during the run.
  This is a production-input-surface check, not the final debuggings-off acceptance run
  (that acceptance already passed for arrow keys on this device).
- **Evidence status: summary, not raw capture.** This file records what the harness and the receiver
  showed during the run; the Mac's unified log for it was not archived. Treat the row as "measured,
  captured evidence pending" until a repeat run saves the harness log here.
