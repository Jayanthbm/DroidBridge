import SwiftUI

/// Phase 0 dashboard: the environment baseline and nothing else.
struct DashboardView: View {
    let environment: EnvironmentSummary

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("DroidBridge")
                .font(.largeTitle.weight(.semibold))

            Text("Phase 0 shell — environment baseline only.")
                .font(.callout)
                .foregroundStyle(.secondary)

            Divider().padding(.vertical, 4)

            row("macOS", environment.macOSVersion)
            row("Architecture", environment.architecture)
            row("App version", environment.appVersion)
            row("Sandboxed", environment.isSandboxed ? "yes" : "no")

            Spacer()
        }
        .padding(24)
        .frame(minWidth: 420, minHeight: 320, alignment: .topLeading)
    }

    private func row(_ label: String, _ value: String) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            Text(label)
                .frame(width: 110, alignment: .leading)
                .foregroundStyle(.secondary)
            Text(value)
                .textSelection(.enabled)
        }
        .font(.body)
    }
}
