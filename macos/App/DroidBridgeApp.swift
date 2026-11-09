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

    /// P07 receiver. The fixture is bundled; --p07-stream can point at an external Annex-B file.
    @StateObject private var video = VideoReceiverModel(
        autoPlay: CommandLine.arguments.contains("--p07-autoplay"),
        streamPath: Self.argumentValue("--p07-stream")
    )

    /// Open a prototype tab directly for a scripted or manual run.
    @State private var selectedTab: Int = CommandLine.arguments.contains("--p06-monitor")
        ? 2
        : (CommandLine.arguments.contains("--p07") ? 3 : 0)

    private static func argumentValue(_ flag: String) -> String? {
        guard let index = CommandLine.arguments.firstIndex(of: flag),
              index + 1 < CommandLine.arguments.count else { return nil }
        return CommandLine.arguments[index + 1]
    }

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
                VideoView(model: video)
                    .tabItem { Label("P07 Video", systemImage: "play.rectangle") }
                    .tag(3)
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
