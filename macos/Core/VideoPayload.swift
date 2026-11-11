import Foundation

/// P08 — parse the wire payload of a `video` (0x20) message.
///
/// Wire shape: 8-byte big-endian signed presentation timestamp (microseconds), followed by the
/// H.264 access unit in AVCC form (4-byte length-prefixed NALs). The sender guarantees AVCC, not
/// Annex-B; the receiver validates length-prefix structure before enqueueing.
public struct VideoPayload {
    public let ptsUs: Int64
    public let avcc: Data

    public enum ParseError: Error, Equatable {
        case tooShort(Int)
        case truncatedNal(available: Int, declared: Int)
        case invalidNalLength(Int)
    }

    public static func parse(_ payload: Data) throws -> VideoPayload {
        guard payload.count >= 8 else { throw ParseError.tooShort(payload.count) }
        var pts: Int64 = 0
        for i in 0..<8 {
            pts = (pts << 8) | Int64(payload[payload.startIndex.advanced(by: i)])
        }
        let avcc = Data(payload.dropFirst(8))
        try validateAvcc(avcc)
        return VideoPayload(ptsUs: pts, avcc: avcc)
    }

    public static func validateAvcc(_ data: Data) throws {
        guard !data.isEmpty else { throw ParseError.tooShort(0) }
        var offset = data.startIndex
        var nalCount = 0
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
            offset = data.index(offset, offsetBy: length)
            nalCount += 1
        }
        if nalCount == 0 { throw ParseError.tooShort(data.count) }
    }
}
