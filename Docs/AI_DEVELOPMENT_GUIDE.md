# DroidBridge — AI Development Guide & Phased Roadmap

## 1. Strict Engineering Rules

1. **Package & Bundle ID:** Use `com.jayanth.droidbridge` across Android (`applicationId`, Kotlin packages) and macOS (`PRODUCT_BUNDLE_IDENTIFIER`).
2. **No Accessibility / No Runtime ADB:**
   - **NEVER** use `AccessibilityService` on Android.
   - **NEVER** use `adb` (USB or Wi-Fi) for delivering taps/input, syncing clipboard, or sharing the screen.
   - **Development-only ADB rule (Phases 1–5):** USB debugging / ADB **is allowed** during Phases 1–5 for installing APKs, launching apps, and reading `logcat`/`dumpsys` logs—**never** as a feature transport. **Phase 6** final acceptance must pass with USB disconnected and debugging **OFF**.
3. **Reference Archive:** All prior PoCs (P00–P17) are preserved on branch `archive/poc-v1` for reference, but `product/v2` must be built cleanly from scratch.
4. **Design Principles:**
   - **macOS:** Follow native **Liquid Glass** design (SwiftUI translucent glass materials, vibrancy, and depth).
   - **Android:** Follow native **Material 3 Expressive** & **Material You** design (dynamic color, expressive shapes, and typography).
5. **Personal-Use Scope (No Store Distribution):** This is a personal app with **no Apple Developer account** and **no App Store / Play Store publishing**. Use local signing for macOS and local APK builds for Android; do not add Developer ID notarization or store-release gates.
6. **Backlog-First on Blockers:** If an item cannot be achieved right away, log it in `Docs/BACKLOG.md` with the reason/blocker and move on to the next item so progress isn't stalled.
7. **Autonomous Phase Progression:** After completing and verifying a phase, commit and push your changes, then immediately start the next phase without waiting—unless human interaction is required (e.g., physical device pairing/testing or a blocking decision).

---

## 2. Phased Development Roadmap

### Phase 1: Project Skeletons & UI Shell
- **macOS:** Create Swift/SwiftUI app (`com.jayanth.droidbridge`) using **Liquid Glass** materials. Implement all 4 UI states with mock data:
  1. No Device Connected (empty state + `+ Connect a Phone`)
  2. Connection Flow (6-digit code UI + Bluetooth HID instructions + `Open Bluetooth Settings` link)
  3. Device Connected Hub (multi-phone sidebar, status pills, inline `Settings` panel, reconnect/error banner)
  4. Floating Phone Mirroring Window (opens on `Start Mirroring`, input focus indicator, Back/Home/Recents bar)
  5. `DroidBridge v2.0.0` footer.
- **Android:** Create Kotlin/Jetpack Compose app (`com.jayanth.droidbridge`) using **Material 3 Expressive** & **Material You** (dynamic color). Implement all 3 UI states with mock data:
  1. No Mac Connected / Onboarding (permissions checklist + `Pair with Mac`)
  2. Connection Flow (6-digit code confirmation + BT HID prompt)
  3. Mac Connected Dashboard (`Start Screen Mirroring`, `Clipboard Sync` + manual fallback button, `Disconnect Mac`)
  4. `DroidBridge v2.0.0` footer.
- **Acceptance:** Both apps build cleanly and all screens/flows are clickable with mock state.

---

### Phase 2: Wi-Fi Discovery, Pinned TLS Pairing & Multi-Device Session
- **Functionality:**
  - Bonjour/mDNS LAN discovery (`_droidbridge._tcp`).
  - TLS 1.3 mutual authentication with 6-digit Short Authentication Code (SAC) verification.
  - Persistent trust store (macOS Keychain / Android Keystore) so paired devices reconnect automatically without re-entering codes.
  - Multi-device session manager on macOS supporting 2+ concurrent Android Wi-Fi connections.
  - Reconnect/error banner when Wi-Fi drops.
- **Acceptance:** Pair Mac ↔ Phone 1 and Mac ↔ Phone 2, kill/relaunch apps, verify automatic reconnect without code prompt.

---

### Phase 3: Screen Mirroring (+ Optional Audio)
- **Functionality:**
  - Android `MediaProjection` H.264 hardware encoder + Foreground Service with persistent Stop notification.
  - macOS VideoToolbox hardware decoder rendering inside the floating phone-bezel window.
  - Automatic Portrait ↔ Landscape rotation & window resize, keyframe recovery on packet loss, and `FLAG_SECURE` blackout.
  - Optional playback audio capture & streaming (Android `AudioPlaybackCapture` → macOS).
- **Acceptance:** Live mirroring at smooth frame rate, rotation in both directions, `FLAG_SECURE` screens render black, Stop works from both Mac and Android notification.

---

### Phase 4: Bluetooth Classic HID (Mouse & Keyboard)
- **Functionality:**
  - macOS Bluetooth Classic HIDP (SDP + L2CAP `0x0011`/`0x0013`) emulating a combined Keyboard + Mouse.
  - `Cmd` → `Ctrl` shortcut mapping, trackpad scrolling, and right-click → Back.
  - Multi-phone focus routing: HID input routes to the **currently focused** mirroring window (`Input: Active` vs `Click to focus`).
  - Bottom nav bar shortcuts (Back, Home, Recents) mapped to Android HID keycodes.
- **Acceptance:** With USB unplugged and ADB off, type text, use arrow keys, move mouse cursor, click, and use Back/Home/Recents on the phone from Mac.

---

### Phase 5: Clipboard Sync
- **Functionality:**
  - Bidirectional Mac ↔ Phone text clipboard sync over the encrypted Wi-Fi TLS channel.
  - Android foreground-tap fallback (`Sync Clipboard Now` button / bringing app forward) when Android is backgrounded.
- **Acceptance:** Copy on Mac → paste on Phone; copy on Phone → paste on Mac (both foregrounded and via fallback).

---

### Phase 6: Polish, Menu Bar & Final Acceptance
- **Functionality:**
  - Optional macOS Menu Bar quick-access extra (open mirror window, sync clipboard, status).
  - Keep screen awake while mirroring + Drag-and-drop file transfer (Mac → Android `Downloads/`).
  - Final edge-case polish (sleep/wake, Wi-Fi toggle, multi-phone switching).
- **Acceptance:** Full end-to-end test sweep with **USB disconnected and USB/Wireless debugging OFF** on Android.
