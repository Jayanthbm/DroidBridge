import Foundation

/// The Bonjour service type the Mac advertises and Android discovers.
///
/// One definition, used by the advertiser, the TXT record and the tests. A typo here would be
/// invisible at runtime -- discovery would simply find nothing -- so it is a constant rather than
/// a string literal at each call site.
///
/// The type must match `NSBonjourServices` in `App/Info.plist` and ADR-022. Bonjour types are
/// `_name._tcp` with no trailing dot in the API, and the name is limited to 15 characters.
public enum ServiceType {
    public static let droidBridge = "_droidbridge-dev._tcp"
}

/// The TXT record the Mac publishes, per NETWORK_PROTOCOL.md.
///
/// Kept as a value type so the exact bytes can be asserted in a test. Bonjour TXT records are a
/// dictionary of small strings; the protocol version and session id are what Android reads to
/// decide whether a discovered service is one it can talk to.
public struct ServiceTxtRecord: Equatable {
    /// Wire protocol version. Android rejects a service whose `v` it does not understand rather
    /// than guessing at compatibility.
    public let version: Int
    /// Random per-session identifier, so a stale advertisement from a previous run is
    /// distinguishable from the current one.
    public let sessionId: String

    public init(version: Int = 1, sessionId: String) {
        self.version = version
        self.sessionId = sessionId
    }

    /// The TXT record as Bonjour wants it: string keys to string values.
    public var dictionary: [String: String] {
        ["v": String(version), "sid": sessionId]
    }

    /// A session id with enough entropy that two runs cannot collide.
    public static func randomSessionId() -> String {
        // 16 bytes of randomness, hex encoded. Not a secret: it identifies a session, it does not
        // authenticate one (that is TLS and the pairing code, P03).
        (0..<16).map { _ in String(format: "%02x", UInt8.random(in: 0...255)) }.joined()
    }
}

/// What the advertiser is doing, for the UI and for the evidence log.
public enum AdvertiseState: Equatable {
    case idle
    case advertising(name: String, port: UInt16)
    case failed(String)

    public var isAdvertising: Bool {
        if case .advertising = self { return true }
        return false
    }
}
