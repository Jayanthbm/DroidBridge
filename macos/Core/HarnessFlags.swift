import Foundation

/// Development-only switches the shipping app reads from the command line or the environment.
///
/// Every accessor is compiled to a constant in a Release build, so a shipped DroidBridge cannot arm
/// the P08 fault hook, turn off `preventsCapture`, write raw video to disk, auto-confirm a pairing or
/// auto-accept a P17 pairing code even if the flag is passed. Debug builds keep the exact behaviour
/// the P03-P17 evidence runs use, so the prototypes stay reproducible.
///
/// The flag literals remain in the binary (they are call-site arguments), so a `strings` check is not
/// the acceptance test. The acceptance test launches the Release build with the flags and observes
/// that nothing turns on; see the Phase 9 candidate notes in `Docs/PROGRESS_LOCAL.md`.
enum HarnessFlags {
    /// True in Debug when `flag` is on the command line; always false in Release.
    static func isOn(_ flag: String) -> Bool {
        #if DEBUG
        return CommandLine.arguments.contains(flag)
        #else
        return false
        #endif
    }

    /// The argument following `flag`, or nil when absent (always nil in Release).
    static func value(after flag: String) -> String? {
        #if DEBUG
        guard let index = CommandLine.arguments.firstIndex(of: flag),
              index + 1 < CommandLine.arguments.count else { return nil }
        return CommandLine.arguments[index + 1]
        #else
        return nil
        #endif
    }

    /// The environment value for `name`, or nil (always nil in Release).
    static func environment(_ name: String) -> String? {
        #if DEBUG
        return ProcessInfo.processInfo.environment[name]
        #else
        return nil
        #endif
    }
}
