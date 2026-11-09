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
/// 3. **`SecIdentityCreateWithCertificate` must be passed a nil keychain**, with the temporary
///    keychain in the search list. Passing the keychain explicitly returns `errSecItemNotFound`
///    even though the identity exists.
/// 4. **The search list must be restored afterwards.** It is process-global, so leaving a deleted
///    keychain in it breaks every later keychain lookup in the app.
///
/// ## Storage
///
/// The key lives in a temporary keychain created for this process and deleted when the identity is
/// released. ADR-025 specifies the production storage (data protection keychain under an embedded
/// Developer ID profile) and that is deliberately **not** built here: P03 measures the handshake,
/// and P15 owns signing and notarisation. What matters for P03 is that the *shape* of the identity
/// is right, so the storage can change without the session code changing.
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

    /// The temporary keychain holding the key and certificate.
    ///
    /// Held so it can be deleted in `deinit`; nothing is written outside it.
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

    deinit {
        // Best effort: the keychain is a temp file, and leaving it behind would be untidy rather
        // than harmful. The search list was already restored in `generate`.
        SecKeychainDelete(keychain)
    }

    /// Generates a fresh identity.
    ///
    /// Generating a new identity is a *new device identity*, not a refresh: every peer that pinned
    /// the old one will see a changed pin and must re-pair. That is why this is called once and the
    /// result is held, rather than called per connection.
    public static func generate() throws -> MacIdentity {
        let path = NSTemporaryDirectory() + "droidbridge-p03-\(UUID().uuidString).keychain"

        var keychainRef: SecKeychain?
        let createStatus = SecKeychainCreate(path, 0, "", false, nil, &keychainRef)
        guard createStatus == errSecSuccess, let keychain = keychainRef else {
            throw MacIdentityError.keychainUnavailable(createStatus)
        }

        // 1. Generate the key into the keychain. Importing a CryptoKit key does not work.
        let secKey = try generateKey(in: keychain)

        // 2. Reconstruct the same key in CryptoKit so `swift-certificates` can sign with it.
        let cryptoKey = try cryptoKitKey(from: secKey)

        // 3. Build and sign the self-signed certificate.
        let certDER = try selfSignedCertificateDER(for: cryptoKey)
        guard let secCert = SecCertificateCreateWithData(nil, Data(certDER) as CFData) else {
            throw MacIdentityError.certificateRejectedBySecurityFramework
        }

        // 4. Add the certificate to the same keychain so it pairs with the key.
        var certAttrs: [String: Any] = [kSecValueRef as String: secCert]
        certAttrs[kSecUseKeychain as String] = keychain
        let certStatus = SecItemAdd(certAttrs as CFDictionary, nil)
        guard certStatus == errSecSuccess else {
            throw MacIdentityError.certificateNotStored(certStatus)
        }

        // 5. Resolve the identity. The nil keychain plus search list is the only combination that
        //    works; see fact 3 in the type comment.
        let identity = try identity(for: secCert, in: keychain)

        // 6. The SPKI, read from the certificate so both sides digest identical bytes.
        let spki = subjectPublicKeyInfo(of: secCert)

        return MacIdentity(
            privateKey: secKey,
            certificate: secCert,
            spki: spki,
            secIdentity: identity,
            keychain: keychain
        )
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

    private static func generateKey(in keychain: SecKeychain) throws -> SecKey {
        var error: Unmanaged<CFError>?
        let attrs: [String: Any] = [
            kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrKeySizeInBits as String: 256,
            kSecAttrIsPermanent as String: true,
            kSecPrivateKeyAttrs as String: [
                kSecAttrIsPermanent as String: true,
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

    /// Resolves a `SecIdentity` for a certificate stored in a temporary keychain.
    ///
    /// The search list is process-global, so it is restored before returning. Leaving a deleted
    /// keychain in it would break every later keychain lookup in the app, which is the kind of bug
    /// that shows up somewhere unrelated an hour later.
    private static func identity(for certificate: SecCertificate, in keychain: SecKeychain) throws -> SecIdentity {
        var previous: CFArray?
        SecKeychainCopySearchList(&previous)

        var searchList: [SecKeychain] = [keychain]
        if let previous = previous as? [SecKeychain] {
            searchList.append(contentsOf: previous)
        }
        SecKeychainSetSearchList(searchList as CFArray)
        defer {
            if let previous {
                SecKeychainSetSearchList(previous)
            }
        }

        var identity: SecIdentity?
        let status = SecIdentityCreateWithCertificate(nil, certificate, &identity)
        guard status == errSecSuccess, let identity else {
            throw MacIdentityError.identityUnavailable(status)
        }
        return identity
    }
}

/// Identity creation failed.
public enum MacIdentityError: Error, CustomStringConvertible {
    case keychainUnavailable(OSStatus)
    case keyGenerationFailed(String)
    case keyExportFailed(String)
    case unexpectedKeyEncoding(Int)
    case keyMismatch
    case certificateRejectedBySecurityFramework
    case certificateNotStored(OSStatus)
    case identityUnavailable(OSStatus)

    public var description: String {
        switch self {
        case .keychainUnavailable(let status):
            return "could not create the temporary keychain (OSStatus \(status))"
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
        }
    }
}
