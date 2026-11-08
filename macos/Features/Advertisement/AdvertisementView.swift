import SwiftUI

/// P02 Path A step 1 — the Mac advertises and echoes.
///
/// The screen exists to produce evidence: the service type, the ephemeral port, the session id,
/// and every inbound connection with its byte counts. A prototype whose measurements are only in
/// a log is hard to defend, so they are on screen too.
struct AdvertisementView: View {

    /// Owned by the app, not by this view: SwiftUI builds TabView content lazily, so a
    /// @StateObject here would not exist until the tab was first selected.
    @ObservedObject var model: AdvertisementModel

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("P02 — Mac advertiser")
                .font(.title2).bold()

            Text("The Mac advertises and listens; Android dials out through the system NSD picker. "
                 + "This is the least-privilege direction (ADR-010).")
                .font(.callout)
                .foregroundStyle(.secondary)

            HStack(spacing: 12) {
                Button(model.isAdvertising ? "Stop" : "Start advertising") {
                    model.toggle()
                }
                .keyboardShortcut(.defaultAction)

                Text(model.statusText)
                    .font(.system(.callout, design: .monospaced))
                    .foregroundStyle(model.isAdvertising ? .green : .secondary)
            }

            GroupBox("Service") {
                VStack(alignment: .leading, spacing: 4) {
                    row("type", ServiceType.droidBridge)
                    row("port", model.port.map(String.init) ?? "—")
                    row("session id", model.sessionId ?? "—")
                    row("entitlement", "network.server (App Sandbox)")
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(4)
            }

            GroupBox("Events") {
                ScrollView {
                    VStack(alignment: .leading, spacing: 2) {
                        if model.events.isEmpty {
                            Text("Nothing yet. Start advertising, then connect from Android.")
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
                .frame(minHeight: 160)
            }

            Text("Echo only: bytes are returned verbatim so Android can compare what it sent. "
                 + "No TLS and no framing — those are P03.")
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
                .frame(width: 90, alignment: .leading)
            Text(value)
                .font(.system(.caption, design: .monospaced))
                .textSelection(.enabled)
        }
    }
}

/// Bridges the advertiser's callbacks into observable state.
@MainActor
final class AdvertisementModel: ObservableObject {

    @Published private(set) var state: AdvertiseState = .idle
    @Published private(set) var events: [String] = []
    @Published private(set) var sessionId: String?

    private let advertiser = ServiceAdvertiser()
    /// Bounded so a long session cannot grow the list without limit.
    private let maxEvents = 200

    init() {
        advertiser.onStateChange = { [weak self] newState in
            self?.state = newState
        }
        advertiser.onEvent = { [weak self] event in
            guard let self else { return }
            self.events.append(event)
            if self.events.count > self.maxEvents {
                self.events.removeFirst(self.events.count - self.maxEvents)
            }
        }

        // `--advertise-on-launch` starts advertising immediately.
        //
        // This exists so the prototype can be verified without a human clicking a button: the
        // evidence for P02 is "does Bonjour publish this service", which is observable from
        // outside the app. Default behaviour is unchanged -- advertising on launch would be wrong
        // for the product, where the user decides when the Mac is discoverable.
        if CommandLine.arguments.contains("--advertise-on-launch") {
            sessionId = ServiceTxtRecord.randomSessionId()
            advertiser.start()
        }
    }

    var isAdvertising: Bool { state.isAdvertising }

    var port: UInt16? {
        if case .advertising(_, let port) = state { return port }
        return nil
    }

    var statusText: String {
        switch state {
        case .idle: return "idle"
        case .advertising(let name, let port): return "advertising \(name) on port \(port)"
        case .failed(let reason): return "failed: \(reason)"
        }
    }

    func toggle() {
        if isAdvertising {
            advertiser.stop()
        } else {
            sessionId = ServiceTxtRecord.randomSessionId()
            advertiser.start()
        }
    }
}
