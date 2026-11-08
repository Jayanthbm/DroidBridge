import XCTest
@testable import DroidBridge

/// Phase 0 tests: the shell's only logic is the environment summary, so that is what is tested.
/// Anything that needs the network, the pasteboard or a projection belongs to its own prototype.
final class EnvironmentSummaryTests: XCTestCase {

    func testCurrentReportsAVersionStringWithThreeComponents() {
        let summary = EnvironmentSummary.current()
        let components = summary.macOSVersion.split(separator: ".")

        XCTAssertEqual(components.count, 3, "macOS version should be major.minor.patch, was \(summary.macOSVersion)")
        XCTAssertNotNil(Int(components[0]), "major version should be numeric")
    }

    func testCurrentReportsAKnownArchitecture() {
        let summary = EnvironmentSummary.current()

        XCTAssertTrue(
            ["arm64", "x86_64"].contains(summary.architecture),
            "unexpected architecture \(summary.architecture)"
        )
    }

    func testAppVersionIsNotPlaceholder() {
        let summary = EnvironmentSummary.current()

        XCTAssertNotEqual(summary.appVersion, "unknown", "CFBundleShortVersionString is missing from Info.plist")
        XCTAssertFalse(summary.appVersion.isEmpty)
    }

    /// The sandbox entitlement is a hard requirement (ADR-026). A test bundle runs outside the
    /// app's sandbox, so this asserts the *mechanism* is readable rather than asserting `true`;
    /// the real check is `codesign -d --entitlements` in the Phase 0 audit.
    func testSandboxFlagIsDerivedFromTheEnvironment() {
        let summary = EnvironmentSummary.current()

        XCTAssertEqual(
            summary.isSandboxed,
            ProcessInfo.processInfo.environment["APP_SANDBOX_CONTAINER_ID"] != nil
        )
    }
}
