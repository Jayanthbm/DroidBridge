# DroidBridge — Product & UX Specification

**App Name:** DroidBridge  
**Package / Bundle ID:** `com.jayanth.droidbridge`  
**Version:** `2.0.0`  
**Branch:** `product/v2` (clean slate; PoC history archived in `archive/poc-v1`)

---

## 1. Product Vision & Core Requirements

DroidBridge is an **iPhone Mirroring–like experience on macOS for Android phones**, built from scratch without Accessibility Services or runtime ADB.

### Core Capabilities
1. **Wi-Fi + Bluetooth Connection:**
   - **Wi-Fi (Bonjour/mDNS + Pinned TLS 1.3):** Screen mirroring, optional playback audio, session control, and clipboard sync.
   - **Bluetooth Classic HIDP (SDP + L2CAP `0x0011`/`0x0013`):** Mac acts as a Bluetooth keyboard and mouse/pointer to control the phone (visible cursor on phone is expected). *Note: BLE HOGP is not supported on Android for dual-mode Mac hosts.*
2. **No Accessibility & No Runtime ADB:**
   - Zero `AccessibilityService` usage.
   - Zero `adb` usage at runtime (USB or Wi-Fi).
3. **Clipboard Sync (Mac ↔ Phone):**
   - Bidirectional text sync over the encrypted Wi-Fi TLS channel.
   - When Android is backgrounded (Android 10+ clipboard restriction), fallback to bringing the app forward / tapping **Sync Clipboard Now** (to be refined in later phases).
4. **Multi-Device Support:**
   - Mac can connect to **multiple Android phones** simultaneously over Wi-Fi (each in its own mirroring window).
   - **Bluetooth Classic HID** input routes to the **currently focused** mirroring window.

---

## 2. macOS App UI & UX Flow
**Design Language:** Native **Liquid Glass** (SwiftUI translucent glass materials, depth, and vibrancy).  
Reference preview: [macos-ui-preview.html](design/macos-ui-preview.html)

1. **No Device Connected (Empty State):**
   - Clean centered card with **+ Connect a Phone** CTA.
   - Footer: `DroidBridge v2.0.0`.
2. **Connection Flow (also used when adding a new device):**
   - **Step 1:** 6-digit Wi-Fi pairing code verification.
   - **Step 2:** One-time Bluetooth Classic HID bond with step-by-step instructions and a **Open Bluetooth Settings** link (`x-apple.systempreferences:com.apple.BluetoothSettings`).
3. **Device Connected (Hub):**
   - **Sidebar:** List of paired phones with Wi-Fi + BT status and **+ Pair New Phone** button.
   - **Main Pane:** Selected phone details, status pills (`Wi-Fi TLS`, `BT Classic HID`), **Start Mirroring**, **Sync Clipboard**, **⚙️ Settings** (inline panel: Stream Quality, Auto-Sync Clipboard, Auto-Reconnect), and **Disconnect**.
   - **Reconnect / Error Banner:** Inline banner if Wi-Fi drops (`Reconnecting...`) or Bluetooth HID disconnects.
4. **Floating Phone Mirroring Window (opens on Start Mirroring):**
   - Phone-bezel window that **auto-rotates/resizes** between Portrait and Landscape.
   - **Top Toolbar:** Input Focus indicator (`⌨️ Input: Active` vs `Click to focus` for multi-phone), Audio toggle, Clipboard Sync button, Stop/Close.
   - **Bottom Nav Bar:** Back, Home, Recents (sent via Bluetooth HID shortcuts).
5. **Menu Bar Extra (Optional Quick Access):**
   - Lightweight menu bar icon to quickly open a phone's mirror window or trigger clipboard sync.

---

## 3. Android App UI & UX Flow
Reference preview: [android-ui-preview.html](design/android-ui-preview.html)

1. **No Mac Connected / Onboarding:**
   - **Pair with Mac** CTA + Required Permissions checklist (Local Network, Bluetooth, Notifications).
   - Footer: `DroidBridge v2.0.0`.
2. **Connection Flow:**
   - **Step 1:** Confirm 6-digit Wi-Fi pairing code.
   - **Step 2:** Accept one-time Bluetooth HID pairing prompt.
3. **Mac Connected (Dashboard):**
   - Connected Mac card (`Wi-Fi + BT` status).
   - **Start Screen Mirroring** primary button (triggers `MediaProjection` consent + persistent Foreground Service notification with Stop action).
   - **Clipboard Sync** card with manual **Sync Clipboard Now** fallback button.
   - **Disconnect Mac** button.
