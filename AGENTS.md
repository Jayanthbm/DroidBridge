# DroidBridge — Agent Instructions

- **App Name:** DroidBridge
- **Package / Bundle ID:** `com.jayanth.droidbridge`
- **Branch:** `product/v2` (clean slate; prior PoCs are archived on `archive/poc-v1`)
- **Primary Docs:**
  - [Docs/PRODUCT_SPEC.md](Docs/PRODUCT_SPEC.md) — Product requirements & UI/UX flows
  - [Docs/AI_DEVELOPMENT_GUIDE.md](Docs/AI_DEVELOPMENT_GUIDE.md) — Engineering rules & Phases 1–6

## Non-Negotiable Rules
1. **No Accessibility Service & No Runtime ADB:** Never use `AccessibilityService` or `adb` (USB/Wi-Fi) for taps/input, clipboard sync, or screen sharing. USB debugging/ADB may be on during development for builds and `logcat`/`dumpsys` diagnostics only.
2. **Design Languages:**
   - **macOS:** Native **Liquid Glass** (SwiftUI).
   - **Android:** Native **Material 3 Expressive** & **Material You** (Jetpack Compose).
3. **Personal-Use Scope:** No Apple Developer account and no App Store / Play Store publishing. Use local signing and local APK builds.
4. **Phased Delivery:** Follow Phases 1–6 in `Docs/AI_DEVELOPMENT_GUIDE.md` one phase at a time.
