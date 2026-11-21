// Copyright (C) 2026 DroidBridge contributors.
// SPDX-License-Identifier: none — internal feasibility experiment, not for distribution.
import SwiftUI

struct P11LocalNetworkView: View {
    @ObservedObject var probe: LocalNetworkProbe
    @State private var target = "192.168.31.112:5555"

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Local Network privacy probe")
                .font(.title2).bold()
            Text(
                "The product app only listens, which macOS does not gate. These two controls perform "
                    + "the operations that are gated, so the alert and the denied state can be measured."
            )
            .font(.callout)
            .foregroundStyle(.secondary)

            Divider()

            HStack(spacing: 10) {
                Button("Browse for \(LocalNetworkProbe.serviceType)") { probe.startBrowse() }
                Button("Stop browse") { probe.stopBrowse() }
                    .disabled(false)
            }

            HStack(spacing: 10) {
                TextField("host:port", text: $target)
                    .textFieldStyle(.roundedBorder)
                    .frame(width: 220)
                Button("Connect (outbound)") { probe.connect(to: target) }
            }

            Divider()

            Text(probe.status)
                .font(.system(.body, design: .monospaced))
                .textSelection(.enabled)

            if let error = probe.lastError {
                Text("lastError: \(error)")
                    .font(.system(.footnote, design: .monospaced))
                    .foregroundStyle(.red)
                    .textSelection(.enabled)
            }

            if !probe.browseResults.isEmpty {
                Text("browse results: \(probe.browseResults.joined(separator: ", "))")
                    .font(.system(.footnote, design: .monospaced))
                    .textSelection(.enabled)
            }

            Spacer()
        }
        .padding(20)
    }
}
