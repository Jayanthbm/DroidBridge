import SwiftUI

/// The macOS shell.
///
/// Phase 0 scope: prove the target builds, the sandbox entitlements sign, and a SwiftUI window
/// with a menu-bar presence launches. There is no discovery, no clipboard and no viewer, because
/// none of those exist yet and a control that does nothing misrepresents the product's state.
@main
struct DroidBridgeApp: App {
    @NSApplicationDelegateAdaptor(BridgeAppDelegate.self) private var appDelegate
    /// Created at launch, so `--advertise-on-launch` works without the user opening the tab.
    @StateObject private var advertisement = AdvertisementModel()

    /// P06 pasteboard model. Auto-starts only under the explicit prototype flag.
    @StateObject private var pasteboard = PasteboardModel(
        autoStart: HarnessFlags.isOn("--p06-monitor"),
        selfWriteAfter: HarnessFlags.isOn("--p06-self-write") ? 5 : nil
    )

    /// P07 receiver. The fixture is bundled; --p07-stream can point at an external Annex-B file.
    @StateObject private var video = VideoReceiverModel(
        autoPlay: HarnessFlags.isOn("--p07-autoplay"),
        streamPath: HarnessFlags.value(after: "--p07-stream")
    )

    /// P08 live mirror receiver.
    @StateObject private var liveMirror = LiveMirrorReceiver()

    /// Phase 5: Bluetooth Classic HID keyboard peripheral (promoted from P17).
    @StateObject private var hidPeripheral = ClassicHIDPeripheral()

    /// Open a prototype tab directly for a scripted or manual run.
    @State private var selectedTab: Int = HarnessFlags.isOn("--p06-monitor")
        ? 2
        : (HarnessFlags.isOn("--p08-mirror") ? 4
            : (HarnessFlags.isOn("--p17-hid") ? 5
                : (HarnessFlags.isOn("--p07") ? 3 : 0)))

    init() {
        // P03 headless harness. Handled before SwiftUI starts so a scripted run gets a listener
        // without a window, and exits with the harness's status rather than the app's.
        //
        // This is a prototype affordance, not a product feature: it exists so the P03 acceptance
        // criteria can be measured by a script instead of by tapping buttons.
        let arguments = Array(CommandLine.arguments.dropFirst())
        if HarnessFlags.isOn("--p06-harness") {
            exit(P06Harness.run(arguments))
        }
        if HarnessFlags.isOn("--listen")
            || HarnessFlags.isOn("--pair")
            || HarnessFlags.isOn("--identity") {
            exit(P03Harness.run(arguments))
        }
    }

    var body: some Scene {
        WindowGroup("DroidBridge") {
            // Two tabs: the Phase 0 environment baseline, and the P02 advertiser. The advertiser
            // is a prototype surface, labelled as such, not a product feature.
            TabView(selection: $selectedTab) {
                DashboardView(environment: EnvironmentSummary.current())
                    .tabItem { Label("Environment", systemImage: "info.circle") }
                    .tag(0)
                AdvertisementView(model: advertisement)
                    .tabItem { Label("P02 Advertiser", systemImage: "antenna.radiowaves.left.and.right") }
                    .tag(1)
                PasteboardView(model: pasteboard)
                    .tabItem { Label("P06 Pasteboard", systemImage: "doc.on.clipboard") }
                    .tag(2)
                VideoView(model: video)
                    .tabItem { Label("P07 Video", systemImage: "play.rectangle") }
                    .tag(3)
                LiveMirrorView(model: liveMirror)
                    .tabItem { Label("P08 Mirror", systemImage: "display") }
                    .tag(4)
                HIDRemoteView(peripheral: hidPeripheral)
                    .tabItem { Label("HID Keyboard", systemImage: "keyboard") }
                    .tag(5)
            }
            .onAppear {
                appDelegate.hidPeripheral = hidPeripheral
                if HarnessFlags.isOn("--p17-hid") {
                    appDelegate.holdTermination(reason: "HID peripheral is active")
                }
                // Force the monitor to start with the window, independent of SwiftUI's lazy tab
                // creation. The prompt needs a real app window, not a headless harness.
                if HarnessFlags.isOn("--p06-monitor") {
                    pasteboard.start()
                }
                // P08 keyframe-recovery harness: inject one corrupt sample after the given delay so
                // the renderer-failed -> flush -> keyframe-request path can be measured by a script.
                if let raw = HarnessFlags.value(after: "--p08-fault-after"),
                   let seconds = TimeInterval(raw) {
                    liveMirror.scriptedFaultAfter = seconds
                }
                // P10 evidence harness: the display layer sets `preventsCapture`, which is correct
                // for normal use but also hides the rendered video from `screencapture`, so the
                // mirrored pixels cannot be recorded without turning it off for the run.
                // P08/P10 harness: start the secure listener with the window so an unattended run
                // does not need a Mac-side click to begin accepting the phone's connection.
                if let expected = HarnessFlags.value(after: "--p08-expect-pin") {
                    liveMirror.expectedPeerPin = expected
                }
                // Phase 2 harness: confirm a first pairing only for a code the operator has already
                // compared on both screens. Never a blind confirm.
                if let code = HarnessFlags.value(after: "--p08-confirm-code") {
                    liveMirror.expectedPairingCode = code
                }
                if let raw = HarnessFlags.value(after: "--p08-idle-timeout"), let seconds = TimeInterval(raw) {
                    liveMirror.idleTimeoutSeconds = seconds
                }
                // Phase 3: watch the Mac pasteboard for user copies and send them to the phone.
                if HarnessFlags.isOn("--clipboard-poll") {
                    liveMirror.clipboard.startPolling()
                }
                if HarnessFlags.isOn("--p08-listen") {
                    // Phase 2 harness: the peer id and service name select which stored pin applies,
                    // so a run can start from a genuinely unpinned peer instead of inheriting the
                    // pin left by an earlier prototype. Without this, a first pairing is impossible to
                    // exercise on a Mac that has ever paired with anything.
                    liveMirror.startListening(
                        peerId: HarnessFlags.value(after: "--p08-peer-id") ?? "android-p08",
                        serviceName: HarnessFlags.value(after: "--p08-service-name") ?? "DroidBridge-P08-Mac",
                        resetPin: HarnessFlags.isOn("--p08-reset-pin"),
                        // Fixed port so a run can drop and restore the listener on the same address.
                        port: HarnessFlags.value(after: "--p08-port").flatMap { UInt16($0) } ?? 0
                    )
                }
                if HarnessFlags.isOn("--p08-allow-capture") {
                    liveMirror.allowScreenCapture()
                }
            }
        }
        .defaultSize(width: 620, height: 560)

        MenuBarExtra("DroidBridge", systemImage: "iphone.gen3") {
            Text("Not connected")
            Divider()
            Button("Quit DroidBridge") {
                NSApplication.shared.terminate(nil)
            }
        }
    }
}

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
    weak var hidPeripheral: ClassicHIDPeripheral?

    func applicationDidFinishLaunching(_ notification: Notification) {
        if HarnessFlags.isOn("--p08-mirror")
            || HarnessFlags.isOn("--advertise-on-launch")
            || HarnessFlags.isOn("--listen") {
            holdTermination(reason: "mirror receiver or advertiser is active")
        }
        installTerminationSignalHandlers()
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
