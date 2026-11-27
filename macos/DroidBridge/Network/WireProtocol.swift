import Foundation
import CryptoKit

enum SHA256Fingerprint {
    static func hex(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    static func pairingCode(local: String, peer: String) -> String {
        let ordered = [local, peer].sorted().joined(separator: "|")
        let digest = SHA256.hash(data: Data("droidbridge-sac-v2|\(ordered)".utf8))
        let value = digest.prefix(4).reduce(UInt32(0)) { ($0 << 8) | UInt32($1) } % 1_000_000
        return String(format: "%06d", value)
    }

    static func displayDigits(_ code: String) -> String {
        guard code.count == 6 else { return code }
        return "\(code.prefix(3)) \(code.suffix(3))"
    }
}

enum WireMessageType: UInt8 {
    case hello = 0x01
    case video = 0x02
    case audio = 0x03
    case clipboard = 0x04
    case control = 0x05
    case ping = 0x06
}

struct WireHello: Codable, Sendable {
    var kind = "hello"
    var protocolVersion = 2
    var deviceID: String
    var name: String
    var model: String
    var platform: String
    var fingerprint: String
    var paired: Bool
    var battery: Int?
    var osVersion: String?

    enum CodingKeys: String, CodingKey {
        case kind
        case protocolVersion = "protocol"
        case deviceID = "deviceId"
        case name
        case model
        case platform
        case fingerprint
        case paired
        case battery
        case osVersion
    }
}

struct WireControl: Codable, Sendable {
    var kind = "control"
    var action: String
    var value: String?
}

struct WireFrame: Sendable {
    let type: WireMessageType
    let payload: Data
}

enum WireProtocolError: LocalizedError {
    case invalidLength
    case payloadTooLarge
    case unknownType(UInt8)
    case invalidJSON

    var errorDescription: String? {
        switch self {
        case .invalidLength: "Invalid frame length."
        case .payloadTooLarge: "Frame is too large."
        case .unknownType(let type): "Unknown frame type 0x\(String(format: "%02x", type))."
        case .invalidJSON: "Invalid JSON payload."
        }
    }
}

struct WireFrameDecoder {
    private var buffer = Data()
    private static let maximumFrameLength = 16 * 1024 * 1024

    mutating func append(_ data: Data) throws -> [WireFrame] {
        buffer.append(data)
        var frames: [WireFrame] = []
        while buffer.count >= 4 {
            let length = buffer.prefix(4).reduce(UInt32(0)) { ($0 << 8) | UInt32($1) }
            guard length >= 1 else { throw WireProtocolError.invalidLength }
            guard length <= Self.maximumFrameLength else { throw WireProtocolError.payloadTooLarge }
            let total = 4 + Int(length)
            guard buffer.count >= total else { break }
            let rawType = buffer[buffer.startIndex + 4]
            guard let type = WireMessageType(rawValue: rawType) else {
                throw WireProtocolError.unknownType(rawType)
            }
            let payload = buffer.subdata(in: (buffer.startIndex + 5)..<(buffer.startIndex + total))
            buffer.removeFirst(total)
            frames.append(WireFrame(type: type, payload: payload))
        }
        return frames
    }
}

enum WireCodec {
    static func frame(type: WireMessageType, payload: Data) -> Data {
        var data = Data(capacity: 5 + payload.count)
        let length = UInt32(payload.count + 1).bigEndian
        withUnsafeBytes(of: length) { data.append(contentsOf: $0) }
        data.append(type.rawValue)
        data.append(payload)
        return data
    }

    static func frame<T: Encodable>(type: WireMessageType, message: T) throws -> Data {
        let payload = try JSONEncoder().encode(message)
        return frame(type: type, payload: payload)
    }

    static func decode<T: Decodable>(_ type: T.Type, from payload: Data) throws -> T {
        do {
            return try JSONDecoder().decode(type, from: payload)
        } catch {
            throw WireProtocolError.invalidJSON
        }
    }
}
