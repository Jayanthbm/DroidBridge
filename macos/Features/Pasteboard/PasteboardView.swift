import SwiftUI

/// P06 prototype surface.
///
/// The UI is a measurement console: it exposes the access-behavior state, the poll count and an
/// event log. It never shows clipboard text, only length and hash.
struct PasteboardView: View {
    @ObservedObject var model: PasteboardModel

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("P06 — Mac pasteboard access")
                .font(.title2).bold()

            Text("macOS 15.4+ can prompt before an app reads the pasteboard. "
                 + "This prototype records the prompt, the access behavior and the write-suppression loop.")
                .font(.callout)
                .foregroundStyle(.secondary)

            HStack(spacing: 12) {
                Button(model.monitoring ? "Stop monitoring" : "Start monitoring") {
                    model.monitoring ? model.stop() : model.start()
                }
                .keyboardShortcut(.defaultAction)
                Button("Write test text") { model.writeTestText() }
                Button("Read now") { model.readNow() }
                Button("Clear clipboard") { model.clearClipboard() }
            }

            GroupBox("State") {
                VStack(alignment: .leading, spacing: 4) {
                    row("monitoring", model.monitoring ? "yes" : "no")
                    row("access behavior", model.accessBehavior)
                    row("polls", String(model.pollCount))
                    row("reads", String(model.readCount))
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(4)
            }

            GroupBox("Events") {
                ScrollView {
                    VStack(alignment: .leading, spacing: 2) {
                        if model.events.isEmpty {
                            Text("Nothing yet. Start monitoring, then copy text in another app.")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        ForEach(Array(model.events.enumerated()), id: \.offset) { _, event in
                            Text(event)
                                .font(.system(.caption, design: .monospaced))
                                .textSelection(.enabled)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                    }
                    .padding(4)
                }
                .frame(minHeight: 180)
            }

            Text("No clipboard content is logged: only length and SHA-256 prefix.")
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
        .padding(20)
        .frame(minWidth: 560, minHeight: 460)
    }

    private func row(_ label: String, _ value: String) -> some View {
        HStack(alignment: .top, spacing: 8) {
            Text(label)
                .font(.system(.caption, design: .monospaced))
                .foregroundStyle(.secondary)
                .frame(width: 110, alignment: .leading)
            Text(value)
                .font(.system(.caption, design: .monospaced))
                .textSelection(.enabled)
        }
    }
}
