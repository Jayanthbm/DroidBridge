import XCTest
@testable import DroidBridge

/// The trust decision is one pure function so the TLS verify block and the post-handshake guard
/// cannot disagree again (ADR-055). The regression these tests exist for: with one phone already
/// confirmed, a second phone's unknown pin was refused at the handshake, so the set from ADR-054
/// could only ever hold the phone that happened to pair first.
final class PeerTrustPolicyTests: XCTestCase {

    private let phonePin = "f68f9062cca91ecc7d61b5421a65cfeb041c90be876fb9ab55f82688444041c9"
    private let secondPhonePin = "7eded50e1e00ad22e3ad2ed50559a150db19f6b3a36f60670f60f44f2207d8f7"

    func testConfirmedPinIsTrusted() {
        let decision = PeerTrustPolicy.decide(
            pin: phonePin,
            confirmedPins: [phonePin],
            explicitPin: nil
        )
        XCTAssertEqual(decision, .trusted)
    }

    func testUnknownPinIsACandidateEvenWhenAnotherPhoneIsConfirmed() {
        let decision = PeerTrustPolicy.decide(
            pin: secondPhonePin,
            confirmedPins: [phonePin],
            explicitPin: nil
        )
        XCTAssertEqual(decision, .candidate, "a second phone must reach the code comparison, not a dead handshake")
    }

    func testUnknownPinWithNoConfirmedPinsIsACandidate() {
        XCTAssertEqual(
            PeerTrustPolicy.decide(pin: phonePin, confirmedPins: [], explicitPin: nil),
            .candidate
        )
    }

    func testBothPhonesAreTrustedOnceBothAreInTheSet() {
        let pins: Set<String> = [phonePin, secondPhonePin]
        XCTAssertEqual(PeerTrustPolicy.decide(pin: phonePin, confirmedPins: pins, explicitPin: nil), .trusted)
        XCTAssertEqual(PeerTrustPolicy.decide(pin: secondPhonePin, confirmedPins: pins, explicitPin: nil), .trusted)
    }

    func testForgettingLeavesNoTrustedPins() {
        // forgetAll empties the set, so the next connection is a candidate again — the same
        // shape as a first pairing, with a code to compare.
        XCTAssertEqual(
            PeerTrustPolicy.decide(pin: phonePin, confirmedPins: [], explicitPin: nil),
            .candidate
        )
    }

    func testExplicitPinModeStillRefusesADifferentIdentity() {
        XCTAssertEqual(
            PeerTrustPolicy.decide(pin: phonePin, confirmedPins: [], explicitPin: secondPhonePin),
            .refused
        )
    }

    func testExplicitPinModeTrustsAnExactMatch() {
        XCTAssertEqual(
            PeerTrustPolicy.decide(pin: phonePin, confirmedPins: [], explicitPin: phonePin),
            .trusted
        )
    }
}