// Copyright (C) 2026 DroidBridge contributors.
// SPDX-License-Identifier: none — internal feasibility experiment, not for distribution.
//
// P17 — isolated macOS Bluetooth Classic HIDP (HID device role) experiment.
//
// Explicit controls only: nothing is published, connected or sent until the user asks.
// No Accessibility permission, no event tap, no global input capture, no private API.
import AppKit
import SwiftUI

@main
struct P17ClassicHIDPApp: App {
    @NSApplicationDelegateAdaptor(P17AppDelegate.self) private var appDelegate
    @StateObject private var peripheral = ClassicHIDPeripheral()

    var body: some Scene {
        WindowGroup("P17 Classic HIDP") {
            P17ClassicHIDPView(peripheral: peripheral)
                .frame(minWidth: 580, minHeight: 760)
                .task { appDelegate.peripheral = peripheral }
        }
    }
}

/// Withdraws the HID SDP record and closes the L2CAP channels before the process goes away.
///
/// Without this, quitting while connected leaves the peer and bluetoothd holding a HID session
/// that no longer has an owner, and the next launch is refused with
/// `L2CAP control channel (0x0011) failed (0xE00002BC)` until the phone's Bluetooth is cycled.
@MainActor
final class P17AppDelegate: NSObject, NSApplicationDelegate {
    var peripheral: ClassicHIDPeripheral?

    func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply {
        peripheral?.stop()
        return .terminateNow
    }

    func applicationDidFinishLaunching(_ notification: Notification) {
        installTerminationSignalHandlers()
    }
}

/// Runs the same shutdown path for `SIGTERM`/`SIGINT` as for a normal quit.
///
/// AppKit does not turn these signals into an `applicationShouldTerminate` call, so a `kill` or a
/// logout used to drop the process without withdrawing the SDP record or closing the L2CAP
/// channels. The peer then kept a HID session nobody owned and the next launch was refused with
/// `0xE00002BC` until Bluetooth was cycled on one side. Confirmed by measurement: `pkill` caused
/// exactly that, while an AppleEvent quit (`osascript ... to quit`) logged `stopped` and reconnected
/// cleanly.
private func installTerminationSignalHandlers() {
    for sig in [SIGTERM, SIGINT] {
        signal(sig, SIG_IGN)
        let source = DispatchSource.makeSignalSource(signal: sig, queue: .main)
        source.setEventHandler {
            // The source is delivered on the main queue, so the main-actor work can be assumed.
            MainActor.assumeIsolated {
                if let delegate = NSApp.delegate as? P17AppDelegate {
                    delegate.peripheral?.stop()
                }
            }
            exit(0)
        }
        source.resume()
        terminationSignalSources.append(source)
    }
}

private var terminationSignalSources: [DispatchSourceSignal] = []
