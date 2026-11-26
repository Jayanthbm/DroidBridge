import SwiftUI

/// The main window: one device, one column of states, one place to set things up.
///
/// Product shell for the P08/P03 path that the prototype tabs proved. The lab surfaces (advertiser,
/// pasteboard monitor, P07 fixture, P08 counter view, HID remote) stay reachable under Diagnostics
/// so evidence runs do not need a second build, but they are no longer the first thing anyone sees.
struct MainWindowView: View {
    let appDelegate: BridgeAppDelegate
    @ObservedObject var liveMirror: LiveMirrorReceiver
    @ObservedObject var advertisement: AdvertisementModel
    @ObservedObject var pasteboard: PasteboardModel
    @ObservedObject var video: VideoReceiverModel
    @ObservedObject var hidPeripheral: ClassicHIDPeripheral

    @State private var selection: SidebarItem? = .overview

    var body: some View {
        NavigationSplitView {
            VStack(spacing: 0) {
                deviceCard
                List(SidebarItem.allCases, selection: $selection) { item in
                    Label(item.title, systemImage: item.symbol).tag(item)
                }
                .listStyle(.sidebar)
            }
            .navigationSplitViewColumnWidth(min: 190, ideal: 210, max: 250)
        } detail: {
            detail
                .frame(minWidth: 480, minHeight: 420)
        }
        .onAppear {
            AppBoot.run(
                appDelegate: appDelegate,
                liveMirror: liveMirror,
                pasteboard: pasteboard,
                hidPeripheral: hidPeripheral
            )
            // Test affordance (Debug only): a scripted run cannot click, and the viewer is a second
            // window, so evidence runs need a way to open it without a human.
            if HarnessFlags.isOn("--open-viewer") {
                ViewerWindow.show(model: liveMirror)
            }
        }
    }

    @ViewBuilder
    private var detail: some View {
        switch selection ?? .overview {
        case .overview:
            OverviewPane(model: liveMirror, openViewer: { ViewerWindow.show(model: liveMirror) })
        case .clipboard:
            ClipboardPane(model: liveMirror)
        case .screen:
            ScreenPane(model: liveMirror, openViewer: { ViewerWindow.show(model: liveMirror) })
        case .audio:
            AudioPane(model: liveMirror)
        case .settings:
            SettingsPane()
        case .diagnostics:
            DiagnosticsPane(
                advertisement: advertisement,
                pasteboard: pasteboard,
                video: video,
                liveMirror: liveMirror,
                hidPeripheral: hidPeripheral
            )
        }
    }

    private var deviceCard: some View {
        VStack(alignment: .leading, spacing: 3) {
            // The phone names itself in `device.info` after every session reaches CONNECTED. Until
            // that message has arrived (first launch, or a phone that has not been updated) the card
            // says exactly what is known rather than inventing a name.
            Text(liveMirror.deviceInfo?.displayName ?? "Android device").font(.headline)
            if let info = liveMirror.deviceInfo {
                Text(info.detailLine)
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
                    .lineLimit(1)
            }
            HStack(spacing: 6) {
                Circle().fill(liveMirror.state.dotColor).frame(width: 7, height: 7)
                Text(liveMirror.state.shortLine)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
            }
            if let pin = liveMirror.peerPin, liveMirror.state == .awaitingConfirmation {
                Text("code \(liveMirror.pairingCode.map(ShortAuthenticationCode.formatForDisplay) ?? "…")")
                    .font(.system(.caption, design: .monospaced))
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 16)
        .padding(.top, 12)
        .padding(.bottom, 8)
    }
}

enum SidebarItem: String, CaseIterable, Identifiable {
    case overview, clipboard, screen, audio, settings, diagnostics

    var id: String { rawValue }

    var title: String {
        switch self {
        case .overview: return "Overview"
        case .clipboard: return "Clipboard"
        case .screen: return "Screen"
        case .audio: return "Audio"
        case .settings: return "Settings"
        case .diagnostics: return "Diagnostics"
        }
    }

    var symbol: String {
        switch self {
        case .overview: return "rectangle.grid.1x2"
        case .clipboard: return "doc.on.clipboard"
        case .screen: return "display"
        case .audio: return "speaker.wave.2"
        case .settings: return "gearshape"
        case .diagnostics: return "stethoscope"
        }
    }
}

private struct PaneTitle: View {
    let title: String
    let subtitle: String

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).font(.title2).bold()
            Text(subtitle).font(.callout).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct StateRow: View {
    let label: String
    let value: String

    var body: some View {
        HStack {
            Text(label).foregroundStyle(.secondary)
            Spacer()
            Text(value).multilineTextAlignment(.trailing)
        }
        .font(.body)
        .padding(.vertical, 6)
    }
}

/// Connected state, the code comparison, and the two actions that matter.
private struct OverviewPane: View {
    @ObservedObject var model: LiveMirrorReceiver
    let openViewer: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            PaneTitle(title: model.state.headline, subtitle: model.state.longLine(model: model))

