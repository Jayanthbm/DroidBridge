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

    init() {
        // P03 headless harness. Handled before SwiftUI starts so a scripted run gets a listener
        // without a window, and exits with the harness's status rather than the app's.
        //
        // This is a prototype affordance, not a product feature: it exists so the P03 acceptance
        // criteria can be measured by a script instead of by tapping buttons.
        let arguments = Array(CommandLine.arguments.dropFirst())
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
            TabView {
                DashboardView(environment: EnvironmentSummary.current())
                    .tabItem { Label("Environment", systemImage: "info.circle") }
                AdvertisementView(model: advertisement)
                    .tabItem { Label("P02 Advertiser", systemImage: "antenna.radiowaves.left.and.right") }
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
