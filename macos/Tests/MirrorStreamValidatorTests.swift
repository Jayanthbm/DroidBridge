import XCTest
@testable import DroidBridge

final class MirrorStreamValidatorTests: XCTestCase {

    func testStreamStartRequiresAnIDR() throws {
        var validator = MirrorStreamValidator()
        validator.streamStarted()

        XCTAssertThrowsError(try validator.validate(ptsUs: 0, isIDR: false)) { error in
            XCTAssertEqual(
                error as? MirrorStreamValidator.Error,
                .expectedIDR(after: "mirror.started", ptsUs: 0)
            )
        }
    }

    func testFormatChangeRequiresANewIDR() throws {
        var validator = MirrorStreamValidator()
        validator.streamStarted()
        XCTAssertEqual(try validator.validate(ptsUs: 1, isIDR: true), .enqueue)

        validator.formatChanged()
        XCTAssertThrowsError(try validator.validate(ptsUs: 0, isIDR: false)) { error in
            XCTAssertEqual(
                error as? MirrorStreamValidator.Error,
                .expectedIDR(after: "mirror.format", ptsUs: 0)
            )
        }
        XCTAssertEqual(try validator.validate(ptsUs: 1, isIDR: true), .enqueue)
    }

    func testDecoderRecoveryDropsInterFramesUntilIDR() throws {
        var validator = MirrorStreamValidator()
        validator.streamStarted()
        XCTAssertEqual(try validator.validate(ptsUs: 1, isIDR: true), .enqueue)

        validator.decoderFlushed()
        XCTAssertEqual(try validator.validate(ptsUs: 2, isIDR: false), .waitForRecoveryIDR)
        XCTAssertEqual(try validator.validate(ptsUs: 3, isIDR: false), .waitForRecoveryIDR)
        XCTAssertEqual(try validator.validate(ptsUs: 4, isIDR: true), .enqueue)
        XCTAssertEqual(try validator.validate(ptsUs: 5, isIDR: false), .enqueue)
    }

    func testNegativePTSIsRejected() throws {
        var validator = MirrorStreamValidator()
        validator.streamStarted()
        XCTAssertThrowsError(try validator.validate(ptsUs: -1, isIDR: true)) { error in
            XCTAssertEqual(error as? MirrorStreamValidator.Error, .negativePTS(-1))
        }
    }

    func testDuplicateAndNonMonotonicPTSAreRejected() throws {
        var validator = MirrorStreamValidator()
        validator.streamStarted()
        XCTAssertEqual(try validator.validate(ptsUs: 10, isIDR: true), .enqueue)

        XCTAssertThrowsError(try validator.validate(ptsUs: 10, isIDR: false)) { error in
            XCTAssertEqual(
                error as? MirrorStreamValidator.Error,
                .nonIncreasingPTS(previous: 10, current: 10)
            )
        }
        XCTAssertThrowsError(try validator.validate(ptsUs: 9, isIDR: false)) { error in
            XCTAssertEqual(
                error as? MirrorStreamValidator.Error,
                .nonIncreasingPTS(previous: 10, current: 9)
            )
        }
    }

    func testNewStreamResetsPTSAndIDRState() throws {
        var validator = MirrorStreamValidator()
        validator.streamStarted()
        XCTAssertEqual(try validator.validate(ptsUs: 100, isIDR: true), .enqueue)
        validator.streamStarted()
        XCTAssertEqual(try validator.validate(ptsUs: 0, isIDR: true), .enqueue)
    }
}
