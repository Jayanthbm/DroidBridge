import AppKit
import SwiftUI

/// The viewer window, hosted by AppKit rather than a SwiftUI scene.
///
/// A second `Window`/`WindowGroup` scene made the system launch *that* scene and skip the main
/// window entirely (measured 2026-11-26, macOS 27.2.1), which also meant the launch bootstrap never
/// ran. One scene for the main window plus an AppKit window for the viewer is deterministic, and it
/// gives the viewer the chrome it wants: hidden title bar, black background, its own full-screen
/// state.
@MainActor
enum ViewerWindow {
    private static var window: NSWindow?

    static func show(model: LiveMirrorReceiver) {
        if let existing = window {
            existing.makeKeyAndOrderFront(nil)
            return
        }
        let hosting = NSHostingView(rootView: ViewerWindowView(model: model))
        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 380, height: 780),
            styleMask: [.titled, .closable, .resizable, .miniaturizable, .fullSizeContentView],
            backing: .buffered,
            defer: false
        )
        window.title = "Screen"
        window.titlebarAppearsTransparent = true
        window.titleVisibility = .hidden
        window.backgroundColor = .black
        window.isReleasedWhenClosed = false
        window.contentView = hosting
        window.minSize = NSSize(width: 260, height: 420)
        window.center()
        window.makeKeyAndOrderFront(nil)
        Self.window = window
    }
}
