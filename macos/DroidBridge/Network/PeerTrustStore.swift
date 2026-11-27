import Foundation
import Security

struct TrustedPeer: Codable, Hashable, Sendable {
    var fingerprint: String
    var deviceID: String
    var name: String
    var model: String
    var platform: String
    var pairedAt: Date
}

/// Pins live in the Keychain. A JSON mirror in Application Support makes the
/// local-development build resilient if an ad-hoc signature changes.
@MainActor
final class PeerTrustStore {
    private let service = "com.jayanth.droidbridge.trust"
    private let fileURL: URL
    private var cached: [String: TrustedPeer] = [:]

    init() {
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("DroidBridge", isDirectory: true)
        try? FileManager.default.createDirectory(at: support, withIntermediateDirectories: true)
        fileURL = support.appendingPathComponent("trust.json")
        cached = Self.readFile(fileURL)
        for peer in keychainPeers() {
            cached[peer.fingerprint] = peer
        }
    }

    func isTrusted(fingerprint: String) -> Bool {
        cached[fingerprint] != nil
    }

    func peer(for fingerprint: String) -> TrustedPeer? {
        cached[fingerprint]
    }

    func all() -> [TrustedPeer] {
        cached.values.sorted { $0.pairedAt > $1.pairedAt }
    }

    func remember(_ peer: TrustedPeer) {
        cached[peer.fingerprint] = peer
        writeFile()
        writeKeychain(peer)
    }

    func forget(fingerprint: String) {
        cached.removeValue(forKey: fingerprint)
        writeFile()
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: fingerprint
        ]
        SecItemDelete(query as CFDictionary)
    }

    private func writeFile() {
        guard let data = try? JSONEncoder().encode(Array(cached.values)) else { return }
        try? data.write(to: fileURL, options: .atomic)
        try? FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: fileURL.path)
    }

    private func writeKeychain(_ peer: TrustedPeer) {
        guard let data = try? JSONEncoder().encode(peer) else { return }
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: peer.fingerprint
        ]
        let attributes: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        ]
        let status = SecItemAdd((query.merging(attributes) { $1 }) as CFDictionary, nil)
        if status == errSecDuplicateItem {
            SecItemUpdate(query as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        }
    }

    private func keychainPeers() -> [TrustedPeer] {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitAll
        ]
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess else { return [] }
        let rows: [Data]
        if let values = result as? [Data] {
            rows = values
        } else if let data = result as? Data {
            rows = [data]
        } else {
            return []
        }
        return rows.compactMap { try? JSONDecoder().decode(TrustedPeer.self, from: $0) }
    }

    private static func readFile(_ url: URL) -> [String: TrustedPeer] {
        guard let data = try? Data(contentsOf: url),
              let peers = try? JSONDecoder().decode([TrustedPeer].self, from: data)
        else { return [:] }
        return Dictionary(uniqueKeysWithValues: peers.map { ($0.fingerprint, $0) })
    }
}
