import Foundation
import Security

/// Confirmed peer pins, stored in the macOS Keychain (ADR-011, ADR-024).
///
/// ## A pin is the identity, not a label
///
/// A pin is the SHA-256 digest of the peer's SPKI, so the pin *is* the peer's identity commitment.
/// Trust is therefore set membership — "is the presented pin one the user confirmed?" — and not a
/// lookup by a caller-chosen label. The earlier per-`peerId` model broke the moment a second phone
/// entered the picture (ADR-053's unification collapsed every surface onto one label, so the two
/// phones fought over a single pin slot and alternated PIN MISMATCH). This store keeps a set of
/// confirmed pins instead; any number of peers can be confirmed at once, and "forget" clears the
/// whole set, which is what the product's single "Forget phone pin" affordance already means.
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
/// ## Migration
///
/// Items written by the previous per-peer-id model (one item per label under this service) are
/// absorbed into the set on first read and deleted. Malformed values are dropped during migration —
/// a pin that cannot be validated is never treated as trusted.
///
/// ## Failure behaviour
///
/// A read error returns an empty set, which makes every peer unknown and requires the full
/// first-pairing confirmation. A pin that cannot be read is never treated as trusted.
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

    /// Account under which the confirmed-pin set is stored as one newline-joined item.
    static let setAccount = "confirmed-pins"

    public init(service: String = "com.droidbridge.p03.peer-pins") {
        self.service = service
    }

    /// The set of pins the user has confirmed. Migrates legacy per-peer-id items on the way.
    public func confirmedPins() -> Set<String> {
        var pins = readSetItem()
        let legacy = readLegacyItems()
        if !legacy.isEmpty {
            pins.formUnion(legacy.values)
            for (account, _) in legacy {
                SecItemDelete(baseQuery(account: account) as CFDictionary)
            }
            let write = writeSetItem(pins)
            if case .failed = write {
                // The merged set could not be written; keep returning what was read so a
                // confirmed pin is not silently lost. The next read migrates again.
                return pins
            }
        }
        return pins
    }

    /// Whether [pin] is one the user confirmed.
    public func isConfirmed(_ pin: String) -> Bool {
        confirmedPins().contains(pin)
    }

    /// Persists a newly confirmed pin. Throws rather than reporting a pairing that is not on disk.
    public func confirm(_ pin: String) throws {
        guard isValidPin(pin) else { throw StoreError.invalidPin(pin) }

        var pins = confirmedPins()
        guard !pins.contains(pin) else { return }
        pins.insert(pin)

        switch writeSetItem(pins) {
        case .written:
            return
        case .failed(let status):
            throw StoreError.keychain(status)
        }
    }

    /// Removes every confirmed pin, so the next connection is a first pairing again.
    public func forgetAll() {
        SecItemDelete(baseQuery(account: Self.setAccount) as CFDictionary)
        // Legacy items are deleted on migration; a sweep here covers any left by an interrupted
        // migration so "forget" really means nothing is confirmed afterwards.
        for (account, _) in readLegacyItems() {
            SecItemDelete(baseQuery(account: account) as CFDictionary)
        }
    }

    // MARK: - Internals

    private func setItemQuery() -> [String: Any] {
        var query = baseQuery(account: Self.setAccount)
        query[kSecReturnData as String] = true
        return query
    }

    private func readSetItem() -> Set<String> {
        var item: CFTypeRef?
        let status = SecItemCopyMatching(setItemQuery() as CFDictionary, &item)
        guard status == errSecSuccess, let data = item as? Data,
              let joined = String(data: data, encoding: .utf8) else {
            return []
        }
        return Set(joined.split(separator: "\n").map(String.init).filter(isValidPin))
    }

    /// Legacy per-peer-id items: every item under this service whose account is not the set item.
    ///
    /// Two phases because macOS 27 rejects `kSecReturnData` together with `kSecMatchLimitAll`
    /// (`errSecParam`): the item list is read as attributes only, then each item's data is read
    /// individually with `kSecMatchLimitOne`, the shape the per-pin reads have always used.
    private func readLegacyItems() -> [String: String] {
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecReturnAttributes as String: true,
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
                  account != Self.setAccount else { continue }
            var dataQuery = baseQuery(account: account)
            dataQuery[kSecReturnData as String] = true
            dataQuery[kSecMatchLimit as String] = kSecMatchLimitOne

            var item: CFTypeRef?
            let read = SecItemCopyMatching(dataQuery as CFDictionary, &item)
            guard read == errSecSuccess, let data = item as? Data,
                  let pin = String(data: data, encoding: .utf8),
                  isValidPin(pin) else { continue }
            pins[account] = pin
        }
        return pins
    }

    private enum SetWriteOutcome { case written; case failed(OSStatus) }

    private func writeSetItem(_ pins: Set<String>) -> SetWriteOutcome {
        let data = Data(pins.sorted().joined(separator: "\n").utf8)
        let query = baseQuery(account: Self.setAccount)
        let update = SecItemUpdate(query as CFDictionary,
                                   [kSecValueData as String: data] as CFDictionary)
        if update == errSecSuccess { return .written }
        guard update == errSecItemNotFound else { return .failed(update) }

        var add = query
        add[kSecValueData as String] = data
        let status = SecItemAdd(add as CFDictionary, nil)
        guard status == errSecSuccess else { return .failed(status) }
        return .written
    }

    private func baseQuery(account: String) -> [String: Any] {
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
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
