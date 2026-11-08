import XCTest
@testable import DroidBridge

/// P03: the Swift short authentication code must agree with the Kotlin one exactly.
///
/// The vectors below are produced by `VectorPrinter` in `shared/protocol` and pasted in. They are
/// not recomputed here: the point is to catch drift between two independent implementations of the
/// same algorithm, and a test that derived its own expectation would agree with itself.
final class ShortAuthenticationCodeTests: XCTestCase {

    private func hex(_ string: String) -> Data {
        var data = Data()
        var index = string.startIndex
        while index < string.endIndex {
            let next = string.index(index, offsetBy: 2)
            data.append(UInt8(string[index..<next], radix: 16)!)
            index = next
        }
        return data
    }

    /// name, identity A (hex), identity B (hex), expected code from the Kotlin implementation.
    private let sharedVectors: [(String, String, String, String)] = [
        ("mac-v1", "00112233445566778899aabbccddeeff", "ffeeddccbbaa99887766554433221100", "953540"),
        ("android-v1", "3082010a0a0b0c0d0e0f", "3082010a0a0b0c0d0e10", "804825"),
        ("short-ids", "01", "02", "075101"),
        ("length-ambiguity-a", "6162", "63", "144752"),
        ("length-ambiguity-b", "61", "6263", "297114"),
        (
            "long",
            String(repeating: "aa", count: 64),
            String(repeating: "bb", count: 64),
            "581903"
        ),
    ]

    func testMatchesTheKotlinImplementation() {
        // The decisive test: if this fails, a real pairing shows two different codes and the user
        // rejects a valid device with no way to tell why.
        for (name, a, b, expected) in sharedVectors {
            let code = ShortAuthenticationCode.derive(hex(a), hex(b))
            XCTAssertEqual(code, expected, "vector \(name) disagreed with the Kotlin implementation")
        }
    }

    func testIsSymmetric() {
        // Both sides must agree without either knowing who is "first".
        for (name, a, b, _) in sharedVectors {
            XCTAssertEqual(
                ShortAuthenticationCode.derive(hex(a), hex(b)),
                ShortAuthenticationCode.derive(hex(b), hex(a)),
                "vector \(name) was not symmetric"
            )
        }
    }

    func testIsSixDigits() {
        for (name, a, b, _) in sharedVectors {
            let code = ShortAuthenticationCode.derive(hex(a), hex(b))
            XCTAssertEqual(code.count, 6, "vector \(name) produced '\(code)'")
            XCTAssertTrue(code.allSatisfy(\.isNumber), "vector \(name) produced '\(code)'")
        }
    }

    func testIsZeroPadded() {
        // "075101" above is the case that matters: a code below 100000 must still be six
        // characters, or the two screens show different things.
        let code = ShortAuthenticationCode.derive(hex("01"), hex("02"))
        XCTAssertEqual(code, "075101")
        XCTAssertEqual(code.count, 6)
    }

    func testSubstitutedIdentityChangesTheCode() {
        // The entire security value: a man-in-the-middle swapping in its own key must change what
        // the user sees, or the confirmation step is theatre.
        let genuine = ShortAuthenticationCode.derive(hex("00112233445566778899aabbccddeeff"), hex("ffeeddccbbaa99887766554433221100"))
        let mitm = ShortAuthenticationCode.derive(hex("00112233445566778899aabbccddeeff"), hex("ffeeddccbbaa99887766554433221101"))
        XCTAssertNotEqual(genuine, mitm)
    }

    func testLengthAmbiguityDoesNotCollide() {
        // Without length prefixes these two would both concatenate to "abc" and produce one code.
        let left = ShortAuthenticationCode.derive(hex("6162"), hex("63"))
        let right = ShortAuthenticationCode.derive(hex("61"), hex("6263"))
        XCTAssertNotEqual(left, right)
    }

    func testDisplayFormattingGroupsTheDigits() {
        XCTAssertEqual(ShortAuthenticationCode.formatForDisplay("123456"), "123 456")
        XCTAssertEqual(ShortAuthenticationCode.formatForDisplay("075101"), "075 101")
    }

    func testMatchingComparesContent() {
        XCTAssertTrue(ShortAuthenticationCode.matches("123456", "123456"))
        XCTAssertFalse(ShortAuthenticationCode.matches("123456", "123457"))
        XCTAssertFalse(ShortAuthenticationCode.matches("123456", "12345"))
    }

    func testDistributionIsNotPathological() {
        // A guard against a derivation that collapses to a handful of values, which would make the
        // code useless as a comparison.
        let android = hex("3082010a0a0b0c0d0e0f")
        var codes = Set<String>()
        for i in 0..<500 {
            let identity = Data([UInt8(i & 0xFF), UInt8((i >> 8) & 0xFF)])
            codes.insert(ShortAuthenticationCode.derive(identity, android))
        }
        XCTAssertGreaterThan(codes.count, 400, "only \(codes.count) distinct codes in 500 draws")
    }
}
