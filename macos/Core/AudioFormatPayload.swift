import Foundation

/// Payload of the `audio.format` control message, as produced by `AudioFormat.kt`.
///
/// The Kotlin side writes JSON with a fixed key order; this side parses by key, so the order is
/// not load-bearing here but the required keys are. A missing or out-of-range field is a refused
/// format, not a guess: a decoder built from guessed parameters produces noise or silence, and
/// neither is diagnosable from the user's side.
public struct AudioFormatPayload: Equatable {

    public enum ParseError: Error, CustomStringConvertible {
        case notJSON(String)
        case missingField(String)
        case unsupportedCodec(String)
        case outOfRange(field: String, value: String)
        case badConfiguration

        public var description: String {
            switch self {
            case .notJSON(let detail): return "not JSON: \(detail)"
            case .missingField(let name): return "missing field \(name)"
            case .unsupportedCodec(let codec): return "unsupported codec \(codec)"
            case .outOfRange(let field, let value): return "\(field) out of range: \(value)"
            case .badConfiguration: return "AudioSpecificConfig is empty or not base64"
            }
        }
    }

    public let codec: String
    public let sampleRate: Int
    public let channels: Int
    public let bitrate: Int
    public let audioSpecificConfig: Data

    public init(codec: String, sampleRate: Int, channels: Int, bitrate: Int, audioSpecificConfig: Data) {
        self.codec = codec
        self.sampleRate = sampleRate
        self.channels = channels
        self.bitrate = bitrate
        self.audioSpecificConfig = audioSpecificConfig
    }

    public static func parse(_ payload: Data) throws -> AudioFormatPayload {
        guard let object = try? JSONSerialization.jsonObject(with: payload) else {
            throw ParseError.notJSON(String(decoding: payload.prefix(64), as: UTF8.self))
        }
        guard let root = object as? [String: Any] else { throw ParseError.notJSON("root is not an object") }

        guard let codec = root["codec"] as? String else { throw ParseError.missingField("codec") }
        guard codec == "aac-lc" else { throw ParseError.unsupportedCodec(codec) }

        guard let sampleRate = root["sampleRate"] as? Int else { throw ParseError.missingField("sampleRate") }
        guard (8_000...48_000).contains(sampleRate) else {
            throw ParseError.outOfRange(field: "sampleRate", value: "\(sampleRate)")
        }

        guard let channels = root["channels"] as? Int else { throw ParseError.missingField("channels") }
        guard channels == 1 || channels == 2 else {
            throw ParseError.outOfRange(field: "channels", value: "\(channels)")
        }

        guard let bitrate = root["bitrate"] as? Int, bitrate > 0 else {
            throw ParseError.missingField("bitrate")
        }

        guard let encoded = root["audioSpecificConfig"] as? String else {
            throw ParseError.missingField("audioSpecificConfig")
        }
        guard let asc = Data(base64Encoded: encoded), !asc.isEmpty else { throw ParseError.badConfiguration }

        return AudioFormatPayload(
            codec: codec,
            sampleRate: sampleRate,
            channels: channels,
            bitrate: bitrate,
            audioSpecificConfig: asc
        )
    }

    public var descriptionText: String {
        "\(codec) \(sampleRate)Hz \(channels)ch \(bitrate / 1000)kbps asc=\(audioSpecificConfig.count)B"
    }
}
