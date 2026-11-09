import XCTest
@testable import DroidBridge

/// Cross-language wire-format tests.
///
/// Every hex vector here also appears in `shared/protocol/.../ControlMessageTest.kt`. The format is
/// implemented twice, deliberately, so the only thing keeping the two ends compatible is a pair of
/// tests that assert the same bytes. If either implementation changes shape, one suite fails.
final class SessionCodecTests: XCTestCase {

    // MARK: - Vectors shared with the Kotlin suite

    private let pingSequenceOneHex = "010100000001"
    private let pongSequenceMultibyteHex = "0102010203046f6b"

    func testPingVectorEncodesToTheKotlinBytes() throws {
        let encoded = try ControlMessageCodec.encode(type: .ping, sequence: 1)
        XCTAssertEqual(encoded.hexString, pingSequenceOneHex)
    }

    func testPongVectorWithMultibyteSequenceAndPayload() throws {
        let encoded = try ControlMessageCodec.encode(
            type: .pong,
            sequence: 0x0102_0304,
            payload: Data("ok".utf8)
        )
        XCTAssertEqual(encoded.hexString, pongSequenceMultibyteHex)
    }

    func testDecodesTheKotlinBytes() throws {
        let ping = try ControlMessageCodec.decode(Data(hex: pingSequenceOneHex)!)
        XCTAssertEqual(ping.type, .ping)
        XCTAssertEqual(ping.sequence, 1)
        XCTAssertTrue(ping.payload.isEmpty)

        let pong = try ControlMessageCodec.decode(Data(hex: pongSequenceMultibyteHex)!)
        XCTAssertEqual(pong.type, .pong)
        XCTAssertEqual(pong.sequence, 0x0102_0304)
        XCTAssertEqual(pong.payload, Data("ok".utf8))
    }

    func testRoundTripPreservesPayload() throws {
        let payload = Data([0x00, 0xFF, 0x10, 0x20])
        let encoded = try ControlMessageCodec.encode(type: .close, sequence: 99, payload: payload)
        let decoded = try ControlMessageCodec.decode(encoded)
        XCTAssertEqual(decoded, ControlMessage(type: .close, sequence: 99, payload: payload))
    }

    // MARK: - Control codec rejections

    func testRejectsSequenceZero() {
        XCTAssertThrowsError(try ControlMessageCodec.encode(type: .ping, sequence: 0)) { error in
            XCTAssertEqual(error as? ControlError, .sequenceZero)
        }
        XCTAssertThrowsError(try ControlMessageCodec.decode(Data(hex: "010100000000")!)) { error in
            XCTAssertEqual(error as? ControlError, .sequenceZero)
        }
    }

    func testRejectsWrongVersion() {
        XCTAssertThrowsError(try ControlMessageCodec.decode(Data(hex: "020100000001")!)) { error in
            XCTAssertEqual(error as? ControlError, .version(2))
        }
    }

    func testRejectsUnknownType() {
        XCTAssertThrowsError(try ControlMessageCodec.decode(Data(hex: "01ff00000001")!)) { error in
            XCTAssertEqual(error as? ControlError, .type(0xFF))
        }
    }

    func testRejectsTruncatedHeader() {
        XCTAssertThrowsError(try ControlMessageCodec.decode(Data(hex: "01010000")!)) { error in
            XCTAssertEqual(error as? ControlError, .short(buffer: 4))
        }
    }

    // MARK: - Replay window

    func testReplayWindowAcceptsIncreasingOnly() throws {
        var window = ReplayWindow()
        try window.accept(1)
        try window.accept(2)
        try window.accept(0xFFFF_FFFF)

        XCTAssertThrowsError(try window.accept(0xFFFF_FFFF))
        XCTAssertThrowsError(try window.accept(1))
        XCTAssertEqual(window.highest, 0xFFFF_FFFF)
    }

    func testReplayWindowStartsEmptySoSequenceOneIsFresh() throws {
        var window = ReplayWindow()
        XCTAssertEqual(window.highest, 0)
        XCTAssertThrowsError(try window.accept(0))
        try window.accept(1)
    }

    // MARK: - Framing

