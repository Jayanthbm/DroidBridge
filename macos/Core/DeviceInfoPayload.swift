import Foundation

/// Payload of the `device.info` control message (NETWORK_PROTOCOL §7).
///
/// The Mac shows "Android device" until the phone says otherwise. This is what the phone says: the
/// same facts its own About screen would show. The peer is authenticated by the time this arrives, so
/// nothing here is trusted for a security decision - it is a label, not an authorisation.
public struct DeviceInfoPayload: Equatable {
    public let manufacturer: String
    public let model: String
    public let androidRelease: String
    public let sdkInt: Int
    public let appVersion: String

    public enum ParseError: Error, Equatable {
        case notJson
        case missingField(String)
        case invalidValue(String)
    }

    /// `samsung SM-S928B`, the string the sidebar's device card shows.
    public var displayName: String {
        let name = "\(manufacturer) \(model)".trimmingCharacters(in: .whitespaces)
        return name.isEmpty ? "Android device" : name
    }

    /// `Android 17 (API 37) - app 0.1.0`, the detail line under it.
    public var detailLine: String {
        "Android \(androidRelease) (API \(sdkInt)) - app \(appVersion)"
    }

    public static func parse(_ payload: Data) throws -> DeviceInfoPayload {
        guard let object = try? JSONSerialization.jsonObject(with: payload),
              let dict = object as? [String: Any] else {
            throw ParseError.notJson
        }

        func string(_ key: String) throws -> String {
            guard let value = dict[key] as? String else { throw ParseError.missingField(key) }
            guard !value.isEmpty, value.count <= 64 else { throw ParseError.invalidValue(key) }
            return value
        }

        func int(_ key: String) throws -> Int {
            guard let value = dict[key] else { throw ParseError.missingField(key) }
            guard let n = value as? NSNumber else { throw ParseError.invalidValue(key) }
            return n.intValue
        }

        let sdk = try int("sdkInt")
        guard sdk > 0 else { throw ParseError.invalidValue("sdkInt") }

        return DeviceInfoPayload(
            manufacturer: try string("manufacturer"),
            model: try string("model"),
            androidRelease: try string("androidRelease"),
            sdkInt: sdk,
            appVersion: try string("appVersion")
        )
    }
}