import SwiftUI

/// The macOS shell.
///
/// Phase 0 scope: prove the target builds, the sandbox entitlements sign, and a SwiftUI window
/// with a menu-bar presence launches. There is no discovery, no clipboard and no viewer, because
/// none of those exist yet and a control that does nothing misrepresents the product's state.
@main
struct DroidBridgeApp: App {
    /// Created at launch, so `--advertise-on-launch` works without the user opening the tab.
    @StateObject private var advertisement = AdvertisementModel()

    /// P06 pasteboard model. Auto-starts only under the explicit prototype flag.
    @StateObject private var pasteboard = PasteboardModel(
        autoStart: CommandLine.arguments.contains("--p06-monitor"),
        selfWriteAfter: CommandLine.arguments.contains("--p06-self-write") ? 5 : nil
    )

    /// Open the P06 tab directly for a scripted or manual P06 run.
    @State private var selectedTab: Int = CommandLine.arguments.contains("--p06-monitor") ? 2 : 0

    init() {
        // P03 headless harness. Handled before SwiftUI starts so a scripted run gets a listener
        // without a window, and exits with the harness's status rather than the app's.
        //
        // This is a prototype affordance, not a product feature: it exists so the P03 acceptance
        // criteria can be measured by a script instead of by tapping buttons.
        let arguments = Array(CommandLine.arguments.dropFirst())
        if arguments.contains("--p06-harness") {
            exit(P06Harness.run(arguments))
        }
        if arguments.contains("--listen")
            || arguments.contains("--pair")
            || arguments.contains("--identity") {
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
            }
            .onAppear {
                // Force the monitor to start with the window, independent of SwiftUI's lazy tab
                // creation. The prompt needs a real app window, not a headless harness.
                if CommandLine.arguments.contains("--p06-monitor") {
                    pasteboard.start()
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
