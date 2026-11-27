# DroidBridge — Architecture, Protocol & Permissions

## 1. Transport Architecture

| Channel | Technology | Purpose |
|---|---|---|
| **Discovery** | Bonjour / mDNS (`_droidbridge._tcp`) | Zero-config LAN discovery between Mac & Android |
| **Control, Video, Audio, Clipboard** | TCP + TLS 1.3 (Mutual Auth, Pinning) | Encrypted session, H.264 video, PCM/AAC audio, clipboard text, heartbeat |
| **Keyboard & Mouse Input** | Bluetooth Classic HIDP (SDP + L2CAP `0x0011`/`0x0013`) | Combined Keyboard + Mouse HID device; no Accessibility or ADB |

---

## 2. Wire Protocol (Over TLS 1.3)

Length-prefixed binary frames: `[4-byte length][1-byte type][payload]`

- `0x01` **HELLO / PAIR:** Device ID, name, public key fingerprint, 6-digit SAC confirmation.
- `0x02` **VIDEO_FRAME:** H.264 Annex-B / AVCC access unit + orientation (`portrait` / `landscape`) + keyframe flag.
- `0x03` **AUDIO_FRAME:** Optional playback audio frames (when Audio is **On** in macOS settings).
- `0x04` **CLIPBOARD_SYNC:** UTF-8 text payload + timestamp/origin ID (loop prevention).
- `0x05` **CONTROL:** Start/Stop mirroring, keyframe request, stream quality, disconnect.
- `0x06` **PING / PONG:** Keepalive heartbeat & reconnect detection.

---

## 3. Bluetooth Classic HIDP & Multi-Phone Routing

- **Combined HID Descriptor:** Reports both **Keyboard** (standard keys + Android nav shortcuts: Back, Home, Recents) and **Mouse** (relative X/Y movement, left click, right click → Back, scroll wheel).
- **One-Time Bond:** On macOS 27, initial Bluetooth pairing is done once manually in System Settings; subsequent connections reconnect automatically.
- **Multi-Phone Focus:** macOS maintains 2+ concurrent Wi-Fi TLS sessions, but routes Bluetooth HID input **only to the currently focused** mirroring window (`Input: Active`).

---

## 4. Permissions Matrix

### macOS (`com.jayanth.droidbridge`)
- `NSLocalNetworkUsageDescription` — LAN discovery & mirroring
- `NSBluetoothAlwaysUsageDescription` — Bluetooth Classic HIDP input

### Android (`com.jayanth.droidbridge`)
- `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` — Wi-Fi TLS session
- `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN` — Bluetooth Classic HID bond status
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PROJECTION` — Screen mirroring FGS
- `POST_NOTIFICATIONS` — Persistent mirroring Stop notification
- `RECORD_AUDIO` — Optional playback audio capture (when enabled)

---

## 5. Known Platform Constraints & Gaps to Handle
1. **Single-Use `MediaProjection` Token (Android 14+):** Stopping mirroring invalidates the token; starting again requires tapping **Start Screen Mirroring** to re-consent.
2. **Background Clipboard (Android 10+):** Android blocks background clipboard reads; DroidBridge provides the **Sync Clipboard Now** button / foreground-tap fallback.
3. **`FLAG_SECURE` Screens:** Banking/DRM apps render black in the mirror stream by Android OS design.
4. **Call Audio:** Android platform policy blocks capturing voice-call audio (`USAGE_VOICE_COMMUNICATION`); only media/game playback audio is streamed.
