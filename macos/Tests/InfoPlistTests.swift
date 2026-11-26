import XCTest

/// Guards the privacy strings the app cannot work without.
///
/// TCC does not deny a missing usage description, it aborts the process
/// (`__TCC_CRASHING_DUE_TO_PRIVACY_VIOLATION__`). That is how the Bluetooth keyboard was lost: the
/// entitlement granted `com.apple.security.device.bluetooth` while `Info.plist` had no
/// `NSBluetoothAlwaysUsageDescription`, and the first `IOBluetooth` call killed the app (crash reports
/// 2026-11-25 and 2026-11-26). The strings are asserted here so a future edit cannot remove one and
/// leave the failure to a crash report.
///
/// These tests run hosted by DroidBridge.app, so `Bundle.main` is the application bundle.
final class InfoPlistTests: XCTestCase {
    private var appInfo: [String: Any] {
        Bundle.main.infoDictionary ?? [:]
    }

    func testBluetoothUsageDescriptionExplainsTheKeyboard() {
        let text = (appInfo["NSBluetoothAlwaysUsageDescription"] as? String ?? "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        XCTAssertFalse(text.isEmpty, "TCC aborts the app on any IOBluetooth access without this string")
        XCTAssertTrue(text.lowercased().contains("bluetooth"))
        XCTAssertTrue(text.lowercased().contains("keyboard"))
    }

    func testLocalNetworkUsageDescriptionIsDeclared() {
        let text = (appInfo["NSLocalNetworkUsageDescription"] as? String ?? "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        XCTAssertFalse(text.isEmpty, "Bonjour advertising needs the Local Network prompt to be explained")
    }

    func testBonjourServicesMatchTheDevelopmentProtocol() {
        let services = appInfo["NSBonjourServices"] as? [String] ?? []
        XCTAssertEqual(services, ["_droidbridge-dev._tcp"], "NETWORK_PROTOCOL.md and Info.plist must not drift")
    }

}
