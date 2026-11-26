import Foundation

/// Payload of one `audio` message: `[8-byte big-endian ptsUs][AAC access unit]`.
///
/// Mirrors `AudioPayload.kt`. The timestamp is the phone encoder's presentation time; it is carried
/// with the frame so an access unit and its time cannot be separated by a decoder that reorders or
/// drops one of them.
public struct AudioFramePayload: Equatable {

    public enum ParseError: Error, CustomStringConvertible {
        case tooShort(Int)
        case negativeTimestamp

        public var description: String {
            switch self {
            case .tooShort(let size): return "audio payload is \(size) bytes; needs 9 or more"
            case .negativeTimestamp: return "audio ptsUs has the sign bit set"
            }
        }
    }

    public static let headerBytes = 8

    public let ptsUs: Int64
    public let accessUnit: Data

    public init(ptsUs: Int64, accessUnit: Data) {
        self.ptsUs = ptsUs
        self.accessUnit = accessUnit
    }

    public static func parse(_ payload: Data) throws -> AudioFramePayload {
        guard payload.count > headerBytes else { throw ParseError.tooShort(payload.count) }

        var pts: Int64 = 0
        for index in 0..<headerBytes {
            pts = (pts << 8) | Int64(payload[payload.startIndex + index])
        }
        guard pts >= 0 else { throw ParseError.negativeTimestamp }

        return AudioFramePayload(ptsUs: pts, accessUnit: payload.dropFirst(headerBytes))
    }
}
