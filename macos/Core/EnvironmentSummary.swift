import Foundation

/// The host facts a test report or a bug report needs.
///
/// Mirrors `BuildInfo` on the Android side: one place that reads the platform, so a baseline
/// report quotes a single source instead of scattering `ProcessInfo` reads through the UI.
struct EnvironmentSummary: Equatable {
    let appVersion: String
    let macOSVersion: String
    let architecture: String
    let isSandboxed: Bool

    static func current() -> EnvironmentSummary {
        let version = ProcessInfo.processInfo.operatingSystemVersion
        let bundleVersion = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String

        return EnvironmentSummary(
            appVersion: bundleVersion ?? "unknown",
            macOSVersion: "\(version.majorVersion).\(version.minorVersion).\(version.patchVersion)",
            architecture: Self.architectureName(),
            isSandboxed: Self.isSandboxed()
        )
    }

    /// Reports the architecture the process is actually running as, not the host's capability.
    private static func architectureName() -> String {
        #if arch(arm64)
        return "arm64"
        #elseif arch(x86_64)
        return "x86_64"
        #else
        return "unknown"
        #endif
    }

    /// Reads the sandbox status from the environment rather than assuming the entitlement took
    /// effect. A build that silently lost `com.apple.security.app-sandbox` should say so.
    private static func isSandboxed() -> Bool {
        ProcessInfo.processInfo.environment["APP_SANDBOX_CONTAINER_ID"] != nil
    }
}
