import XCTest
@testable import DroidBridge

/// P02: the parts of the advertiser that can be asserted without a network.
///
/// The live half (does Bonjour actually publish, does Android actually find it) needs a device on
/// Android 17 and is recorded as pending in the P02 report. What is testable here is the part that
/// silently breaks discovery: the service type string and the TXT record bytes.
final class ServiceAdvertisementTests: XCTestCase {

    func testServiceTypeMatchesTheDeclaredBonjourType() {
        // Must match NSBonjourServices in Info.plist and ADR-022. A mismatch here means discovery
        // finds nothing, with no error anywhere to explain why.
        XCTAssertEqual(ServiceType.droidBridge, "_droidbridge-dev._tcp")
        XCTAssertTrue(ServiceType.droidBridge.hasPrefix("_"))
        XCTAssertTrue(ServiceType.droidBridge.hasSuffix("._tcp"))

        // Bonjour limits the service name to 15 characters, excluding the leading underscore and
        // the trailing "._tcp".
        let name = ServiceType.droidBridge
            .replacingOccurrences(of: "._tcp", with: "")
            .dropFirst()
        XCTAssertLessThanOrEqual(name.count, 15, "Bonjour service names are limited to 15 characters")
    }

    func testServiceTypeIsDeclaredInInfoPlist() throws {
        // Reads the host app's real info dictionary, so changing one without the other fails here
        // rather than on a device. The test bundle is hosted by the app (TEST_HOST is set), so
        // Bundle.main is the app; its own generated Info.plist is not the one that ships.
        //
        // Note: `url(forResource: "Info", withExtension: "plist")` does not work for this --
        // a bundle looks in Contents/Resources, and the app's Info.plist is at Contents/.
        let services = Bundle.main.object(forInfoDictionaryKey: "NSBonjourServices") as? [String]

        XCTAssertNotNil(services, "NSBonjourServices must be declared or Bonjour is blocked")
        XCTAssertTrue(
            services?.contains(ServiceType.droidBridge) == true,
            "NSBonjourServices must list \(ServiceType.droidBridge), found \(services ?? [])",
        )
    }

    func testTxtRecordEncodesVersionAndSessionId() {
        let record = ServiceTxtRecord(version: 1, sessionId: "abc123")
        let data = ServiceAdvertiser.txtRecordData(record)

        // Bonjour TXT is length-prefixed key=value pairs, sorted for determinism.
        // "sid=abc123" is 10 bytes, "v=1" is 3.
        var expected = Data()
        expected.append(UInt8(10)); expected.append(contentsOf: Array("sid=abc123".utf8))
        expected.append(UInt8(3)); expected.append(contentsOf: Array("v=1".utf8))

        XCTAssertEqual(data, expected)
    }

    func testTxtRecordDictionaryCarriesTheDocumentedKeys() {
        let record = ServiceTxtRecord(version: 1, sessionId: "deadbeef")
        XCTAssertEqual(record.dictionary["v"], "1")
        XCTAssertEqual(record.dictionary["sid"], "deadbeef")
        XCTAssertEqual(record.dictionary.count, 2)
    }

    func testSessionIdsAreDistinct() {
        // A repeated session id would make a stale advertisement indistinguishable from a live one.
        let ids = Set((0..<64).map { _ in ServiceTxtRecord.randomSessionId() })
        XCTAssertEqual(ids.count, 64, "session ids must not collide")
        XCTAssertEqual(ids.first?.count, 32, "16 random bytes hex encoded is 32 characters")
    }

    func testAdvertiseStateReportsAdvertisingCorrectly() {
        XCTAssertFalse(AdvertiseState.idle.isAdvertising)
        XCTAssertTrue(AdvertiseState.advertising(name: "DroidBridge", port: 5000).isAdvertising)
        XCTAssertFalse(AdvertiseState.failed("nope").isAdvertising)
    }
}
