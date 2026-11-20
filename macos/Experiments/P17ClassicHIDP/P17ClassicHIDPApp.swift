// Copyright (C) 2026 DroidBridge contributors.
// SPDX-License-Identifier: none — internal feasibility experiment, not for distribution.
//
// P17 — isolated macOS Bluetooth Classic HIDP (HID device role) experiment.
//
// Explicit controls only: nothing is published, connected or sent until the user asks.
// No Accessibility permission, no event tap, no global input capture, no private API.
import SwiftUI

@main
struct P17ClassicHIDPApp: App {
    @StateObject private var peripheral = ClassicHIDPeripheral()

    var body: some Scene {
        WindowGroup("P17 Classic HIDP") {
            P17ClassicHIDPView(peripheral: peripheral)
                .frame(minWidth: 560, minHeight: 660)
        }
    }
}