            if model.state == .awaitingConfirmation {
                VStack(alignment: .leading, spacing: 10) {
                    Text(model.pairingCode.map(ShortAuthenticationCode.formatForDisplay) ?? "…")
                        .font(.system(size: 34, weight: .semibold, design: .monospaced))
                    Text("Check that the phone shows the same code, then confirm.")
                        .font(.callout)
                    HStack {
                        Button("Confirm pairing") { model.confirmPairing() }.keyboardShortcut(.defaultAction)
                        Button("Reject") { model.rejectPairing() }
                    }
                }
                .padding(16)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(.quaternary.opacity(0.4), in: RoundedRectangle(cornerRadius: 10))
            }

            HStack(spacing: 10) {
                Button("Send clipboard now") { _ = model.clipboard.sendLocalClipboard() }
                    .disabled(!model.state.isLive)
                Button("Share this screen") { openViewer() }
                if !model.state.isLive {
                    Button("Start listening") { model.startListening() }
                }
            }

            Divider()

            StateRow(label: "Clipboard", value: model.clipboardStatus)
            StateRow(label: "Screen", value: model.screenLine)
            StateRow(label: "Audio", value: model.audioStatus)
            StateRow(label: "Pairing", value: model.pairingLine)

            if !model.state.isLive {
                Text("The phone connects to this Mac. Open Screen → Share this screen from the phone, " +
                     "or use Find the Mac in the DroidBridge app.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Spacer()
        }
        .padding(24)
    }
}

private struct ClipboardPane: View {
    @ObservedObject var model: LiveMirrorReceiver

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            PaneTitle(title: "Clipboard", subtitle: "Origin-tagged text, pushed both ways over this session.")

            Text(model.clipboardStatus)
                .font(.system(.callout, design: .monospaced))
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(10)
                .background(.quaternary.opacity(0.35), in: RoundedRectangle(cornerRadius: 8))

            HStack(spacing: 10) {
                Button("Send clipboard now") { _ = model.clipboard.sendLocalClipboard() }
                    .disabled(!model.state.isLive)
                Toggle("Pause sync", isOn: Binding(
                    get: { model.clipboard.paused },
                    set: { paused in
                        model.clipboard.paused = paused
                        if paused { model.clipboard.stopPolling() } else { model.clipboard.startPolling() }
                    }
                ))
                .toggleStyle(.switch)
            }

            Text("Text is kept in memory only; nothing is written to disk or to a log.")
                .font(.caption)
                .foregroundStyle(.secondary)

            Spacer()
        }
        .padding(24)
    }
}

private struct ScreenPane: View {
    @ObservedObject var model: LiveMirrorReceiver
    let openViewer: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            PaneTitle(title: "Screen", subtitle: "The phone's display, decoded in a window of its own.")

            StateRow(label: "State", value: model.state.rawValue)
            StateRow(label: "Format", value: model.streamSize.map { "\(Int($0.width))×\(Int($0.height))" } ?? "not announced")
            StateRow(label: "Received", value: "\(model.framesReceived) frames, \(model.framesDropped) dropped")

            HStack(spacing: 10) {
                Button("Open viewer") { openViewer() }
                Button("Request keyframe") { model.requestKeyframe() }.disabled(!model.state.isSharing)
                Button("Stop sharing") { model.requestStopSharing() }.disabled(!model.state.isSharing)
                Button("Stop listening") { model.stop() }.disabled(!model.state.isLive)
            }

            Text("The viewer draws nothing but the stream and a fading status line. Protected screens " +
                 "arrive black by design (P10).")
                .font(.caption)
                .foregroundStyle(.secondary)

            Spacer()
        }
        .padding(24)
    }
}

private struct AudioPane: View {
    @ObservedObject var model: LiveMirrorReceiver

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            PaneTitle(title: "Audio", subtitle: "AAC-LC from the phone, played with the video.")

            Text(model.audioStatus).font(.system(.callout, design: .monospaced))
            Toggle("Mute", isOn: Binding(
                get: { model.audio.muted },
                set: { model.audio.muted = $0 }
            ))
            .toggleStyle(.switch)

            Text("Quality is the prototype's 44.1 kHz stereo 128 kbit/s (ADR-057), not a final setting.")
                .font(.caption)
                .foregroundStyle(.secondary)

