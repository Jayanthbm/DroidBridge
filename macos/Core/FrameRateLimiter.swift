import Foundation

/// A bounded token-bucket limiter for framed traffic.
///
/// A fixed arrival-time window can reject valid TCP traffic when the OS delivers already-buffered
/// frames in a short burst after scheduling delay. This limiter preserves the sustained rate limit
/// while allowing a finite, explicitly bounded burst. It is not protocol validation: callers must
/// still validate framing, authentication, message type, sequence and payload before admitting.
struct FrameRateLimiter {
    private let framesPerSecond: Int
    private let burstCapacity: Int
    private var tokens: Double
    private var lastUpdate: TimeInterval?

    init(framesPerSecond: Int, burstCapacity: Int) {
        precondition(framesPerSecond > 0)
        precondition(burstCapacity > 0)
        self.framesPerSecond = framesPerSecond
        self.burstCapacity = burstCapacity
        self.tokens = Double(burstCapacity)
    }

    mutating func reset() {
        tokens = Double(burstCapacity)
        lastUpdate = nil
    }

    mutating func admit(at time: TimeInterval) -> Bool {
        let effectiveTime = max(time, lastUpdate ?? time)
        if let lastUpdate {
            let elapsed = effectiveTime - lastUpdate
            tokens = min(Double(burstCapacity), tokens + elapsed * Double(framesPerSecond))
        }
        lastUpdate = effectiveTime

        guard tokens >= 1 else { return false }
        tokens -= 1
        return true
    }
}
