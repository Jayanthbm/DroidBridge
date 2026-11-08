import Foundation
import CryptoKit

/// The short authentication code shown on both screens during pairing (ADR-011).
///
/// This must produce byte-identical results to `ShortAuthenticationCode.kt` in `shared/protocol`.
/// The two implementations are the same algorithm written twice, which is exactly the kind of
/// duplication that silently drifts: if they disagree, pairing fails with no useful error and the
/// user sees two different codes on two screens. `ShortAuthenticationCodeTests` pins the shared
/// vectors, so a drift breaks a test rather than a pairing.
///
/// Why the inputs are sorted: both sides must derive the same code without agreeing who goes
/// first. Sorting by content gives one canonical order, so neither side needs to know its role.
/// A role-based order would let a peer choose the role that produces the code it prefers.
///
/// Why the length is prefixed: concatenating two variable-length identities is ambiguous, since
/// ("ab","c") and ("a","bc") both concatenate to "abc". An attacker controlling one identity could
/// exploit that to make two different key pairs yield the same code.
///
/// The code is a confirmation value, not a key and not a secret. It is derived from public keys,
/// so anyone observing both can compute it. Its only job is to let a human notice a substitution;
/// confidentiality comes from TLS.
enum ShortAuthenticationCode {

    /// Digits in the displayed code.
    static let digits = 6

    /// Domain separator, so these same bytes hashed for another purpose cannot collide.
    private static let domain = "droidbridge/sas/v1"

    /// Derives the code from two public identities.
    ///
    /// Both sides must pass the same *form* of identity (e.g. both DER certificates). Mixing forms
    /// would produce different codes on the two screens.
    static func derive(_ identityA: Data, _ identityB: Data) -> String {
        precondition(!identityA.isEmpty, "identity must not be empty")
        precondition(!identityB.isEmpty, "identity must not be empty")

        // Canonical order: compare by content so both sides hash the same sequence.
        let (first, second) = identityA.lexicographicallyPrecedes(identityB)
            ? (identityA, identityB)
            : (identityB, identityA)

        var hasher = SHA256()
        hasher.update(data: Data(domain.utf8))
        updateLengthPrefixed(&hasher, first)
        updateLengthPrefixed(&hasher, second)
        let hash = Data(hasher.finalize())

        // Take the low 32 bits and reduce modulo 10^6.
        //
        // Modulo bias: 2^32 is not a multiple of 10^6, so the first 967_296 values appear one
        // extra time. The bias is about 0.02% and irrelevant here, since the code is a human
        // comparison aid against an attacker who must also win a TLS handshake. Stated rather
        // than left for a reader to wonder about.
        let value = (UInt32(hash[hash.startIndex]) << 24)
            | (UInt32(hash[hash.startIndex + 1]) << 16)
            | (UInt32(hash[hash.startIndex + 2]) << 8)
            | UInt32(hash[hash.startIndex + 3])

        let code = value % 1_000_000
        return String(format: "%06u", code)
    }

    /// Formats a code for display as `123 456`.
    ///
    /// Grouped so a person can read it aloud and compare it without losing their place. Display
    /// only: the canonical form is the unspaced string, and comparison always uses that.
    static func formatForDisplay(_ code: String) -> String {
        precondition(code.count == digits, "expected a \(digits) digit code, got \(code.count)")
        let split = code.index(code.startIndex, offsetBy: 3)
        return "\(code[code.startIndex..<split]) \(code[split...])"
    }

    /// Compares two codes without leaking how far they matched.
    ///
    /// A short-circuiting comparison on a value a user is being asked to verify is the kind of
    /// detail that costs nothing to get right.
    static func matches(_ a: String, _ b: String) -> Bool {
        guard a.count == b.count else { return false }
        var diff: UInt8 = 0
        for (x, y) in zip(a.utf8, b.utf8) {
            diff |= x ^ y
        }
        return diff == 0
    }

    private static func updateLengthPrefixed(_ hasher: inout SHA256, _ value: Data) {
        let length = UInt32(value.count)
        var bigEndian = length.bigEndian
        withUnsafeBytes(of: &bigEndian) { hasher.update(bufferPointer: $0) }
        hasher.update(data: value)
    }
}
