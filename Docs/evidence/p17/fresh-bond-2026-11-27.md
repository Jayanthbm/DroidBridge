# P17 fresh-bond run — macOS 27 pairing, channels and input (2026-11-27)

Device: Samsung SM-S928B (S24 Ultra), Android 17, serial `RZCY81TKBSV`.
Mac: `MacBookAir10,1`, macOS 27.2.1 build `26B131`, app `com.droidbridge.app` (Debug).
Run conditions: USB connected, `adb_enabled=1`, `adb_wifi=0`.

## Before the run

The existing Classic bond was removed on both sides so the Phase 5 pairing-prompt row could be
measured on a genuinely fresh bond:

- Phone: Bluetooth settings → *Jayanth's MacBook Air* → Unpair (ADB drove the settings taps).
- Mac: `blueutil --unpair c0-d5-e2-f6-d7-c2`.

## Mac-initiated pairing — five attempts, codes matched, reply lost (macOS 27)

Codes observed on both screens: `111169`, `160297`, `821954`, `820963`, `482504`.

```
09:32:37.369 IOBluetoothDevicePair userConfirmationRequest: deviceAddr:c0-d5-e2-f6-d7-c2 numericValue:821954
09:32:37.369 [P17ClassicHIDP] numeric comparison value 821954
09:32:46.212 [P17ClassicHIDP] pairing confirmation: accept
09:32:46.212 IOBluetoothDevicePair BluetoothHCIUserConfirmationRequestReply no-op. Should be done at CB Layer
09:33:07.886 IOBluetoothDevicePair connectionComplete status:0x0000001F
09:33:07.889 [P17ClassicHIDP] Pairing failed (0x0000001F)
```

`blueutil --pair c0-d5-e2-f6-d7-c2`, answered `yes` on the Mac with the same code shown on the phone
and **Pair** tapped on the phone, failed identically:

```
Does "Jayanth's S24 Ultra" (c0-d5-e2-f6-d7-c2) display number 820963 (yes/no)? yes
Failed to pair "c0-d5-e2-f6-d7-c2" with error 0x1f (Unspecified Error)
```

The Mac could not be made discoverable (`blueutil --discoverable 1` → *"Failed to switch bluetooth
discoverable on in 10 seconds"*, reads `0` with the Bluetooth pane frontmost), and the phone's scan
listed no available devices, so the phone-initiated path used on 2026-11-20 was unavailable.

## Manual pairing completed (owner)

Codes compared by the owner on both screens; paired from the Mac app's HID panel. Afterwards:

- Phone `Bonded devices` contains `XX:XX:XX:XX:D0:6D … Jayanth's MacBook Air`.
- Mac `blueutil --paired` contains `c0-d5-e2-f6-d7-c2 … Jayanth's S24 Ultra`.

## Connect after the fresh bond — channels open

```
09:47:14.835 SDP published (handle 1330188580)
09:47:14.835 opening ACL to c0-d5-e2-f6-d7-c2
09:47:14.835 class of device -> 0x2540
09:47:18.652 control channel open
09:47:18.753 interrupt channel open
```

`dumpsys bluetooth_manager` after toggling the phone's Bluetooth off/on (`cmd bluetooth_manager
disable` / `enable`):

```
Profile: HidHostService
  mInputDevices:
 XX:XX:XX:XX:D0:6D : Selected transport=1 HID connection state=2 HOGP connection state=0
```

So Android did bind the fresh bond as a Classic HID input device (state 2 = connected).

## Input over the fresh bond — not observed

With the receiver (`com.droidbridge.experiment.bluetoothhid/.HidReceiverActivity`) foreground and its
counters at `UP: 0 DOWN: 0 / LEFT: 0 RIGHT: 0`:

```
09:47:25.079 harness sent UP
(counters after: UP: 0  DOWN: 0 / LEFT: 0  RIGHT: 0)
```

Repeated once more after a clean app quit and relaunch (channels reopened, `harness sent UP` at
09:48:04), with the same result: no counter changed. The earlier lifecycle state
(`L2CAP control channel (0x0011) failed (0xE00002BC)`) after an unclean kill is the documented
bluetoothd half-open state and was cleared by cycling the Mac's Bluetooth.

## Reading

- **Phase 5 row 2 (pairing prompt): measured in part.** A fresh bond can be created on this Mac and
  the HID channels open on connect. It required a human to compare the codes, because macOS 27's
  `IOBluetoothDevicePair.replyUserConfirmation` is a no-op and cannot answer the numeric comparison
  (same result from `blueutil`), and the Mac cannot be made discoverable for a phone-initiated bond.
- **Keyboard delivery over the fresh bond is not working in this run.** The host binds the device
  (`HID connection state=2`) but the receiver's key counters never move after a sent report, so this
  is an open defect in the re-paired state, not a passing input row.
- Not measured here: protocol negotiation line (the app shows it in its own UI, not in the log), and
  any press made through the app's own arrow pad.
