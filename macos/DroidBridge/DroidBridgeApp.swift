import SwiftUI

@main
struct DroidBridgeApp: App {
    @State private var model = AppModel()

    var body: some Scene {
        Window("DroidBridge", id: "main") {
            RootView()
                .environment(model)
        }
        .defaultSize(width: 1000, height: 720)
        .windowResizability(.contentMinSize)
        .commands {
            CommandGroup(replacing: .newItem) {}

            CommandMenu("Device") {
                Button("Pair New Phone…") {
                    model.beginPairing()
                }
                .keyboardShortcut("n", modifiers: .command)
                .disabled(model.isPairingPresented)

                if let deviceID = model.selectedDeviceID {
                    Button("Sync Clipboard") {
                        model.syncClipboard(deviceID: deviceID)
                    }
                    .keyboardShortcut("c", modifiers: [.command, .shift])

                    Divider()

                    Button("Disconnect") {
                        model.disconnect(deviceID: deviceID)
                    }
                }
            }
        }

        WindowGroup("Phone Mirror", id: "mirror", for: String.self) { $deviceID in
            if let deviceID {
                MirrorWindowView(deviceID: deviceID)
                    .environment(model)
            } else {
                ContentUnavailableView("No Phone Selected", systemImage: "iphone.gen3")
                    .frame(minWidth: 360, minHeight: 600)
            }
        }
        .defaultSize(width: 460, height: 840)
        .windowResizability(.contentMinSize)
        .restorationBehavior(.disabled)
        .defaultLaunchBehavior(.suppressed)
    }
}
