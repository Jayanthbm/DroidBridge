import Foundation
import Security

/// Confirmed peer pins, stored in the macOS Keychain (ADR-011, ADR-024).
///
/// ## Why the Keychain and not a file
///
/// A pin is a public-key digest, so confidentiality is not the point — integrity is. A file that
/// anything in the user's account can rewrite can be re-pointed at an attacker's key, which turns a
/// "known peer" into a silent substitution. A Keychain item is access-controlled by the system and
/// is not part of any sync or backup the app controls, so the value the user confirmed is the value
/// the next handshake checks.
///
/// ## Which Keychain
///
/// The prototype uses the login (file-based) Keychain. ADR-025 specifies the **data protection**
/// Keychain for the identity key under an embedded Developer ID profile, and that work is P13/P15:
/// the data protection Keychain needs an application-identifier entitlement, which does not exist
/// until the app is provisioned. Using it here would make P03 unbuildable on an ad-hoc build. The
/// API shape below is the same either way, so moving to the data protection Keychain later is a
/// change of `kSecUseDataProtectionKeychain`, not a change of the session code.
///
/// ## Failure behaviour
///
/// A read error returns nil, which makes the peer unknown and requires the full first-pairing
/// confirmation. A pin that cannot be read is never treated as trusted.
public final class PeerPinStore {

    public enum StoreError: Swift.Error, CustomStringConvertible {
        case keychain(OSStatus)
        case invalidPin(String)

        public var description: String {
            switch self {
            case .keychain(let status):
                let message = SecCopyErrorMessageString(status, nil) as String? ?? "unknown"
                return "keychain returned \(status): \(message)"
            case .invalidPin(let value):
                return "refusing to store a malformed pin '\(value)'"
            }
        }
    }

    /// Keychain service name. Separate from the identity items so the two can be managed apart.
    public let service: String

    public init(service: String = "com.droidbridge.p03.peer-pins") {
        self.service = service
    }

    /// The confirmed pin for [peerId], or nil when absent or unreadable.
    public func pin(for peerId: String) -> String? {
        var query = baseQuery(peerId: peerId)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne

        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        guard status == errSecSuccess, let data = item as? Data else {
            return nil
        }
        return String(data: data, encoding: .utf8)
    }

    /// Persists a confirmed pin. Throws rather than reporting a pairing that is not on disk.
    public func store(pin: String, for peerId: String) throws {
        guard isValidPin(pin) else { throw StoreError.invalidPin(pin) }

        let query = baseQuery(peerId: peerId)
        let attributes: [String: Any] = [kSecValueData as String: Data(pin.utf8)]

        let updateStatus = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        if updateStatus == errSecSuccess { return }
        guard updateStatus == errSecItemNotFound else {
            throw StoreError.keychain(updateStatus)
        }

        var add = query
        add[kSecValueData as String] = Data(pin.utf8)
        let addStatus = SecItemAdd(add as CFDictionary, nil)
        guard addStatus == errSecSuccess else {
            throw StoreError.keychain(addStatus)
        }
    }

    /// Removes a pin, so the next connection is a first pairing again.
    public func forget(_ peerId: String) {
        SecItemDelete(baseQuery(peerId: peerId) as CFDictionary)
    }

    /// Every stored pin, for diagnostics. Unreadable entries are omitted.
    public func snapshot() -> [String: String] {
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecReturnAttributes as String: true,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitAll,
        ]
        query[kSecUseDataProtectionKeychain as String] = false

        var items: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &items)
        guard status == errSecSuccess, let entries = items as? [[String: Any]] else {
            return [:]
        }

        var pins: [String: String] = [:]
        for entry in entries {
            guard let account = entry[kSecAttrAccount as String] as? String,
                  let data = entry[kSecValueData as String] as? Data,
                  let pin = String(data: data, encoding: .utf8) else { continue }
            pins[account] = pin
        }
        return pins
    }

    // MARK: - Internals

    private func baseQuery(peerId: String) -> [String: Any] {
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: peerId,
        ]
        // Explicitly the login Keychain; see the type comment for why the data protection Keychain
        // is deferred to ADR-025's provisioning work.
        query[kSecUseDataProtectionKeychain as String] = false
        return query
    }

    private func isValidPin(_ pin: String) -> Bool {
        // SHA-256 as lowercase hex: exactly 64 characters, nothing else.
        guard pin.count == 64 else { return false }
        return pin.allSatisfy { character in
            ("0"..."9").contains(String(character)) || ("a"..."f").contains(String(character))
        }
    }
}
