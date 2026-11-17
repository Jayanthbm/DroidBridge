import XCTest
@testable import DroidBridge

final class FrameRateLimiterTests: XCTestCase {

    func testAdmitsConfiguredBoundedBurst() {
        var limiter = FrameRateLimiter(framesPerSecond: 120, burstCapacity: 8)

        for index in 0..<8 {
            XCTAssertTrue(limiter.admit(at: 10), "frame \(index) should be within the burst")
        }
        XCTAssertFalse(limiter.admit(at: 10), "the ninth immediate frame exceeds the burst")
    }

    func testRefillsAtSustainedRate() {
        var limiter = FrameRateLimiter(framesPerSecond: 10, burstCapacity: 2)

        XCTAssertTrue(limiter.admit(at: 0))
        XCTAssertTrue(limiter.admit(at: 0))
        XCTAssertFalse(limiter.admit(at: 0))
        XCTAssertTrue(limiter.admit(at: 0.1))
        XCTAssertFalse(limiter.admit(at: 0.1))
    }

    func testLongDelayRefillsOnlyToBurstCapacity() {
        var limiter = FrameRateLimiter(framesPerSecond: 10, burstCapacity: 3)

        XCTAssertTrue(limiter.admit(at: 0))
        for _ in 0..<3 {
            XCTAssertTrue(limiter.admit(at: 10))
        }
        XCTAssertFalse(limiter.admit(at: 10))
    }

    func testClockRegressionDoesNotCreateTokens() {
        var limiter = FrameRateLimiter(framesPerSecond: 10, burstCapacity: 2)

        XCTAssertTrue(limiter.admit(at: 5))
        XCTAssertTrue(limiter.admit(at: 5))
        XCTAssertFalse(limiter.admit(at: 4))
    }

    func testResetRestoresInitialBurst() {
        var limiter = FrameRateLimiter(framesPerSecond: 1, burstCapacity: 2)
        XCTAssertTrue(limiter.admit(at: 0))
        XCTAssertTrue(limiter.admit(at: 0))
        XCTAssertFalse(limiter.admit(at: 0))

        limiter.reset()
        XCTAssertTrue(limiter.admit(at: 0))
        XCTAssertTrue(limiter.admit(at: 0))
    }
}
