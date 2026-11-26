import XCTest
@testable import DroidBridge

final class AudioPayloadTests: XCTestCase {

    private func formatJSON(
        codec: String = "aac-lc",
        sampleRate: Int = 44_100,
        channels: Int = 2,
        bitrate: Int = 128_000,
        asc: String = "EhA="
    ) -> Data {
        let json = """
        {"codec":"\(codec)","sampleRate":\(sampleRate),"channels":\(channels),"bitrate":\(bitrate),"audioSpecificConfig":"\(asc)"}
        """
        return Data(json.utf8)
    }

    func testParsesTheKotlinEncoding() throws {
        let format = try AudioFormatPayload.parse(formatJSON())
        XCTAssertEqual(format.codec, "aac-lc")
        XCTAssertEqual(format.sampleRate, 44_100)
        XCTAssertEqual(format.channels, 2)
        XCTAssertEqual(format.bitrate, 128_000)
        XCTAssertEqual([UInt8](format.audioSpecificConfig), [0x12, 0x10])
    }

    func testRejectsAnUnsupportedCodec() {
        XCTAssertThrowsError(try AudioFormatPayload.parse(formatJSON(codec: "opus"))) { error in
            guard case AudioFormatPayload.ParseError.unsupportedCodec = error else {
                return XCTFail("unexpected error \(error)")
            }
        }
    }

    func testRejectsAnOutOfRangeSampleRate() {
        XCTAssertThrowsError(try AudioFormatPayload.parse(formatJSON(sampleRate: 96_000)))
    }

    func testRejectsAnEmptyConfiguration() {
        XCTAssertThrowsError(try AudioFormatPayload.parse(formatJSON(asc: "")))
    }

    func testRejectsMalformedJSON() {
        XCTAssertThrowsError(try AudioFormatPayload.parse(Data("not json".utf8)))
    }

    func testParsesAFrameWithBigEndianTimestamp() throws {
        var payload = Data([0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x30, 0x39])  // 12345
        payload.append(contentsOf: [0x21, 0x10, 0x05, 0x60])

        let frame = try AudioFramePayload.parse(payload)
        XCTAssertEqual(frame.ptsUs, 12_345)
        XCTAssertEqual([UInt8](frame.accessUnit), [0x21, 0x10, 0x05, 0x60])
    }

    func testRejectsAFrameWithNoAccessUnit() {
        XCTAssertThrowsError(try AudioFramePayload.parse(Data(repeating: 0, count: 8))) { error in
            guard case AudioFramePayload.ParseError.tooShort = error else {
                return XCTFail("unexpected error \(error)")
            }
        }
    }

    func testRejectsAFrameWithTheSignBitSet() {
        var payload = Data([0x80, 0, 0, 0, 0, 0, 0, 0])
        payload.append(0x01)
        XCTAssertThrowsError(try AudioFramePayload.parse(payload)) { error in
            guard case AudioFramePayload.ParseError.negativeTimestamp = error else {
                return XCTFail("unexpected error \(error)")
            }
        }
    }
}
