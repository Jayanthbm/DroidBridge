import XCTest
@testable import DroidBridge

/// PeerPinStore is a set of confirmed pins keyed by nothing but the pins themselves (ADR-054):
/// a pin is the digest of the peer's SPKI, so trust is membership and the label the earlier
/// per-peer-id model required is gone. These tests pin the behaviours the session code relies on.
final class PeerPinStoreTests: XCTestCase {

    private var store: PeerPinStore!

    override func setUp() {
        super.setUp()
        // A test-only service keeps the suite from touching the pins the running app confirmed.
        store = PeerPinStore(service: "com.droidbridge.tests.peer-pins")
        store.forgetAll()
    }

    override func tearDown() {
        store.forgetAll()
        super.tearDown()
    }

    private let pinA = String(repeating: "a", count: 64)
    private let pinB = String(repeating: "b", count: 64)

    func testAConfirmedPinIsTrusted() throws {
        XCTAssertFalse(store.isConfirmed(pinA))
        try store.confirm(pinA)
        XCTAssertTrue(store.isConfirmed(pinA))
    }

    func testTwoPeersAreBothTrustedWithoutFighting() throws {
        // The regression this store exists for: two phones must not evict each other's pin.
        try store.confirm(pinA)
        try store.confirm(pinB)
        XCTAssertTrue(store.isConfirmed(pinA))
        XCTAssertTrue(store.isConfirmed(pinB))
    }

    func testForgetAllLeavesNothingTrusted() throws {
        try store.confirm(pinA)
        try store.confirm(pinB)
        store.forgetAll()
        XCTAssertFalse(store.isConfirmed(pinA))
        XCTAssertFalse(store.isConfirmed(pinB))
    }

    func testAMalformedPinIsRefused() {
        XCTAssertThrowsError(try store.confirm("not-a-pin"))
        XCTAssertFalse(store.isConfirmed("not-a-pin"))
    }

    func testLegacyPerPeerIdItemsMigrateIntoTheSet() throws {
        // Write items the way the pre-ADR-054 store did: one item per peer id label.
        let legacyPins = [
            "android-p03": String(repeating: "c", count: 64),
            "android-p08": String(repeating: "d", count: 64),
        ]
        for (account, pin) in legacyPins {
            var query: [String: Any] = [
                kSecClass as String: kSecClassGenericPassword,
                kSecAttrService as String: "com.droidbridge.tests.peer-pins",
                kSecAttrAccount as String: account,
                kSecValueData as String: Data(pin.utf8),
            ]
            query[kSecUseDataProtectionKeychain as String] = false
            let status = SecItemAdd(query as CFDictionary, nil)
            XCTAssertEqual(status, errSecSuccess, "setup: legacy item could not be written")
        }
        defer {
            for (account, _) in legacyPins {
                var query: [String: Any] = [
                    kSecClass as String: kSecClassGenericPassword,
                    kSecAttrService as String: "com.droidbridge.tests.peer-pins",
                    kSecAttrAccount as String: account,
                ]
                query[kSecUseDataProtectionKeychain as String] = false
                SecItemDelete(query as CFDictionary)
            }
        }

        let pins = store.confirmedPins()
        XCTAssertTrue(pins.contains(legacyPins["android-p03"]!))
        XCTAssertTrue(pins.contains(legacyPins["android-p08"]!))

        // Migration deletes the legacy items, so a second read is stable and forget works.
        XCTAssertEqual(store.confirmedPins(), pins)
        store.forgetAll()
        XCTAssertFalse(store.isConfirmed(legacyPins["android-p03"]!))
    }
}
