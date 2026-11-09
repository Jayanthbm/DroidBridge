import AVFoundation
import CoreMedia
import XCTest
@testable import DroidBridge

final class H264AnnexBTests: XCTestCase {

    private var fixtureURL: URL {
        URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .appendingPathComponent("../Resources/p07-test.h264")
    }

    func testFixtureParsesAndProducesAccessUnits() throws {
        let data = try Data(contentsOf: fixtureURL)
        let nals = try H264AnnexB.parse(data)
        XCTAssertFalse(nals.isEmpty)
        XCTAssertTrue(nals.contains { $0.type == 7 }, "fixture must contain SPS")
        XCTAssertTrue(nals.contains { $0.type == 8 }, "fixture must contain PPS")
        XCTAssertTrue(nals.contains { $0.type == 5 }, "fixture must contain an IDR frame")

        let sets = try H264AnnexB.parameterSets(from: nals)
        XCTAssertEqual(sets.sps.first.map { $0 & 0x1F }, 7)
        XCTAssertEqual(sets.pps.first.map { $0 & 0x1F }, 8)

        let units = H264AnnexB.accessUnits(from: nals, frameDurationUs: 16_666)
        XCTAssertGreaterThanOrEqual(units.count, 30)
        XCTAssertTrue(units.first?.isKeyframe == true)
        let pts = units.map(\.ptsUs)
        XCTAssertNoThrow(try H264AnnexB.validateStrictlyIncreasing(pts))
    }

    func testLengthPrefixedConversion() throws {
        let nals = [
            H264NAL(type: 7, bytes: [0x67, 0x42, 0xC0]),
            H264NAL(type: 8, bytes: [0x68, 0xCB]),
        ]
        let data = H264AnnexB.lengthPrefixed(nals)
        XCTAssertEqual(Array(data.prefix(4)), [0, 0, 0, 3])
        XCTAssertEqual(Array(data[4..<7]), [0x67, 0x42, 0xC0])
        XCTAssertEqual(Array(data[7..<11]), [0, 0, 0, 2])
        XCTAssertEqual(Array(data[11..<13]), [0x68, 0xCB])
    }

    func testEmulationPreventionBytesArePreserved() throws {
        // Start code, NAL header 0x65 (IDR), payload containing 00 00 03 01, then a second NAL.
        let bytes: [UInt8] = [
            0, 0, 1, 0x65, 0x88, 0x00, 0x00, 0x03, 0x01, 0x80,
            0, 0, 0, 1, 0x41, 0x9A, 0x00, 0x00,
        ]
        let nals = try H264AnnexB.parse(Data(bytes))
        XCTAssertEqual(nals.count, 2)
        XCTAssertEqual(nals[0].type, 5)
        XCTAssertEqual(Array(nals[0].bytes[2..<5]), [0x00, 0x00, 0x03])
        XCTAssertEqual(nals[1].type, 1)
        // Trailing Annex-B zero bytes are stripped.
        XCTAssertEqual(nals[1].bytes.last, 0x9A)
    }

    func testParameterSetsWithStartCodesAreRejected() throws {
        let sps: [UInt8] = [0, 0, 0, 1, 0x67, 0x42]
        let pps: [UInt8] = [0, 0, 1, 0x68, 0xCB]
        XCTAssertThrowsError(try H264AnnexB.makeFormatDescription(sps: sps, pps: pps)) { error in
            XCTAssertEqual(error as? H264Error, .parameterSetHasStartCode)
        }
    }

    func testWrongNalLengthSizeIsRejected() throws {
        let data = try Data(contentsOf: fixtureURL)
        let nals = try H264AnnexB.parse(data)
        let sets = try H264AnnexB.parameterSets(from: nals)
        XCTAssertThrowsError(
            try H264AnnexB.makeFormatDescription(sps: sets.sps, pps: sets.pps, nalLengthSize: 2)
        ) { error in
            XCTAssertEqual(error as? H264Error, .unsupportedNalLengthSize(2))
        }
    }

    func testFormatDescriptionAndSampleBufferBuild() throws {
        let data = try Data(contentsOf: fixtureURL)
        let nals = try H264AnnexB.parse(data)
        let sets = try H264AnnexB.parameterSets(from: nals)
        let format = try H264AnnexB.makeFormatDescription(sps: sets.sps, pps: sets.pps)
        XCTAssertEqual(CMVideoFormatDescriptionGetDimensions(format).width, 640)
        XCTAssertEqual(CMVideoFormatDescriptionGetDimensions(format).height, 360)

        let units = H264AnnexB.accessUnits(from: nals, frameDurationUs: 16_666)
        let sample = try H264AnnexB.makeSampleBuffer(
            for: units[0], format: format, frameDurationUs: 16_666
        )
        XCTAssertEqual(CMSampleBufferGetNumSamples(sample), 1)
        XCTAssertTrue(CMSampleBufferIsValid(sample))
        XCTAssertEqual(CMSampleBufferGetPresentationTimeStamp(sample).value, 0)
        XCTAssertTrue(
            CMSampleBufferGetSampleAttachmentsArray(sample, createIfNecessary: false) != nil
        )
    }

    func testTimingRejectsDuplicateAndNonMonotonicPTS() {
        XCTAssertThrowsError(try H264AnnexB.validateStrictlyIncreasing([0, 1, 1]))
        XCTAssertThrowsError(try H264AnnexB.validateStrictlyIncreasing([0, 2, 1]))
        XCTAssertNoThrow(try H264AnnexB.validateStrictlyIncreasing([0, 1, 2]))
    }
}
