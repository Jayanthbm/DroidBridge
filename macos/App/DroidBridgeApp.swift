import SwiftUI

/// The macOS shell: a main window, a viewer window, and a menu-bar item.
///
/// The main window is the only window *scene*. The viewer is an AppKit window (see
/// `ViewerWindow`): a second scene made the system launch that scene instead of the main window,
/// so the launch bootstrap never ran. Two windows remain the product shape; only the plumbing
/// differs. The prototype surfaces stay reachable under Diagnostics, and the harness flags are read
/// once per run in `AppBoot`.
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

    /// Phase 5: Bluetooth Classic HID keyboard peripheral (promoted from P17). The delegate owns
    /// the same instance so AppKit can reach it before the main window's onAppear runs.
    @StateObject private var hidPeripheral: ClassicHIDPeripheral

    init() {
        // One peripheral instance shared by the SwiftUI tree and the AppKit delegate. SwiftUI may
        // re-initialise this struct (a StateObject keeps only the first value), so the delegate is
        // attached once: otherwise it could hold an instance the UI never shows and termination
        // cleanup would withdraw the wrong peripheral.
        let hid = ClassicHIDPeripheral()
        _hidPeripheral = StateObject(wrappedValue: hid)
        if appDelegate.hidPeripheral == nil {
            appDelegate.hidPeripheral = hid
        }

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
        WindowGroup("DroidBridge", id: "main") {
            MainWindowView(
                appDelegate: appDelegate,
                liveMirror: liveMirror,
                advertisement: advertisement,
                pasteboard: pasteboard,
                video: video,
                hidPeripheral: hidPeripheral
            )
        }
        .defaultSize(width: 820, height: 580)

        MenuBarExtra {
            MenuBarView(model: liveMirror)
        } label: {
            Image(systemName: liveMirror.state.isSharing ? "iphone.gen3.radiowaves.left.and.right" : "iphone.gen3")
        }
        .menuBarExtraStyle(.window)
    }
}
