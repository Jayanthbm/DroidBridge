import Foundation

/// Control message types. Wire codes must match `ControlMessage.kt`.
///
/// The mirroring family and `video` were added by P08 and travel on the same authenticated
/// connection as the session messages. `video` is media, not control: it is paced and rate-limited
/// on its own budget on both ends, because a 30 fps stream would exhaust a control-frame budget in
/// its first second.
public enum ControlMessageType: UInt8, Equatable {
    case ping = 0x01
    case pong = 0x02
    case close = 0x03

    case mirrorStarted = 0x10
    case mirrorFormat = 0x11
    case mirrorStop = 0x12
    case mirrorKeyframeRequest = 0x13

    case video = 0x20

    public var label: String {
        switch self {
        case .ping: return "ping"
        case .pong: return "pong"
        case .close: return "close"
        case .mirrorStarted: return "mirror.started"
        case .mirrorFormat: return "mirror.format"
        case .mirrorStop: return "mirror.stop"
        case .mirrorKeyframeRequest: return "mirror.keyframe_request"
        case .video: return "video"
        }
    }

    /// True for media, which is paced and limited separately from control traffic.
    public var isMedia: Bool { self == .video }
}

/// One decoded control message: `[version][type][4-byte sequence][payload]`.
public struct ControlMessage: Equatable {
    public let type: ControlMessageType
    public let sequence: UInt32
    public let payload: Data

    public init(type: ControlMessageType, sequence: UInt32, payload: Data = Data()) {
        self.type = type
        self.sequence = sequence
        self.payload = payload
    }

    /// Bounded, redacted description for logs: never dumps binary payloads wholesale.
    public var summary: String {
        if payload.isEmpty { return "\(type.label):\(sequence)" }
        // Media is never decoded into a string for logging, not even to test printability: a log
        // line must not be a place where a slice of someone's screen can appear (P10).
        if type.isMedia {
            return "\(type.label):\(sequence):<\(payload.count) bytes>"
        }
        let text = String(decoding: payload, as: UTF8.self)
        let printable = payload.allSatisfy { $0 >= 0x20 && $0 <= 0x7E }
        if printable {
            return "\(type.label):\(sequence):\(text.prefix(64))"
        }
        return "\(type.label):\(sequence):<\(payload.count) bytes>"
    }
}

/// A control sequence repeated or rewound.
public struct ReplayError: Swift.Error, CustomStringConvertible {
    public let sequence: UInt32
    public let highest: UInt32
    public var description: String {
        "replayed control sequence \(sequence) (highest accepted \(highest))"
    }
}

/// Error from the control codec.
public enum ControlError: Swift.Error, Equatable, CustomStringConvertible {
    case short(buffer: Int)
    case version(UInt8)
    case type(UInt8)
    case sequenceZero
    case tooLarge(Int)

    public var description: String {
        switch self {
        case .short(let count):
            return "control message is \(count) bytes, needs at least 6"
        case .version(let v):
            return "unsupported control version \(v)"
        case .type(let code):
            return String(format: "unknown control message type 0x%02x", code)
        case .sequenceZero:
            return "control sequence must start at 1"
        case .tooLarge(let size):
            return "control payload \(size) exceeds the maximum"
        }
    }
}

/// Encodes and decodes the control envelope, matching `ControlMessage.kt`.
public enum ControlMessageCodec {

    public static let version: UInt8 = 1
    public static let headerBytes = 6
    public static let maxPayloadBytes = SessionFraming.maxPayloadBytes - headerBytes

    public static func encode(
        type: ControlMessageType,
        sequence: UInt32,
        payload: Data = Data()
    ) throws -> Data {
        guard sequence >= 1 else { throw ControlError.sequenceZero }
        guard payload.count <= maxPayloadBytes else {
            throw ControlError.tooLarge(payload.count)
        }

        var out = Data(capacity: headerBytes + payload.count)
        out.append(version)
        out.append(type.rawValue)
        out.append(UInt8((sequence >> 24) & 0xFF))
        out.append(UInt8((sequence >> 16) & 0xFF))
        out.append(UInt8((sequence >> 8) & 0xFF))
        out.append(UInt8(sequence & 0xFF))
        out.append(payload)
        return out
    }

    public static func decode(_ data: Data) throws -> ControlMessage {
        guard data.count >= headerBytes else { throw ControlError.short(buffer: data.count) }
        let bytes = [UInt8](data)

        guard bytes[0] == version else { throw ControlError.version(bytes[0]) }
        guard let type = ControlMessageType(rawValue: bytes[1]) else {
            throw ControlError.type(bytes[1])
        }
        let sequence = (UInt32(bytes[2]) << 24)
            | (UInt32(bytes[3]) << 16)
            | (UInt32(bytes[4]) << 8)
            | UInt32(bytes[5])
        guard sequence >= 1 else { throw ControlError.sequenceZero }

        let payload = Data(bytes[headerBytes...])
        guard payload.count <= maxPayloadBytes else {
            throw ControlError.tooLarge(payload.count)
        }
        return ControlMessage(type: type, sequence: sequence, payload: payload)
    }
}

/// Accepts only strictly increasing control sequence numbers, per received direction.
public struct ReplayWindow {

    public private(set) var highest: UInt32 = 0

    public init() {}

    public mutating func accept(_ sequence: UInt32) throws {
        guard sequence > highest else {
            throw ReplayError(sequence: sequence, highest: highest)
        }
        highest = sequence
    }
}