    func testEncodesFramedPing() throws {
        let framed = try SessionFraming.encodeFrame(Data(hex: pingSequenceOneHex)!)
        XCTAssertEqual(framed.hexString, "00000006\(pingSequenceOneHex)")
    }

    func testDecoderReassemblesByteAtATime() throws {
        var decoder = FrameDecoder()
        let framed = try SessionFraming.encodeFrame(Data(hex: pingSequenceOneHex)!)

        var frames: [Data] = []
        for byte in framed {
            frames.append(contentsOf: try decoder.feed(Data([byte])))
        }
        XCTAssertEqual(frames.count, 1)
        XCTAssertEqual(frames[0].hexString, pingSequenceOneHex)
    }

    func testDecoderSplitsTwoFramesFromOneRead() throws {
        var decoder = FrameDecoder()
        let first = try SessionFraming.encodeFrame(Data(hex: pingSequenceOneHex)!)
        let second = try SessionFraming.encodeFrame(Data(hex: pongSequenceMultibyteHex)!)

        let frames = try decoder.feed(first + second)
        XCTAssertEqual(frames.map(\.hexString), [pingSequenceOneHex, pongSequenceMultibyteHex])
    }

    func testDecoderKeepsPartialPayloadUntilCompleted() throws {
        var decoder = FrameDecoder()
        let framed = try SessionFraming.encodeFrame(Data(hex: pongSequenceMultibyteHex)!)

        // Header plus three of eight payload bytes.
        let head = framed.prefix(7)
        XCTAssertTrue(try decoder.feed(head).isEmpty)
        XCTAssertEqual(decoder.pendingBytes, 3)

        let tail = framed.dropFirst(7)
        let frames = try decoder.feed(Data(tail))
        XCTAssertEqual(frames.first?.hexString, pongSequenceMultibyteHex)
    }

    func testRejectsZeroLengthFrameOnHeaderOnly() {
        var decoder = FrameDecoder()
        XCTAssertThrowsError(try decoder.feed(Data(hex: "00000000")!)) { error in
            XCTAssertEqual(error as? SessionFraming.Error, .zeroLength)
        }
        XCTAssertTrue(decoder.isFailed)
    }

    func testRejectsOversizeLengthBeforeAllocating() {
        var decoder = FrameDecoder()
        // 2 GiB declared: must fail on the header, never waiting for bytes that will not come.
        XCTAssertThrowsError(try decoder.feed(Data(hex: "80000000")!)) { error in
            XCTAssertEqual(error as? SessionFraming.Error, .tooLarge(declared: 0x8000_0000))
        }
        XCTAssertTrue(decoder.isFailed)
    }

    func testDecoderStaysFailedAfterViolation() {
        var decoder = FrameDecoder()
        XCTAssertThrowsError(try decoder.feed(Data(hex: "00000000")!))
        XCTAssertThrowsError(try decoder.feed(Data(hex: "00000006010100000001")!))
        XCTAssertTrue(decoder.isFailed)
    }

    func testAcceptsMaximumSizedPayload() throws {
        let payload = Data(repeating: 0x41, count: SessionFraming.maxPayloadBytes)
        let framed = try SessionFraming.encodeFrame(payload)
        var decoder = FrameDecoder()
        let frames = try decoder.feed(framed)
        XCTAssertEqual(frames.first?.count, SessionFraming.maxPayloadBytes)
    }
}

// MARK: - Hex helpers

extension Data {
    /// Parses an even-length hex string, so wire vectors can be written the way the Kotlin tests
    /// write them.
    init?(hex: String) {
        let clean = hex.filter { !$0.isWhitespace }
        guard clean.count % 2 == 0 else { return nil }
        var bytes: [UInt8] = []
        bytes.reserveCapacity(clean.count / 2)
        var index = clean.startIndex
        while index < clean.endIndex {
            let next = clean.index(index, offsetBy: 2)
            guard let byte = UInt8(clean[index..<next], radix: 16) else { return nil }
            bytes.append(byte)
            index = next
        }
        self = Data(bytes)
    }

    var hexString: String {
        map { String(format: "%02x", $0) }.joined()
    }
}
