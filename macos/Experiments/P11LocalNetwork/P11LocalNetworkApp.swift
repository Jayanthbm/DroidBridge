// Copyright (C) 2026 DroidBridge contributors.
// SPDX-License-Identifier: none — internal feasibility experiment, not for distribution.
//
// P11 — isolated macOS local-network-privacy probe. Explicit controls only.
//
// The termination hold matters here: TN3179 records that macOS fails to display the local network
// alert for a process with a very short lifespan (FB16131937), and AppKit's automatic termination
// otherwise ends this idle window well inside the window where the alert would be presented.
import AppKit
import SwiftUI

@main
struct P11LocalNetworkApp: App {
    @NSApplicationDelegateAdaptor(P11AppDelegate.self) private var appDelegate
    @StateObject private var probe = LocalNetworkProbe()

    var body: some Scene {
        WindowGroup("P11 Local Network") {
            P11LocalNetworkView(probe: probe)
                .frame(minWidth: 620, minHeight: 460)
                .task {
                    appDelegate.probe = probe
                    probe.runHarnessIfRequested()
                }
        }
    }
}

final class P11AppDelegate: NSObject, NSApplicationDelegate {
    var probe: LocalNetworkProbe?

    func applicationDidFinishLaunching(_ notification: Notification) {
        ProcessInfo.processInfo.disableAutomaticTermination("P11 local-network probe must stay alive to observe the alert")
        ProcessInfo.processInfo.disableSuddenTermination()
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        true
    }
}
