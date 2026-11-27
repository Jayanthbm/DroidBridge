# DroidBridge — AI Development Guide & Phased Roadmap

## 1. Strict Engineering Rules

1. **Package & Bundle ID:** Use `com.jayanth.droidbridge` across Android (`applicationId`, Kotlin packages) and macOS (`PRODUCT_BUNDLE_IDENTIFIER`).
2. **No Accessibility / No Runtime ADB:**
   - **NEVER** use `AccessibilityService` on Android.
   - **NEVER** use `adb` (USB or Wi-Fi) for delivering taps/input, syncing clipboard, or sharing the screen.
   - **Development-only ADB rule:** USB debugging / ADB **may** be enabled during development for installing builds, launching apps, and reading `logcat`/`dumpsys` diagnostics—**never** as a feature transport.
3. **Reference Archive:** All prior PoCs (P00–P17) are preserved on branch `archive/poc-v1` for reference, but `product/v2` must be built cleanly from scratch.
4. **Design Principles:**
   - **macOS:** Follow native **Liquid Glass** design (SwiftUI translucent glass materials, vibrancy, and depth).
   - **Android:** Follow native **Material 3 Expressive** & **Material You** design (dynamic color, expressive shapes, and typography).
5. **No Unrequested Scope:** Build phase by phase; verify each phase before moving to the next.

---

## 2. Phased Development Roadmap

### Phase 1: Project Skeletons & UI Shell
- Initialize clean Android (Kotlin + Jetpack Compose, `com.jayanth.droidbridge`) and macOS (Swift + SwiftUI, `com.jayanth.droidbridge`) projects.
- Implement the UI screens from `Docs/PRODUCT_SPEC.md` with mock/local state.
- Add `DroidBridge v2.0.0` version footer.

### Phase 2: Wi-Fi Discovery, Pinned TLS Pairing & Multi-Device Session
- Bonjour/mDNS discovery + TLS 1.3 pinned pairing with 6-digit verification code.
- Persistent trust store + automatic reconnect + reconnect/error banner.
- Support multiple concurrent Wi-Fi sessions on macOS.

### Phase 3: Screen Mirroring (+ Optional Audio)
- Android `MediaProjection` H.264 encoder + Foreground Service notification.
- macOS VideoToolbox hardware decoder in the floating phone-bezel window.
- Auto-rotation/resize (Portrait ↔ Landscape), keyframe recovery, and `FLAG_SECURE` blackout.

### Phase 4: Bluetooth Classic HID (Mouse & Keyboard)
- macOS Bluetooth Classic HIDP (SDP + L2CAP `0x0011`/`0x0013`) keyboard + mouse/pointer.
- Multi-phone focus routing: route HID input to the focused mirroring window (`Input: Active` indicator).
- Bottom nav bar shortcuts (Back, Home, Recents).

### Phase 5: Clipboard Sync
- Bidirectional Mac ↔ Phone text sync over Wi-Fi TLS.
- Android foreground-tap fallback (**Sync Clipboard Now**) when backgrounded.

### Phase 6: Polish, Menu Bar & Final Acceptance
- Optional macOS Menu Bar quick-access extra.
- Final acceptance sweep with **USB disconnected and USB/Wireless debugging OFF**.
