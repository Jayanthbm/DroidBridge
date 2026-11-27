import Foundation
import Security

/// The Mac's long-lived self-signed TLS identity. It is generated once with the
/// system OpenSSL binary and kept in Application Support, outside the app bundle.
struct MacIdentity: @unchecked Sendable {
    let identity: SecIdentity
    let certificate: SecCertificate
    let fingerprint: String
    let deviceID: String
    let displayName: String

    var networkIdentity: sec_identity_t? {
        sec_identity_create(identity)
    }
}

enum MacIdentityError: LocalizedError {
    case opensslFailed(String)
    case identityFailed(String)
    case missingIdentity
    case missingCertificate

    var errorDescription: String? {
        switch self {
        case .opensslFailed(let message):
            "Could not create the Mac TLS identity: \(message)"
        case .identityFailed(let message):
            "Could not load the Mac TLS identity: \(message)"
        case .missingIdentity:
            "The Mac TLS identity is missing."
        case .missingCertificate:
            "The Mac TLS certificate is missing."
        }
    }
}

enum MacIdentityStore {
    static func loadOrCreate() throws -> MacIdentity {
        let support = try supportDirectory()
        let certURL = support.appendingPathComponent("identity.crt")
        let keyURL = support.appendingPathComponent("identity.key")
        let certDERURL = support.appendingPathComponent("identity.crt.der")
        let keyDERURL = support.appendingPathComponent("identity.key.der")
        let legacyP12URL = support.appendingPathComponent("identity.p12")

        // Earlier builds imported a keychain-backed PKCS#12 identity. Keeping it
        // around can trigger a keychain authorization prompt, so use the
        // in-memory identity above and remove the stale import.
        if FileManager.default.fileExists(atPath: legacyP12URL.path) {
            try? FileManager.default.removeItem(at: legacyP12URL)
        }

        let filesExist = FileManager.default.fileExists(atPath: certURL.path)
            && FileManager.default.fileExists(atPath: keyURL.path)
        if !filesExist {
            try generate(certURL: certURL, keyURL: keyURL)
        }

        do {
            return try loadIdentity(
                certDERURL: certDERURL,
                keyDERURL: keyDERURL,
                certURL: certURL,
                keyURL: keyURL,
                support: support
            )
        } catch {
            // Migrate older EC PKCS#12 identities or recover from a truncated file.
            try? FileManager.default.removeItem(at: certURL)
            try? FileManager.default.removeItem(at: keyURL)
            try? FileManager.default.removeItem(at: certDERURL)
            try? FileManager.default.removeItem(at: keyDERURL)
            try generate(certURL: certURL, keyURL: keyURL)
            return try loadIdentity(
                certDERURL: certDERURL,
                keyDERURL: keyDERURL,
                certURL: certURL,
                keyURL: keyURL,
                support: support
            )
        }
    }

    private static func loadIdentity(
        certDERURL: URL,
        keyDERURL: URL,
        certURL: URL,
        keyURL: URL,
        support: URL
    ) throws -> MacIdentity {
        if !FileManager.default.fileExists(atPath: keyDERURL.path) {
            try run("/usr/bin/openssl", [
                "rsa", "-in", keyURL.path,
                "-outform", "DER", "-out", keyDERURL.path,
            ])
        }
        if !FileManager.default.fileExists(atPath: certDERURL.path) {
            try run("/usr/bin/openssl", [
                "x509", "-in", certURL.path,
                "-outform", "DER", "-out", certDERURL.path,
            ])
        }

        let certData = try Data(contentsOf: certDERURL)
        let keyData = try Data(contentsOf: keyDERURL)
        guard let certificate = SecCertificateCreateWithData(nil, certData as CFData) else {
            throw MacIdentityError.missingCertificate
        }
        var error: Unmanaged<CFError>?
        let attributes: [CFString: Any] = [
            kSecAttrKeyType: kSecAttrKeyTypeRSA,
            kSecAttrKeyClass: kSecAttrKeyClassPrivate,
            kSecAttrKeySizeInBits: 2048,
        ]
        guard let privateKey = SecKeyCreateWithData(
            keyData as CFData,
            attributes as CFDictionary,
            &error
        ) else {
            let message = error?.takeRetainedValue().localizedDescription ?? "unknown key format"
            throw MacIdentityError.identityFailed(message)
        }
        guard let identity = SecIdentityCreate(nil, certificate, privateKey) else {
            throw MacIdentityError.missingIdentity
        }

        let certificateData = SecCertificateCopyData(certificate) as Data
        let fingerprint = SHA256Fingerprint.hex(certificateData)
        let deviceID = try loadOrCreateDeviceID(in: support)
        let hostName = Host.current().localizedName ?? "Mac"
        return MacIdentity(
            identity: identity,
            certificate: certificate,
            fingerprint: fingerprint,
            deviceID: deviceID,
            displayName: hostName
        )
    }

    private static func supportDirectory() throws -> URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let directory = base.appendingPathComponent("DroidBridge", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory
    }

    private static func generate(certURL: URL, keyURL: URL) throws {
        let openssl = "/usr/bin/openssl"
        try run(openssl, [
            "req", "-x509", "-newkey", "rsa:2048",
            "-nodes",
            "-keyout", keyURL.path,
            "-out", certURL.path,
            "-days", "3650",
            "-subj", "/CN=DroidBridge Mac/O=DroidBridge"
        ])
        try? FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: keyURL.path)
    }

    private static func run(_ executable: String, _ arguments: [String]) throws {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: executable)
        process.arguments = arguments
        let errorPipe = Pipe()
        process.standardError = errorPipe
        process.standardOutput = Pipe()
        try process.run()
        process.waitUntilExit()
        guard process.terminationStatus == 0 else {
            let data = errorPipe.fileHandleForReading.readDataToEndOfFile()
            let message = String(data: data, encoding: .utf8) ?? "exit \(process.terminationStatus)"
            throw MacIdentityError.opensslFailed(message.trimmingCharacters(in: .whitespacesAndNewlines))
        }
    }

    private static func loadOrCreateDeviceID(in directory: URL) throws -> String {
        let url = directory.appendingPathComponent("device-id")
        if let value = try? String(contentsOf: url, encoding: .utf8)
            .trimmingCharacters(in: .whitespacesAndNewlines),
           !value.isEmpty {
            return value
        }
        let value = UUID().uuidString.lowercased()
        try value.write(to: url, atomically: true, encoding: .utf8)
        try? FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
        return value
    }
}
