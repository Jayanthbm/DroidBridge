import Foundation

/// Length-prefixed framing for the control channel, matching `shared/protocol/session/Framing.kt`.
///
/// The Mac and the Android app are the two ends of one wire format that exists twice, so the rules
/// below are deliberately the same rules, not a second interpretation of them:
///
/// - wire shape `[4-byte big-endian unsigned length][payload]`;
/// - the length is validated **before** any allocation, so a peer announcing 2 GiB is rejected on
///   the header and no buffer is reserved for it;
/// - zero is invalid (a frame must carry a control message);
/// - after any violation the decoder latches failed and refuses later feeds.
///
/// The pinned vectors in `SessionFramingTests` come from the Kotlin tests, so a drift between the
/// two implementations fails a test instead of silently failing a pairing.
public enum SessionFraming {

    /// Bytes in the length prefix.
    public static let headerBytes = 4

    /// Largest accepted control payload. Matches `Framing.MAX_CONTROL_PAYLOAD_BYTES`.
    public static let maxPayloadBytes = 1 << 20

    /// Thrown by every framing violation.
    public enum Error: Swift.Error, Equatable, CustomStringConvertible {
        case zeroLength
        case tooLarge(declared: UInt32)
        case failed(String)

        public var description: String {
            switch self {
            case .zeroLength:
                return "zero-length frame: a frame must carry a payload"
            case .tooLarge(let declared):
                return "declared payload \(declared) bytes exceeds the \(maxPayloadBytes) byte limit"
            case .failed(let reason):
                return "framing already failed: \(reason)"
            }
        }
    }

    /// Validates a declared payload length coming straight off the wire.
    public static func validate(declared: UInt32) throws {
        if declared == 0 {
            throw Error.zeroLength
        }
        if Int(declared) > maxPayloadBytes {
            throw Error.tooLarge(declared: declared)
        }
    }

    /// Encodes one frame: prefix plus payload.
    public static func encodeFrame(_ payload: Data) throws -> Data {
        guard !payload.isEmpty else { throw Error.zeroLength }
        guard payload.count <= maxPayloadBytes else {
            throw Error.tooLarge(declared: UInt32(clamping: payload.count))
        }
        var out = Data(capacity: headerBytes + payload.count)
        let length = UInt32(payload.count)
        out.append(UInt8((length >> 24) & 0xFF))
        out.append(UInt8((length >> 16) & 0xFF))
        out.append(UInt8((length >> 8) & 0xFF))
        out.append(UInt8(length & 0xFF))
        out.append(payload)
        return out
    }
}

/// Incremental frame decoder over a byte stream.
///
/// TCP delivers bytes, not messages: one read can hold half a header, three frames, or a header
/// plus part of a payload. One decoder must live for the whole connection; creating one per read
/// would drop whatever it had already buffered.
public struct FrameDecoder {

    private var buffer = Data()
    private var expected: Int?
    private var failure: SessionFraming.Error?

    public init() {}

    /// True once the stream is unrecoverable.
    public var isFailed: Bool { failure != nil }

    /// Bytes buffered toward the next frame.
    public var pendingBytes: Int { buffer.count }

    /// Feeds bytes and returns every frame completed by them, in order.
    public mutating func feed(_ chunk: Data) throws -> [Data] {
        if let failure { throw SessionFraming.Error.failed(failure.description) }
        buffer.append(chunk)

        var frames: [Data] = []
        while true {
            if let need = expected {
                guard buffer.count >= need else { break }
                frames.append(buffer.prefix(need))
                buffer.removeFirst(need)
                expected = nil
                continue
            }

            guard buffer.count >= SessionFraming.headerBytes else { break }
            let header = buffer.prefix(SessionFraming.headerBytes)
            let declared = header.reduce(UInt32(0)) { ($0 << 8) | UInt32($1) }
            buffer.removeFirst(SessionFraming.headerBytes)

            do {
                try SessionFraming.validate(declared: declared)
            } catch let error as SessionFraming.Error {
                failure = error
                throw error
            }

            expected = Int(declared)
        }
        return frames
    }
}
