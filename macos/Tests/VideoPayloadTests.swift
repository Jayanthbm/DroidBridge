import Foundation
import XCTest
@testable import DroidBridge

final class VideoPayloadTests: XCTestCase {

    private func payload(ptsUs: Int64, avcc: [UInt8]) -> Data {
        var bytes = withUnsafeBytes(of: ptsUs.bigEndian, Array.init)
        bytes.append(contentsOf: avcc)
        return Data(bytes)
    }

    func testParsesSignedBigEndianPTSAndDetectsIDR() throws {
        let data = payload(ptsUs: 42, avcc: [0, 0, 0, 2, 0x65, 0x80])
        let parsed = try VideoPayload.parse(data)
        XCTAssertEqual(parsed.ptsUs, 42)
        XCTAssertTrue(parsed.isKeyframe)
        XCTAssertEqual(Array(parsed.avcc), [0, 0, 0, 2, 0x65, 0x80])
    }

    func testParsesNegativePTSWithoutIntegerOverflow() throws {
        let data = payload(ptsUs: -1, avcc: [0, 0, 0, 2, 0x41, 0x80])
        let parsed = try VideoPayload.parse(data)
        XCTAssertEqual(parsed.ptsUs, -1)
        XCTAssertFalse(parsed.isKeyframe)
    }

    func testAcceptsNonIDRAccessUnit() throws {
        let parsed = try VideoPayload.parse(payload(ptsUs: 7, avcc: [0, 0, 0, 2, 0x41, 0x80]))
        XCTAssertFalse(parsed.isKeyframe)
    }

    func testAcceptsAncillarySEIAndAUDNALUnitsWithVCL() throws {
        let avcc: [UInt8] = [
            0, 0, 0, 2, 0x09, 0xF0, // access unit delimiter
            0, 0, 0, 2, 0x06, 0x80, // supplemental enhancement information
            0, 0, 0, 2, 0x41, 0x80, // non-IDR slice
        ]
        let parsed = try VideoPayload.parse(payload(ptsUs: 7, avcc: avcc))
        XCTAssertFalse(parsed.isKeyframe)
    }

    func testDetectsIDRAmongMultipleNALUnits() throws {
        let avcc: [UInt8] = [
            0, 0, 0, 2, 0x41, 0x80,
            0, 0, 0, 2, 0x65, 0x80,
        ]
        XCTAssertTrue(try VideoPayload.validateAvcc(Data(avcc)))
    }

    func testRejectsZeroLengthNAL() {
        XCTAssertThrowsError(try VideoPayload.parse(payload(ptsUs: 0, avcc: [0, 0, 0, 0]))) { error in
            XCTAssertEqual(error as? VideoPayload.ParseError, .invalidNalLength(0))
        }
    }

    func testRejectsTruncatedLengthPrefix() {
        XCTAssertThrowsError(try VideoPayload.parse(payload(ptsUs: 0, avcc: [0, 0, 0]))) { error in
            XCTAssertEqual(error as? VideoPayload.ParseError, .truncatedNal(available: 3, declared: 4))
        }
    }

    func testRejectsTruncatedNALPayload() {
        XCTAssertThrowsError(try VideoPayload.parse(payload(ptsUs: 0, avcc: [0, 0, 0, 3, 0x65, 0x80]))) { error in
            XCTAssertEqual(error as? VideoPayload.ParseError, .truncatedNal(available: 2, declared: 3))
        }
    }

    func testRejectsForbiddenZeroBitParameterSetAndReservedNALs() {
        XCTAssertThrowsError(try VideoPayload.parse(payload(ptsUs: 0, avcc: [0, 0, 0, 1, 0xE5]))) { error in
            XCTAssertEqual(error as? VideoPayload.ParseError, .invalidNalHeader(0xE5))
        }
        XCTAssertThrowsError(try VideoPayload.parse(payload(ptsUs: 0, avcc: [0, 0, 0, 1, 0x67]))) { error in
            XCTAssertEqual(error as? VideoPayload.ParseError, .invalidNalHeader(0x67))
        }
        XCTAssertThrowsError(try VideoPayload.parse(payload(ptsUs: 0, avcc: [0, 0, 0, 1, 0x0D]))) { error in
            XCTAssertEqual(error as? VideoPayload.ParseError, .invalidNalHeader(0x0D))
        }
    }

    func testRejectsEmptyAccessUnitAndShortTimestamp() {
        XCTAssertThrowsError(try VideoPayload.parse(payload(ptsUs: 0, avcc: []))) { error in
            XCTAssertEqual(error as? VideoPayload.ParseError, .tooShort(0))
        }
        XCTAssertThrowsError(try VideoPayload.parse(Data([0, 1, 2]))) { error in
            XCTAssertEqual(error as? VideoPayload.ParseError, .tooShort(3))
        }
    }
}
