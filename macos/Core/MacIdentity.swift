import Foundation
import Crypto
import Network
import Security
import X509
import os

/// The Mac's per-install identity (ADR-011, ADR-024).
///
/// A P-256 key with a self-signed certificate, generated on first run. macOS has no public API to
/// create a certificate, so this uses Apple's `swift-certificates` (ADR-024).
///
/// ## Why the pin is the public key, not the certificate
///
/// ADR-024 pins the peer by public-key digest, and the reason is worth restating because it is easy
/// to get wrong: a certificate is a container. The same key can be re-certified with new validity
/// dates without the identity changing. Pinning the certificate would force a re-pair for a renewal
/// that changed nothing security-relevant, and would train users to click through "identity
/// changed" warnings that are usually harmless — which is exactly how a real substitution gets
/// ignored. The digest is over the SubjectPublicKeyInfo, so it tracks the key alone.
///
/// ## The four measured facts this file depends on
///
/// Getting a `SecIdentity` on macOS is not documented end to end, and every plausible shortcut
/// fails. P03 measured the working path, and each step below is here because the obvious
/// alternative was tried and rejected:
///
/// 1. **The key must be generated into the keychain**, not imported. `SecKeyCreateWithData` accepts
///    an EC private key only as `x9.63 public point || raw scalar` (97 bytes for P-256) — raw
///    scalar, PKCS#8 and RFC 5915 `ECPrivateKey` are all rejected with `errSecParam` — but the
///    resulting `SecKey` still cannot be added to a keychain with `SecItemAdd`.
/// 2. **The certificate must be added to the same keychain** as the key, or the two never pair.
/// 3. **`SecIdentityCreateWithCertificate` must be passed a nil keychain**, with the target keychain
///    in the search list. Passing the keychain explicitly returns `errSecItemNotFound` even though
///    the identity exists.
/// 4. **The search list must be restored afterwards.** It is process-global and must never retain a
///    temporary/test keychain after identity resolution.
///
/// ## Storage
///
/// Personal-use builds keep the identity in the user's login Keychain (ADR-034), so the key and
/// certificate survive app relaunches without a restricted provisioning-profile entitlement. The
/// data-protection Keychain under ADR-025 remains the distribution target when a profile is
/// available. Keychain failures must fail closed; they must not silently create a different pin.
public final class MacIdentity {

    /// The signing key, as the keychain holds it.
    public let privateKey: SecKey

    /// The self-signed certificate presented to peers.
    public let certificate: SecCertificate

    /// The DER SubjectPublicKeyInfo, which is what both sides digest.
    public let subjectPublicKeyInfo: Data

    /// This identity's pin: SHA-256 over the SubjectPublicKeyInfo, lowercase hex.
    public let pin: String

    /// The identity, ready for `sec_protocol_options_set_local_identity`.
    public let secIdentity: SecIdentity

    /// The keychain holding the key and certificate.
    private let keychain: SecKeychain

    private let log = Logger(subsystem: "com.droidbridge.macos", category: "identity")

    private init(
        privateKey: SecKey,
        certificate: SecCertificate,
        spki: Data,
        secIdentity: SecIdentity,
        keychain: SecKeychain
    ) {
        self.privateKey = privateKey
        self.certificate = certificate
        self.subjectPublicKeyInfo = spki
        self.pin = MacIdentity.pin(ofSPKI: spki)
        self.secIdentity = secIdentity
        self.keychain = keychain
    }

    /// Loads the persistent personal-use identity or creates it on first launch.
    /// Missing items are created; all other Keychain errors or inconsistent existing items fail closed.
    public static func generate() throws -> MacIdentity {
        var keychainRef: SecKeychain?
        let status = SecKeychainCopyDefault(&keychainRef)
        guard status == errSecSuccess, let keychain = keychainRef else {
            throw MacIdentityError.keychainUnavailable(status)
        }
        return try loadOrCreate(in: keychain, applicationTag: persistentKeyApplicationTag)
    }

    /// Test seam: exercises the same persistence path in a caller-owned disposable keychain.
    static func generate(in keychain: SecKeychain, applicationTag: Data) throws -> MacIdentity {
        try loadOrCreate(in: keychain, applicationTag: applicationTag)
    }

    private static let persistentKeyApplicationTag = Data("com.droidbridge.macos.identity.p256.v2".utf8)

