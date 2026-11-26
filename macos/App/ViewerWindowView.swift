import AppKit
import SwiftUI

/// The viewer window: the stream, a status line, and nothing else.
///
/// No window chrome, no counters, no controls that are not implemented. Input capture (pointer and
/// keys as Bluetooth HID) is the P17 production surface and lands here next; until then the viewer is
/// deliberately observe-only, and says so rather than offering a dead "control" switch.
struct ViewerWindowView: View {
    @ObservedObject var model: LiveMirrorReceiver

    @State private var hudVisible = true
    @State private var hideTask: Task<Void, Never>?

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            LiveVideoLayerView(layer: model.layer)

            if !model.state.isSharing {
                VStack(spacing: 10) {
                    Image(systemName: "display")
                        .font(.system(size: 28))
                        .foregroundStyle(.secondary)
                    Text(model.state.isLive ? "Waiting for the phone to share" : "Not listening")
                        .font(.headline)
                    Text(model.state.isLive
                         ? "Start sharing from the DroidBridge app on the phone."
                         : "Open DroidBridge and start listening to accept the connection.")
                        .font(.callout)
                        .foregroundStyle(.secondary)
                    if !model.state.isLive {
                        Button("Start listening") { model.startListening() }
                    }
                }
                .padding(24)
            }

            VStack {
                HStack(alignment: .top) {
                    hud
                    Spacer()
                }
                Spacer()
            }
            .padding(12)
            .opacity(hudVisible ? 1 : 0)
            .animation(.easeInOut(duration: 0.25), value: hudVisible)
        }
        .onContinuousHover { _ in showHUD() }
        .navigationTitle("Screen")
    }

    private var hud: some View {
        HStack(spacing: 8) {
            Circle().fill(model.state.dotColor).frame(width: 7, height: 7)
            Text(model.screenLine)
                .font(.system(.caption, design: .monospaced))
            Divider().frame(height: 14)
            Button {
                NSApp.keyWindow?.toggleFullScreen(nil)
            } label: {
                Image(systemName: "arrow.up.left.and.arrow.down.right")
            }
            .buttonStyle(.plain)
            .help("Full screen")
            Button {
                model.stop()
            } label: {
                Image(systemName: "xmark.circle.fill")
            }
            .buttonStyle(.plain)
            .help("Stop listening. The phone stops sharing within about 15 seconds.")
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .background(.ultraThinMaterial, in: Capsule())
    }

    /// The HUD fades out after two still seconds so the phone screen is what remains.
    private func showHUD() {
        if !hudVisible { hudVisible = true }
        hideTask?.cancel()
        hideTask = Task {
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            guard !Task.isCancelled else { return }
            await MainActor.run { hudVisible = false }
        }
    }
}
