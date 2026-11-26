import AppKit
import SwiftUI

/// The menu-bar popover: state, the clipboard shortcut, and the way back to the window.
struct MenuBarView: View {
    @ObservedObject var model: LiveMirrorReceiver
    @Environment(\.openWindow) private var openWindow

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 7) {
                Circle().fill(model.state.dotColor).frame(width: 7, height: 7)
                Text(model.state.isSharing ? "Sharing · " + model.screenLine : model.state.shortLine)
                    .font(.callout)
            }

            Divider()

            Button("Send clipboard now") { _ = model.clipboard.sendLocalClipboard() }
                .disabled(!model.state.isLive)
            Toggle("Pause clipboard sync", isOn: Binding(
                get: { model.clipboard.paused },
                set: { paused in
                    model.clipboard.paused = paused
                    if paused { model.clipboard.stopPolling() } else { model.clipboard.startPolling() }
                }
            ))

            Divider()

            Button("Open DroidBridge") {
                openWindow(id: "main")
                NSApp.activate(ignoringOtherApps: true)
            }
            Button("Open viewer") {
                ViewerWindow.show(model: model)
                NSApp.activate(ignoringOtherApps: true)
            }
            Button(model.state.isLive ? "Stop listening" : "Start listening") {
                if model.state.isLive { model.stop() } else { model.startListening() }
            }

            Divider()

            Button("Quit DroidBridge") { NSApplication.shared.terminate(nil) }
                .keyboardShortcut("q")
        }
        .buttonStyle(.plain)
        .padding(12)
        .frame(width: 250)
    }
}
