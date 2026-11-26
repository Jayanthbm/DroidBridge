import AppKit
import SwiftUI

/// Holds the app open while a mirroring session is running.
///
/// AppKit's automatic termination ends an idle window (the log shows
/// `_kLSApplicationWouldBeTerminatedByTALKey=1`), which killed the P08/P10 receiver mid-session and
/// looked like a protocol failure. A receiver that is showing or waiting for a mirror must not be
/// terminated just because the user is not touching it; P17 hit the same problem and fixed it the
/// same way.
final class BridgeAppDelegate: NSObject, NSApplicationDelegate {
    private var holdReason: String?

    /// Set by the SwiftUI scene after the state objects exist; the HID peripheral must be
    /// withdrawn (SDP record removed, L2CAP channels closed) before the process dies.
    /// Owned here so the HID harness and termination cleanup are reachable from AppKit even before
    /// the SwiftUI main window's `onAppear` has run (which is not guaranteed in a headless run).
    var hidPeripheral: ClassicHIDPeripheral?

    func applicationDidFinishLaunching(_ notification: Notification) {
        if HarnessFlags.isOn("--p08-mirror")
            || HarnessFlags.isOn("--advertise-on-launch")
            || HarnessFlags.isOn("--listen") {
            holdTermination(reason: "mirror receiver or advertiser is active")
        }
        installTerminationSignalHandlers()
        // The HID harness must not depend on the Diagnostics > HID tab being shown. Launch it here
        // (after IOBluetooth warm-up, off the main thread) so unattended --harness-* runs work.
        if HarnessFlags.isOn("--p17-hid") {
            Task { @MainActor in
                await self.hidPeripheral?.warmUpIOBluetooth()
                self.hidPeripheral?.refreshPairedDevices()
                self.hidPeripheral?.runHarnessIfRequested()
            }
        }
    }

    /// Withdraws the HID SDP record and closes the L2CAP channels before the process goes away.
    ///
    /// Without this, quitting while connected leaves the peer and bluetoothd holding a HID session
    /// that no longer has an owner, and the next launch is refused with
    /// `L2CAP control channel (0x0011) failed (0xE00002BC)` until the phone's Bluetooth is cycled.
    /// Measured in P17; the promotion inherits the requirement, not just the code.
    func holdTermination(reason: String) {
        guard holdReason == nil else { return }
        holdReason = reason
        ProcessInfo.processInfo.disableAutomaticTermination(reason)
        ProcessInfo.processInfo.disableSuddenTermination()
    }

    func releaseTerminationHold() {
        guard let holdReason else { return }
        self.holdReason = nil
        ProcessInfo.processInfo.enableAutomaticTermination(holdReason)
        ProcessInfo.processInfo.enableSuddenTermination()
    }
    func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply {
        // Normal path: stop() is idempotent (channels already closed and cleaned up when the
        // signal handler awaited them; nothing pending here) and the app terminates now.
        hidPeripheral?.stop()
        return .terminateNow
    }
}

/// Runs the same shutdown path for `SIGTERM`/`SIGINT` as for a normal quit.
///
/// AppKit does not turn these signals into an `applicationShouldTerminate` call, so a `kill` or a
/// logout would drop the process without withdrawing the SDP record or closing the L2CAP channels.
/// The peer then kept a HID session nobody owned and the next launch was refused with `0xE00002BC`
/// until Bluetooth was cycled on one side. Confirmed by measurement in P17: `pkill` caused exactly
/// that, while an AppleEvent quit logged `stopped` and reconnected cleanly.
private func installTerminationSignalHandlers() {
    for sig in [SIGTERM, SIGINT] {
        signal(sig, SIG_IGN)
        let source = DispatchSource.makeSignalSource(signal: sig, queue: .main)
        source.setEventHandler {
            MainActor.assumeIsolated {
                guard let delegate = NSApp.delegate as? BridgeAppDelegate else { exit(0) }
                let peripheral = delegate.hidPeripheral
                peripheral?.stop()
                // Measured 2026-11-24: exit(0) after stop() — immediately, after a fixed 1.5 s
                // hold, and after awaiting the L2CAP closed-callbacks — each left bluetoothd
                // holding the HID session (0xE00002BC on the next launch), while an AppleEvent
                // quit while connected relaunches clean. So: await the closes, bounded, then let
                // the process die through AppKit's normal termination instead of exit(0).
                let finish: () -> Void = { NSApp.terminate(nil) }
                guard let peripheral, peripheral.awaitingChannelClose else { finish(); return }
                var closed = false
                peripheral.closeWaitContinuation = { closed = true }
                var waited = 0.0
                func tick() {
                    if closed || waited >= 4.0 { finish(); return }
                    waited += 0.1
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.1, execute: tick)
                }
                tick()
            }
        }
        source.resume()
        terminationSignalSources.append(source)
    }
}

private var terminationSignalSources: [DispatchSourceSignal] = []
