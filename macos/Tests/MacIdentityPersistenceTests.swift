import XCTest
import Security
@testable import DroidBridge

final class MacIdentityPersistenceTests: XCTestCase {

    func testIdentityPersistsAcrossLoadsFromSeparateObjects() throws {
        let keychain = try makeTemporaryKeychain()
        defer { _ = SecKeychainDelete(keychain) }
        let tag = Data("com.droidbridge.tests.identity.\(UUID().uuidString)".utf8)

        let first = try MacIdentity.generate(in: keychain, applicationTag: tag)
        let firstPin = first.pin
        XCTAssertEqual(first.subjectPublicKeyInfo.count, 91)
        XCTAssertEqual(first.pin.count, 64)
        withExtendedLifetime(first) {}

        let second = try MacIdentity.generate(in: keychain, applicationTag: tag)
        XCTAssertEqual(second.pin, firstPin, "A second identity load must reuse the stored key/certificate.")
    }

    func testIncompleteExistingKeyFailsClosedInsteadOfGeneratingReplacement() throws {
        let keychain = try makeTemporaryKeychain()
        defer { _ = SecKeychainDelete(keychain) }
        let tag = Data("com.droidbridge.tests.incomplete.\(UUID().uuidString)".utf8)

        var error: Unmanaged<CFError>?
        let keyAttributes: [String: Any] = [
            kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrKeySizeInBits as String: 256,
            kSecAttrIsPermanent as String: true,
            kSecPrivateKeyAttrs as String: [
                kSecAttrIsPermanent as String: true,
                kSecAttrApplicationTag as String: tag,
                kSecUseKeychain as String: keychain,
            ],
        ]
        let orphan = try XCTUnwrap(SecKeyCreateRandomKey(keyAttributes as CFDictionary, &error))
        withExtendedLifetime(orphan) {}

        XCTAssertThrowsError(try MacIdentity.generate(in: keychain, applicationTag: tag)) { error in
            guard case MacIdentityError.persistentIdentityIncomplete(0) = error else {
                return XCTFail("Expected fail-closed incomplete identity error, got \(error)")
            }
        }
    }

    private func makeTemporaryKeychain() throws -> SecKeychain {
        let path = NSTemporaryDirectory() + "droidbridge-identity-test-\(UUID().uuidString).keychain"
        var keychain: SecKeychain?
        let status = SecKeychainCreate(path, 0, "", false, nil, &keychain)
        guard status == errSecSuccess, let keychain else {
            throw MacIdentityError.keychainUnavailable(status)
        }
        return keychain
    }
}