    private static func loadOrCreate(in keychain: SecKeychain, applicationTag: Data) throws -> MacIdentity {
        var query = keyQuery(in: keychain, applicationTag: applicationTag)
        query[kSecReturnRef as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne

        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound {
            return try create(in: keychain, applicationTag: applicationTag)
        }
        guard status == errSecSuccess, let result,
              CFGetTypeID(result) == SecKeyGetTypeID() else {
            throw MacIdentityError.keychain(status == errSecSuccess ? errSecDecode : status)
        }
        let privateKey = unsafeBitCast(result, to: SecKey.self)

        let matchingCertificates = try certificates(matching: privateKey, in: keychain)
        guard matchingCertificates.count == 1, let certificate = matchingCertificates.first else {
            throw MacIdentityError.persistentIdentityIncomplete(matchingCertificates.count)
        }
        let identity = try identity(for: certificate, in: keychain)
        let spki = subjectPublicKeyInfo(of: certificate)
        guard !spki.isEmpty else { throw MacIdentityError.certificateRejectedBySecurityFramework }
        return MacIdentity(
            privateKey: privateKey,
            certificate: certificate,
            spki: spki,
            secIdentity: identity,
            keychain: keychain
        )
    }

    private static func create(in keychain: SecKeychain, applicationTag: Data) throws -> MacIdentity {
        let secKey = try generateKey(in: keychain, applicationTag: applicationTag)
        let cryptoKey = try cryptoKitKey(from: secKey)
        let certDER = try selfSignedCertificateDER(for: cryptoKey)
        guard let secCert = SecCertificateCreateWithData(nil, Data(certDER) as CFData) else {
            throw MacIdentityError.certificateRejectedBySecurityFramework
        }

        // Explicit item class is required for the legacy file-based Keychain. Omitting it can leave
        // a private-key item without its certificate, which makes persistent loads fail closed.
        let certAttrs: [String: Any] = [
            kSecClass as String: kSecClassCertificate,
            kSecAttrLabel as String: "DroidBridge",
            kSecValueRef as String: secCert,
            kSecUseKeychain as String: keychain,
            kSecUseDataProtectionKeychain as String: false,
        ]
        let certStatus = SecItemAdd(certAttrs as CFDictionary, nil)
        guard certStatus == errSecSuccess else {
            throw MacIdentityError.certificateNotStored(certStatus)
        }

        let identity = try identity(for: secCert, in: keychain)
        let spki = subjectPublicKeyInfo(of: secCert)
        let matchingCertificates = try certificates(matching: secKey, in: keychain)
        guard !spki.isEmpty, matchingCertificates.count == 1 else {
            throw MacIdentityError.persistentIdentityMismatch
        }
        return MacIdentity(
            privateKey: secKey,
            certificate: secCert,
            spki: spki,
            secIdentity: identity,
            keychain: keychain
        )
    }

    private static func keyQuery(in keychain: SecKeychain, applicationTag: Data) -> [String: Any] {
        [
            kSecClass as String: kSecClassKey,
            kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrKeyClass as String: kSecAttrKeyClassPrivate,
            kSecAttrApplicationTag as String: applicationTag,
            kSecUseKeychain as String: keychain,
            kSecUseDataProtectionKeychain as String: false,
        ]
    }

    private static func certificates(matching privateKey: SecKey, in keychain: SecKeychain) throws -> [SecCertificate] {
        guard let attributes = SecKeyCopyAttributes(privateKey) as? [String: Any],
              let keyApplicationLabel = attributes[kSecAttrApplicationLabel as String] as? Data else {
            throw MacIdentityError.keyAttributesUnavailable("public-key hash unavailable")
        }
        return try withKeychainOnSearchList(keychain, only: true) {
            // Limit the certificate query to the selected Keychain, then require both the generated
            // label and exact public-key application label. Security's identity resolver uses the
            // regular merged search list separately after this bounded lookup.
            let query: [String: Any] = [
                kSecClass as String: kSecClassCertificate,
                kSecAttrLabel as String: "DroidBridge",
                kSecReturnRef as String: true,
                kSecMatchLimit as String: kSecMatchLimitAll,
                kSecUseDataProtectionKeychain as String: false,
            ]
            var result: CFTypeRef?
            let status = SecItemCopyMatching(query as CFDictionary, &result)
            if status == errSecItemNotFound { return [] }
            guard status == errSecSuccess else { throw MacIdentityError.keychain(status) }
            guard let certificates = result as? [SecCertificate] else {
                throw MacIdentityError.keychain(errSecDecode)
            }
            return certificates.filter { certificate in
                guard let publicKey = SecCertificateCopyKey(certificate),
                      let publicKeyAttributes = SecKeyCopyAttributes(publicKey) as? [String: Any],
                      let certificateApplicationLabel = publicKeyAttributes[kSecAttrApplicationLabel as String] as? Data else { return false }
                return keyApplicationLabel == certificateApplicationLabel
            }
        }
    }

    /// SHA-256 of arbitrary bytes, lowercase hex.
    public static func pin(ofSPKI spki: Data) -> String {
        SHA256.hash(data: spki).map { String(format: "%02x", $0) }.joined()
    }

    /// The identity bytes the short authentication code is derived from.
    ///
    /// The SPKI, not the certificate, so that re-certifying the same key does not change the code
    /// the user is asked to compare. Android passes the same form.
    public var sasIdentityBytes: Data { subjectPublicKeyInfo }

    /// Extracts the DER SubjectPublicKeyInfo from a certificate.
    ///
    /// `SecCertificateCopyKey` gives a `SecKey`, and `SecKeyCopyExternalRepresentation` on an EC key
    /// returns the raw point rather than the SPKI wrapper. The SPKI is read out of the certificate's
    /// DER instead, so the bytes are exactly what the peer digested.
    public static func subjectPublicKeyInfo(of certificate: SecCertificate) -> Data {
        let der = SecCertificateCopyData(certificate) as Data
        return extractSPKI(fromCertificateDER: der) ?? Data()
    }

    /// Finds the SubjectPublicKeyInfo inside a certificate's DER.
    ///
    /// The SPKI for a P-256 key is a fixed 91-byte structure beginning with the `id-ecPublicKey` and
    /// `prime256v1` algorithm identifiers. Rather than parse the whole certificate, this locates
    /// that well-known prefix and takes the 91 bytes from there. Both sides produce a 91-byte SPKI
    /// for P-256, which the P03 report pins as a vector.
    static func extractSPKI(fromCertificateDER der: Data) -> Data? {
        // 30 59 30 13 06 07 2A 86 48 CE 3D 02 01 06 08 2A 86 48 CE 3D 03 01 07 03 42 00
        let prefix: [UInt8] = [
            0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2A, 0x86, 0x48, 0xCE, 0x3D, 0x02, 0x01,
            0x06, 0x08, 0x2A, 0x86, 0x48, 0xCE, 0x3D, 0x03, 0x01, 0x07, 0x03, 0x42, 0x00,
        ]
        let total = prefix.count + 65
        let bytes = [UInt8](der)
        guard bytes.count >= total else { return nil }

        for start in 0...(bytes.count - total) where Array(bytes[start..<(start + prefix.count)]) == prefix {
            return Data(bytes[start..<(start + total)])
        }
        return nil
    }

    // MARK: - Internals

    private static func generateKey(in keychain: SecKeychain, applicationTag: Data) throws -> SecKey {
        var error: Unmanaged<CFError>?
        let attrs: [String: Any] = [
            kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrKeySizeInBits as String: 256,
            kSecAttrIsPermanent as String: true,
            kSecPrivateKeyAttrs as String: [
                kSecAttrIsPermanent as String: true,
                kSecAttrApplicationTag as String: applicationTag,
                kSecUseKeychain as String: keychain,
            ],
        ]
        guard let key = SecKeyCreateRandomKey(attrs as CFDictionary, &error) else {
            let message = error?.takeRetainedValue().localizedDescription ?? "unknown"
            throw MacIdentityError.keyGenerationFailed(message)
        }
        return key
    }

    /// Rebuilds the keychain's key as a CryptoKit key.
    ///
    /// `SecKeyCopyExternalRepresentation` returns the x9.63 point followed by the scalar; CryptoKit
    /// wants the scalar alone. The public halves are compared afterwards, so a mismatch fails here
    /// rather than producing a certificate signed by a key the TLS stack does not hold.
    private static func cryptoKitKey(from secKey: SecKey) throws -> P256.Signing.PrivateKey {
        var error: Unmanaged<CFError>?
        guard let blob = SecKeyCopyExternalRepresentation(secKey, &error) as Data? else {
            let message = error?.takeRetainedValue().localizedDescription ?? "unknown"
            throw MacIdentityError.keyExportFailed(message)
        }
        guard blob.count == 97 else {
            throw MacIdentityError.unexpectedKeyEncoding(blob.count)
        }

        let key: P256.Signing.PrivateKey
        do {
            key = try P256.Signing.PrivateKey(rawRepresentation: blob.suffix(32))
        } catch {
            throw MacIdentityError.keyExportFailed(error.localizedDescription)
        }

        guard let publicKey = SecKeyCopyPublicKey(secKey),
              let publicBlob = SecKeyCopyExternalRepresentation(publicKey, &error) as Data? else {
            throw MacIdentityError.keyExportFailed("public key unavailable")
        }
        guard publicBlob == key.publicKey.x963Representation else {
            throw MacIdentityError.keyMismatch
        }
        return key
    }

    private static func selfSignedCertificateDER(for key: P256.Signing.PrivateKey) throws -> Data {
        let name = try DistinguishedName {
            CommonName("DroidBridge")
            OrganizationName("DroidBridge")
        }
        let now = Date()
        let san = try SubjectAlternativeNames([.dnsName("droidbridge.local")])
        let keyUsage = try KeyUsage(digitalSignature: true, keyCertSign: true)
        let eku = try ExtendedKeyUsage([.serverAuth, .clientAuth])
        let basic = try BasicConstraints.isCertificateAuthority(maxPathLength: nil)

        let certificate = try Certificate(
            version: .v3,
            serialNumber: Certificate.SerialNumber(),
            publicKey: Certificate.PublicKey(key.publicKey),
            notValidBefore: now.addingTimeInterval(-60),
            notValidAfter: now.addingTimeInterval(60 * 60 * 24 * 365),
            issuer: name,
            subject: name,
            signatureAlgorithm: .ecdsaWithSHA256,
            extensions: try Certificate.Extensions([
                basic.makeCertificateExtension(),
                keyUsage.makeCertificateExtension(),
                eku.makeCertificateExtension(),
                san.makeCertificateExtension(),
            ]),
            issuerPrivateKey: .init(key)
        )
        return Data(try certificate.serializeAsPEM().derBytes)
    }

    /// Runs a Keychain query with `keychain` on the process search list, then restores the prior
    /// list. Search-list state is process-global; it must never leak out of this operation.
    private static func withKeychainOnSearchList<T>(
        _ keychain: SecKeychain,
        only: Bool = false,
        operation: () throws -> T
    ) throws -> T {
        var previous: CFArray?
        let copyStatus = SecKeychainCopySearchList(&previous)
        guard copyStatus == errSecSuccess else { throw MacIdentityError.keychain(copyStatus) }
        var searchList = only ? [keychain] : ((previous as? [SecKeychain]) ?? [])
        if !only && !searchList.contains(where: { $0 == keychain }) { searchList.insert(keychain, at: 0) }
        let setStatus = SecKeychainSetSearchList(searchList as CFArray)
        guard setStatus == errSecSuccess else { throw MacIdentityError.keychain(setStatus) }
        defer { if let previous { _ = SecKeychainSetSearchList(previous) } }
        return try operation()
    }

    /// Resolves a `SecIdentity` for a certificate stored in the target keychain.
    /// Security's identity resolver requires the keychain to be in the search list and a nil
    /// keychain argument; see the measured login-Keychain behavior in P15.
    private static func identity(for certificate: SecCertificate, in keychain: SecKeychain) throws -> SecIdentity {
        try withKeychainOnSearchList(keychain) {
            var identity: SecIdentity?
            let status = SecIdentityCreateWithCertificate(nil, certificate, &identity)
            guard status == errSecSuccess, let identity else {
                throw MacIdentityError.identityUnavailable(status)
            }
            return identity
        }
    }

}

/// Identity creation failed.
public enum MacIdentityError: Error, CustomStringConvertible {
    case keychainUnavailable(OSStatus)
    case keychain(OSStatus)
    case keyAttributesUnavailable(String)
    case keyGenerationFailed(String)
    case keyExportFailed(String)
    case unexpectedKeyEncoding(Int)
    case keyMismatch
    case certificateRejectedBySecurityFramework
    case certificateNotStored(OSStatus)
    case identityUnavailable(OSStatus)
    case persistentIdentityIncomplete(Int)
    case persistentIdentityMismatch

    public var description: String {
        switch self {
        case .keychainUnavailable(let status):
            return "could not access the default login Keychain (OSStatus \(status))"
        case .keychain(let status):
            return "Keychain operation failed (OSStatus \(status))"
        case .keyAttributesUnavailable(let message):
            return "could not read identity key attributes: \(message)"
        case .keyGenerationFailed(let message):
            return "could not generate the identity key: \(message)"
        case .keyExportFailed(let message):
            return "could not read the identity key back: \(message)"
        case .unexpectedKeyEncoding(let bytes):
            return "identity key exported as \(bytes) bytes, expected 97"
        case .keyMismatch:
            return "the exported key does not match the keychain's public key"
        case .certificateRejectedBySecurityFramework:
            return "the generated certificate was rejected by the Security framework"
        case .certificateNotStored(let status):
            return "could not store the certificate (OSStatus \(status))"
        case .identityUnavailable(let status):
            return "could not resolve a SecIdentity (OSStatus \(status))"
        case .persistentIdentityIncomplete(let matchingCertificates):
            return "persistent identity has no unique matching certificate (\(matchingCertificates) matches); refusing to replace it"
        case .persistentIdentityMismatch:
            return "persistent identity key does not match its certificate"
        }
    }
}
