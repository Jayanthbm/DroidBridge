import Foundation

/// Validates the ordering and keyframe boundaries of one authenticated mirror stream.
///
/// This is intentionally separate from VideoToolbox: protocol-invalid sequencing is rejected
/// before sample buffers are enqueued, while codec-specific decode failures remain decoder state.
struct MirrorStreamValidator {
    enum Error: Swift.Error, Equatable, CustomStringConvertible {
        case negativePTS(Int64)
        case nonIncreasingPTS(previous: Int64, current: Int64)
        case expectedIDR(after: String, ptsUs: Int64)

        var description: String {
            switch self {
            case .negativePTS(let pts):
                return "negative video PTS \(pts)"
            case .nonIncreasingPTS(let previous, let current):
                return "video PTS \(current) is not greater than \(previous)"
            case .expectedIDR(let boundary, let pts):
                return "expected IDR after \(boundary), received non-IDR at PTS \(pts)"
            }
        }
    }

    enum Decision: Equatable {
        case enqueue
        case waitForRecoveryIDR
    }

    private(set) var lastPTSUs: Int64?
    private var initialIDRBoundary: String?
    private var recoveryIDRRequired = false

    mutating func streamStarted() {
        lastPTSUs = nil
        initialIDRBoundary = "mirror.started"
        recoveryIDRRequired = false
    }

    mutating func formatChanged() {
        // The Android sender creates a new encoder when capture geometry changes; that encoder's
        // presentation timestamps may restart at zero. Keep strict ordering within each encoder
        // epoch while accepting the new epoch only after its required IDR.
        lastPTSUs = nil
        initialIDRBoundary = "mirror.format"
        recoveryIDRRequired = false
    }

    mutating func decoderFlushed() {
        recoveryIDRRequired = true
    }

    mutating func validate(ptsUs: Int64, isIDR: Bool) throws -> Decision {
        guard ptsUs >= 0 else { throw Error.negativePTS(ptsUs) }
        if let previous = lastPTSUs, ptsUs <= previous {
            throw Error.nonIncreasingPTS(previous: previous, current: ptsUs)
        }
        lastPTSUs = ptsUs

        if let boundary = initialIDRBoundary {
            guard isIDR else { throw Error.expectedIDR(after: boundary, ptsUs: ptsUs) }
            initialIDRBoundary = nil
            return .enqueue
        }

        if recoveryIDRRequired {
            guard isIDR else { return .waitForRecoveryIDR }
            recoveryIDRRequired = false
        }
        return .enqueue
    }
}
