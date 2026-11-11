import Foundation

/// Payload of the `mirror.format` control message (NETWORK_PROTOCOL §7).
public struct MirrorFormatPayload {
    public let width: Int
    public let height: Int
    public let rotation: Int
    public let sps: Data
    public let pps: Data
    public let nalLengthSize: Int32

    public enum ParseError: Error, Equatable {
        case notJson
        case missingField(String)
        case invalidBase64(String)
        case invalidValue(String)
    }

    public static func parse(_ payload: Data) throws -> MirrorFormatPayload {
        let object = try JSONSerialization.jsonObject(with: payload)
        guard let dict = object as? [String: Any] else { throw ParseError.notJson }

        func int(_ key: String) throws -> Int {
            guard let value = dict[key] else { throw ParseError.missingField(key) }
            if let n = value as? Int { return n }
            if let n = value as? NSNumber { return n.intValue }
            throw ParseError.invalidValue(key)
        }
        func string(_ key: String) throws -> String {
            guard let value = dict[key] as? String else { throw ParseError.missingField(key) }
            return value
        }

        let width = try int("width")
        let height = try int("height")
        let rotation = (try? int("rotation")) ?? 0
        let nalLengthSize = Int32((try? int("nalLengthSize")) ?? 4)
        guard width > 0, height > 0 else { throw ParseError.invalidValue("size") }
        guard [0, 90, 180, 270].contains(rotation) else { throw ParseError.invalidValue("rotation") }
        guard [1, 2, 4].contains(Int(nalLengthSize)) else { throw ParseError.invalidValue("nalLengthSize") }

        let spsString = try string("sps")
        let ppsString = try string("pps")
        guard let sps = Data(base64Encoded: spsString) else { throw ParseError.invalidBase64("sps") }
        guard let pps = Data(base64Encoded: ppsString) else { throw ParseError.invalidBase64("pps") }
        guard !sps.isEmpty, !pps.isEmpty else { throw ParseError.invalidValue("parameter sets") }

        return MirrorFormatPayload(
            width: width,
            height: height,
            rotation: rotation,
            sps: sps,
            pps: pps,
            nalLengthSize: nalLengthSize
        )
    }
}
