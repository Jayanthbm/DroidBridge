import AppKit

/// Launch-time wiring shared by every window, run once per app run.
///
/// These flags were read from the old tab view's `onAppear`, which made "the prototype tabs were
/// built" a load-bearing fact for scripted runs. The two-window shell calls this from the main
/// window instead, so `--p08-listen` still starts the listener without a Mac-side click, and the
/// guard makes it idempotent because either window may appear first.
@MainActor
enum AppBoot {
    private static var didRun = false

    static func run(
        appDelegate: BridgeAppDelegate,
        liveMirror: LiveMirrorReceiver,
        pasteboard: PasteboardModel,
        hidPeripheral: ClassicHIDPeripheral
    ) {
        guard !didRun else { return }
        didRun = true

        appDelegate.hidPeripheral = hidPeripheral
        if HarnessFlags.isOn("--p17-hid") {
            appDelegate.holdTermination(reason: "HID peripheral is active")
            // The harness itself is started by BridgeAppDelegate.applicationDidFinishLaunching,
            // which owns the same peripheral instance and does not depend on this view appearing.
        }
        // Force the monitor to start with the window, independent of SwiftUI's lazy view
        // creation. The prompt needs a real app window, not a headless harness.
        if HarnessFlags.isOn("--p06-monitor") {
            pasteboard.start()
        }
        // P08 keyframe-recovery harness: inject one corrupt sample after the given delay so the
        // renderer-failed -> flush -> keyframe-request path can be measured by a script.
        if let raw = HarnessFlags.value(after: "--p08-fault-after"),
           let seconds = TimeInterval(raw) {
            liveMirror.scriptedFaultAfter = seconds
        }
        // P08/P10 harness: start the secure listener with the window so an unattended run does not
        // need a Mac-side click to begin accepting the phone's connection.
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
            // The peer id and service name select which stored pin applies, so a run can start from
            // a genuinely unpinned peer instead of inheriting the pin left by an earlier prototype.
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
