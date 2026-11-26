import XCTest
@testable import DroidBridge

final class DeviceInfoPayloadTests: XCTestCase {

    /// The exact string `DeviceInfoTest` pins on the Kotlin side.
    private let kotlinPayload = """
    {"manufacturer":"samsung","model":"SM-S928B","androidRelease":"17","sdkInt":37,"appVersion":"0.1.0"}
    """

    func testParsesTheKotlinEncoding() throws {
        let info = try DeviceInfoPayload.parse(Data(kotlinPayload.utf8))
        XCTAssertEqual(info.manufacturer, "samsung")
        XCTAssertEqual(info.model, "SM-S928B")
        XCTAssertEqual(info.androidRelease, "17")
        XCTAssertEqual(info.sdkInt, 37)
        XCTAssertEqual(info.appVersion, "0.1.0")
        XCTAssertEqual(info.displayName, "samsung SM-S928B")
        XCTAssertEqual(info.detailLine, "Android 17 (API 37) - app 0.1.0")
    }

    func testRejectsMissingFields() {
        let json = "{\"manufacturer\":\"samsung\",\"model\":\"SM-S928B\",\"sdkInt\":37}"
        XCTAssertThrowsError(try DeviceInfoPayload.parse(Data(json.utf8))) { error in
            guard case DeviceInfoPayload.ParseError.missingField(let key) = error else {
                return XCTFail("unexpected error \(error)")
            }
            XCTAssertEqual(key, "androidRelease")
        }
    }

    func testRejectsAnImpossibleApiLevel() {
        let json = "{\"manufacturer\":\"s\",\"model\":\"m\",\"androidRelease\":\"17\",\"sdkInt\":0,\"appVersion\":\"1\"}"
        XCTAssertThrowsError(try DeviceInfoPayload.parse(Data(json.utf8)))
    }

    func testRejectsNonJson() {
        XCTAssertThrowsError(try DeviceInfoPayload.parse(Data("not json".utf8)))
    }

    func testFallsBackToThePlaceholderWhenTheNameIsEmpty() {
        let info = DeviceInfoPayload(
            manufacturer: " ",
            model: " ",
            androidRelease: "17",
            sdkInt: 37,
            appVersion: "0.1.0"
        )
        XCTAssertEqual(info.displayName, "Android device")
    }
}