            Spacer()
        }
        .padding(24)
    }
}

private struct SettingsPane: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            PaneTitle(title: "Settings", subtitle: "Nothing to configure yet, and no toggle that pretends otherwise.")

            StateRow(label: "Version", value: EnvironmentSummary.current().appVersion)
            StateRow(label: "Service name", value: "DroidBridge-P08-Mac")

            Text("Pinned identity, service name and port become configurable with the Phase 3 settings " +
                 "surface. Until then they are fixed so the phone's discovery keeps resolving this Mac.")
                .font(.caption)
                .foregroundStyle(.secondary)

            Spacer()
        }
        .padding(24)
    }
}

/// The prototype surfaces, kept reachable so a scripted evidence run needs only this build.
private struct DiagnosticsPane: View {
    @ObservedObject var advertisement: AdvertisementModel
    @ObservedObject var pasteboard: PasteboardModel
    @ObservedObject var video: VideoReceiverModel
    @ObservedObject var liveMirror: LiveMirrorReceiver
    @ObservedObject var hidPeripheral: ClassicHIDPeripheral

    @State private var tab = 0

    var body: some View {
        TabView(selection: $tab) {
            DashboardView(environment: EnvironmentSummary.current())
                .tabItem { Label("Environment", systemImage: "info.circle") }
                .tag(0)
            AdvertisementView(model: advertisement)
                .tabItem { Label("Advertiser", systemImage: "antenna.radiowaves.left.and.right") }
                .tag(1)
            PasteboardView(model: pasteboard)
                .tabItem { Label("Pasteboard", systemImage: "doc.on.clipboard") }
                .tag(2)
            VideoView(model: video)
                .tabItem { Label("Video fixture", systemImage: "play.rectangle") }
                .tag(3)
            LiveMirrorView(model: liveMirror)
                .tabItem { Label("Mirror", systemImage: "display") }
                .tag(4)
            HIDRemoteView(peripheral: hidPeripheral)
                .tabItem { Label("HID", systemImage: "keyboard") }
                .tag(5)
        }
        .padding(8)
    }
}

extension LiveMirrorReceiver.State {
    var isLive: Bool {
        switch self {
        case .listening, .awaitingConfirmation, .connected, .streaming: return true
        case .idle, .stopped, .failed: return false
        }
    }

    var isSharing: Bool { self == .streaming }

    var dotColor: Color {
        switch self {
        case .streaming: return .green
        case .connected: return .green.opacity(0.7)
        case .awaitingConfirmation: return .orange
        case .listening: return .yellow
        case .failed: return .red
        case .idle, .stopped: return .secondary
        }
    }

    var headline: String {
        switch self {
        case .idle: return "Not listening"
        case .listening: return "Waiting for the phone"
        case .awaitingConfirmation: return "Compare the code"
        case .connected: return "Connected"
        case .streaming: return "Sharing"
        case .stopped: return "Stopped"
        case .failed: return "Failed"
        }
    }

    var shortLine: String {
        switch self {
        case .idle: return "not listening"
        case .listening: return "waiting for the phone"
        case .awaitingConfirmation: return "compare the code"
        case .connected: return "connected, not sharing"
        case .streaming: return "sharing"
        case .stopped: return "stopped"
        case .failed: return "failed"
        }
    }

    @MainActor
    func longLine(model: LiveMirrorReceiver) -> String {
        switch self {
        case .idle: return "Press Start listening, then connect from the DroidBridge app on the phone."
        case .listening: return "Listening on the local network. The phone finds this Mac by name."
        case .awaitingConfirmation: return "The phone is asking to pair. Confirm only if both screens show the same code."
        case .connected: return "Paired and authenticated. Start sharing from the phone, or open the viewer."
        case .streaming: return model.screenLine + " — decoded from the phone's encoder."
        case .stopped: return "The session ended. Start listening again to accept a new connection."
        case .failed: return "The listener could not start; see Diagnostics for the reason."
        }
    }
}

@MainActor
extension LiveMirrorReceiver {
    /// One line about the video path, used by the overview and the viewer HUD.
    var screenLine: String {
        guard let size = streamSize, state == .streaming else { return "not sharing" }
        let fps = measuredFPS > 0 ? String(format: "%.0f fps", measuredFPS) : "… fps"
        return "\(Int(size.width))×\(Int(size.height)) · \(fps)"
    }

    var pairingLine: String {
        switch state {
        case .awaitingConfirmation: return "confirm the code"
        default: return peerPin == nil ? "not paired" : "stored, silent reconnects"
        }
    }
}
