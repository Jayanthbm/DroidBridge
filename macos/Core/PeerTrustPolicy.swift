import Foundation

/// What a presented peer pin is worth, decided in one place (ADR-055).
///
/// The listener used to decide this twice — once inside the TLS verify block and once after the
/// handshake — and the two copies drifted: a peer whose pin was not in a *non-empty* confirmed set
/// was refused outright, which made adding a second phone impossible without forgetting the first.
/// The set exists (ADR-054) precisely so two phones can be trusted at once, so the refusal was
/// wrong: an unknown pin deserves the code-comparison prompt, not a dead handshake.
///
/// The three answers:
/// - `.trusted` — the pin is confirmed; application data may flow.
/// - `.candidate` — not confirmed, but allowed to reach the unconfirmed pairing state where the
///   short authentication code is shown on both sides. No application data moves until the user
///   confirms, and nothing is stored until then.
/// - `.refused` — only ever produced by the explicit-pin test mode, where a caller asked for exact
///   equality and got a different identity. The stored-set path never needs it.
enum PeerTrustPolicy {
    enum Decision: Equatable {
        case trusted
        case candidate
        case refused
    }

    /// - Parameters:
    ///   - pin: the pin the peer presented in this handshake.
    ///   - confirmedPins: the pins the user has confirmed on this Mac.
    ///   - explicitPin: a pin demanded by a test harness, or nil for normal product behaviour.
    static func decide(pin: String, confirmedPins: Set<String>, explicitPin: String?) -> Decision {
        if let explicitPin {
            return explicitPin == pin ? .trusted : .refused
        }
        return confirmedPins.contains(pin) ? .trusted : .candidate
    }
}