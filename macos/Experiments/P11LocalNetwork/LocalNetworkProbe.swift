// Copyright (C) 2026 DroidBridge contributors.
// SPDX-License-Identifier: none — internal feasibility experiment, not for distribution.
//
// P11 — isolated macOS local-network-privacy probe.
//
// The production app only listens: it advertises a Bonjour service and accepts inbound
// connections. Neither is gated by Local Network privacy on macOS, so the app never triggers the
// alert and never appears in Settings. This probe performs the two operations that ARE gated —
// browsing for a Bonjour service and dialling a LAN address — so the allowed/denied UX can be
// measured instead of assumed.
//
// Public API only: NSNetServiceBrowser and Network.framework. No permissions beyond the sandbox
// entitlements below, and nothing runs until a button is pressed or a harness flag is passed.
import Foundation
import Network
import os

@MainActor
final class LocalNetworkProbe: NSObject, ObservableObject {
    @Published private(set) var status: String = "idle — nothing has touched the network yet"
    @Published private(set) var browseResults: [String] = []
    @Published private(set) var lastError: String?

    private static let log = Logger(
        subsystem: "com.droidbridge.experiment.p11localnetwork",
        category: "P11LocalNetwork"
    )
    static let serviceType = "_droidbridge-dev._tcp"

    private var browser: NetServiceBrowser?
    private var found: Set<String> = []
    private var connection: NWConnection?
    private var harnessRan = false

    override init() {
        super.init()
    }

    private func note(_ text: String) {
        status = text
        print("[p11] \(text)")
        Self.log.info("[P11LocalNetwork] \(text, privacy: .public)")
    }

    // MARK: - Bonjour browse (gated operation #1)

    /// Browsing is the operation the product needs if the Mac ever discovers Android itself.
    func startBrowse() {
        guard browser == nil else { return }
        found.removeAll()
        browseResults = []
        lastError = nil
        note("browser starting for \(Self.serviceType)")

        let browser = NetServiceBrowser()
        browser.delegate = self
        self.browser = browser
        browser.searchForServices(ofType: Self.serviceType, inDomain: "local.")

        // A denial is silent at the API level (no results, no callback), so summarise explicitly.
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 8_000_000_000)
            guard self.browser != nil else { return }
            if self.found.isEmpty {
                self.note(
                    "browse finished with 0 services after 8s "
                        + "(expected when access is denied or the screen has no advertiser)"
                )
            } else {
                self.note("browse finished with \(self.found.count) service(s): \(self.found.sorted().joined(separator: ", "))")
            }
        }
    }

    func stopBrowse() {
        browser?.stop()
        browser = nil
        note("browser stopped")
    }

    // MARK: - Outbound connect (gated operation #2)

    /// Dialling a LAN address is gated even when nothing is listening on the far side: the
    /// permission decision happens before the connection attempt.
    func connect(to target: String) {
        let parts = target.split(separator: ":")
        guard parts.count == 2, let port = NWEndpoint.Port(String(parts[1])) else {
            lastError = "expected host:port, got '\(target)'"
            note("connect skipped: \(lastError ?? "")")
            return
        }
        let host = NWEndpoint.Host(String(parts[0]))
        lastError = nil
        note("connecting to \(target)")

        let connection = NWConnection(host: host, port: port, using: .tcp)
        self.connection = connection
        connection.stateUpdateHandler = { [weak self] state in
            Task { @MainActor in
                switch state {
                case .ready:
                    self?.note("connected to \(target) — local network access allowed")
                    connection.cancel()
                case .waiting(let error):
                    // Denial surfaces here: the connection waits instead of failing outright.
                    self?.lastError = Self.describe(error)
                    self?.note("waiting: \(Self.describe(error))")
                case .failed(let error):
                    self?.lastError = Self.describe(error)
                    self?.note("failed: \(Self.describe(error))")
                case .cancelled:
                    break
                default:
                    break
                }
            }
        }
        connection.start(queue: .main)

        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 10_000_000_000)
            if self.connection === connection, connection.state != .ready {
                self.note("connect probe inconclusive after 10s (state \(connection.state))")
                connection.cancel()
            }
        }
    }

    private static func describe(_ error: NWError) -> String {
        switch error {
        case .posix(let code):
            return "posix \(code.rawValue) (\(code))"
        case .dns(let code):
            return "dns \(code)"
        case .tls(let code):
            return "tls \(code)"
        @unknown default:
            return "\(error)"
        }
    }

    // MARK: - Automation harness

    /// `--harness-browse`            browse for the DroidBridge service type
    /// `--harness-connect host:port` dial a LAN address
    func runHarnessIfRequested() {
        guard !harnessRan else { return }
        harnessRan = true
        let arguments = CommandLine.arguments

        if arguments.contains("--harness-browse") {
            startBrowse()
        }
        if let index = arguments.firstIndex(of: "--harness-connect"), index + 1 < arguments.count {
            connect(to: arguments[index + 1])
        }
        if !arguments.contains("--harness-browse") && !arguments.contains("--harness-connect") {
            // Launched from Finder there are no arguments: browse anyway, because browsing is the
            // gated operation this experiment exists to measure. Nothing else runs by itself.
            note("launch without flags — starting the gated browse automatically")
            startBrowse()
        }
    }
}

extension LocalNetworkProbe: NetServiceBrowserDelegate {
    func netServiceBrowser(
        _ browser: NetServiceBrowser,
        didFind service: NetService,
        moreComing: Bool
    ) {
        let name = service.name
        Task { @MainActor in
            self.found.insert(name)
            self.browseResults = self.found.sorted()
            self.note("found: \(name)")
        }
    }

    func netServiceBrowser(
        _ browser: NetServiceBrowser,
        didRemove service: NetService,
        moreComing: Bool
    ) {
        let name = service.name
        Task { @MainActor in
            self.found.remove(name)
            self.browseResults = self.found.sorted()
            self.note("removed: \(name)")
        }
    }

    /// A denial is reported here rather than as a prompt-free empty result.
    func netServiceBrowser(
        _ browser: NetServiceBrowser,
        didNotSearch errorDict: [String: NSNumber]
    ) {
        let description = errorDict.description
        Task { @MainActor in
            self.lastError = description
            self.note("didNotSearch: \(description)")
        }
    }

    func netServiceBrowserDidStopSearch(_ browser: NetServiceBrowser) {
        Task { @MainActor in
            self.note("browser stopped searching")
        }
    }
}
