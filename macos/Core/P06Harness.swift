import AppKit
import Foundation

/// P06 headless harness.
///
/// Starts the pasteboard monitor without depending on SwiftUI's lazy tab creation, so a scripted
/// run always gets the same evidence. The app is still the bundled, code-signed binary; the
/// harness only controls when monitoring starts and when it exits.
///
/// Usage: `DroidBridge --p06-harness [--p06-self-write] [--p06-seconds N]`
@MainActor
enum P06Harness {

    static func run(_ arguments: [String]) -> Int32 {
        var seconds: Double = 120
        if let index = arguments.firstIndex(of: "--p06-seconds"),
           index + 1 < arguments.count,
           let parsed = Double(arguments[index + 1]) {
            seconds = parsed
        }

        let app = NSApplication.shared
        app.setActivationPolicy(.regular)
        app.activate(ignoringOtherApps: true)

        let model = PasteboardModel(
            autoStart: true,
            selfWriteAfter: arguments.contains("--p06-self-write") ? 5 : nil
        )
        let deadline = Date().addingTimeInterval(seconds)
        while Date() < deadline {
            RunLoop.main.run(until: Date().addingTimeInterval(0.2))
        }
        model.stop()
        return 0
    }
}
