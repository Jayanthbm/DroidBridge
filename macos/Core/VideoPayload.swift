import Foundation

/// P08 — parse the wire payload of a `video` (0x20) message.
///
/// Wire shape: 8-byte big-endian signed presentation timestamp (microseconds), followed by the
/// H.264 access unit in AVCC form (4-byte length-prefixed NALs). The sender guarantees AVCC, not
/// Annex-B; the receiver validates length-prefix structure before enqueueing.
public struct VideoPayload {
    public let ptsUs: Int64
    public let avcc: Data
    public let isKeyframe: Bool

    public enum ParseError: Error, Equatable {
        case tooShort(Int)
        case truncatedNal(available: Int, declared: Int)
        case invalidNalLength(Int)
        case invalidNalHeader(UInt8)
    }

    public static func parse(_ payload: Data) throws -> VideoPayload {
        guard payload.count >= 8 else { throw ParseError.tooShort(payload.count) }
        var rawPTS: UInt64 = 0
        for i in 0..<8 {
            rawPTS = (rawPTS << 8) | UInt64(payload[payload.startIndex.advanced(by: i)])
        }
        let pts = Int64(bitPattern: rawPTS)
        let avcc = Data(payload.dropFirst(8))
        let isKeyframe = try validateAvcc(avcc)
        return VideoPayload(ptsUs: pts, avcc: avcc, isKeyframe: isKeyframe)
    }

    /// Validates each length-prefixed NAL and reports whether the access unit contains an IDR.
    /// This is a bounded structural check, not an H.264 decoder; VideoToolbox still validates the
    /// slice contents. Invalid framing is rejected before a sample buffer is created.
    @discardableResult
    public static func validateAvcc(_ data: Data) throws -> Bool {
        guard !data.isEmpty else { throw ParseError.tooShort(0) }
        var offset = data.startIndex
        var nalCount = 0
        var containsIDR = false
        while offset < data.endIndex {
            let remaining = data.distance(from: offset, to: data.endIndex)
            guard remaining >= 4 else {
                throw ParseError.truncatedNal(available: remaining, declared: 4)
            }
            let b0 = UInt32(data[offset])
            let b1 = UInt32(data[data.index(offset, offsetBy: 1)])
            let b2 = UInt32(data[data.index(offset, offsetBy: 2)])
            let b3 = UInt32(data[data.index(offset, offsetBy: 3)])
            let length = Int((b0 << 24) | (b1 << 16) | (b2 << 8) | b3)
            offset = data.index(offset, offsetBy: 4)
            guard length > 0 else { throw ParseError.invalidNalLength(length) }
            let after = data.distance(from: offset, to: data.endIndex)
            guard after >= length else {
                throw ParseError.truncatedNal(available: after, declared: length)
            }
            let nalHeader = data[offset]
            let nalType = nalHeader & 0x1F
            // Access units may include SEI, AUD, end markers, or filler alongside VCL NALs.
            // Parameter sets belong in `mirror.format`; reserved/unspecified types are rejected.
            let supportedType = (1...6).contains(nalType) || (9...12).contains(nalType)
            guard nalHeader & 0x80 == 0, supportedType else {
                throw ParseError.invalidNalHeader(nalHeader)
            }
            containsIDR = containsIDR || nalType == 5
            offset = data.index(offset, offsetBy: length)
            nalCount += 1
        }
        if nalCount == 0 { throw ParseError.tooShort(data.count) }
        return containsIDR
    }
}
