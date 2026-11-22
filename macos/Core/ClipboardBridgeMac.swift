import AppKit
import CryptoKit
import Foundation

/// Phase 3 on the Mac: carry text between the macOS pasteboard and the phone.
///
/// The same two rules the Android side implements, because a loop-prevention rule that only one side
/// follows does not prevent anything:
///
/// 1. **Origin.** Text that originated on this Mac is never applied back when the phone echoes it.
/// 2. **Content hash.** The offer, the body and the ack all carry the same SHA-256, so a mismatched
///    delivery is refused instead of written.
///
/// macOS has no foreground restriction like Android's (R-09), so receiving is automatic here — but
/// writing still must not re-trigger the pasteboard monitor, or the Mac would send back what it just
/// received. `changeCount` is the mechanism: after a write we record the count it produced and treat
/// the next observation of that count as our own.
///
/// Nothing is persisted: no text, no hash history, no file. `lastAppliedHash` and `lastWrittenChangeCount`
/// live in memory and vanish with the process.
public final class ClipboardBridgeMac {

    /// Largest text this implementation will carry, matching `ClipboardSync.MAX_TEXT_BYTES`.
    public static let maxTextBytes = 64 * 1024

    private let pasteboard = NSPasteboard.general

    /// Hash of the last text written because the phone sent it — suppresses exactly one echo.
    private var lastAppliedHash: String?

    /// Pasteboard change count produced by our own write.
    private var lastWrittenChangeCount: Int?

    /// Paused by the user: nothing is sent or applied.
    public var paused = false

    /// Status for the UI. Contains sizes and hashes only, never clipboard content.
    public private(set) var status = "idle"

    /// Called with a control message plus payload; wired to the live session.
    public var send: ((ControlMessageType, Data) -> Void)?

    /// This Mac's pseudonymous origin id, set by the session owner from its identity pin.
    public var ownOriginId: String = "mac"

    /// Emits a status line for the evidence log.
    public var onStatus: ((String) -> Void)?

    private func note(_ line: String) {
        status = line
        onStatus?(line)
    }

    /// Reads the Mac pasteboard and offers it to the phone.
    ///
    /// Returns the status string so the UI can show what happened without the caller inspecting the
    /// pasteboard itself.
    @discardableResult
    public func sendLocalClipboard() -> String {
        guard !paused else { note("paused"); return status }
        // Password managers mark concealed pasteboard items; those never leave the Mac.
        if pasteboard.data(forType: NSPasteboard.PasteboardType("org.nspasteboard.ConcealedType")) != nil {
            note("this pasteboard entry is marked concealed and was not sent")
            return status
        }
        guard let text = pasteboard.string(forType: .string), !text.isEmpty else {
            note("nothing on the pasteboard")
            return status
        }
        let bytes = Array(text.utf8)
        guard bytes.count <= Self.maxTextBytes else {
            note("pasteboard text is too large (\(bytes.count) bytes)")
            return status
        }
        let hash = Self.hash(of: bytes)
        if hash == lastAppliedHash {
            note("skipped: this text came from the phone")
            return status
        }
        guard send != nil else { note("not connected"); return status }

        let origin = ownOriginId
        let offer = """
        {"hash":"\(hash)","bytes":\(bytes.count),"origin":"\(origin)","source":"macOS"}
        """
        send?(.clipboardOffer, Data(offer.utf8))
        send?(.clipboardText, Data(bytes))
        note("sent \(bytes.count) bytes")
        return status
    }

    /// Handles a clipboard control message from the phone.
    public func handle(type: ControlMessageType, payload: Data) {
        switch type {
        case .clipboardOffer:
            handleOffer(payload)
        case .clipboardAccept:
            note("phone accepted the offer")
        case .clipboardText:
            note("unexpected body without an offer")
        case .clipboardAck:
            note("phone applied the text")
        case .clipboardReject:
            note("phone declined: \(String(data: payload, encoding: .utf8) ?? "unknown")")
        case .clipboardPause:
            let action = String(data: payload, encoding: .utf8) ?? ""
            paused = action == "pause"
            note(paused ? "paused by the phone" : "resumed by the phone")
        default:
            break
        }
    }

    /// The Mac is the receiver here: an offer is answered, and the body is written when it arrives.
    ///
    /// The phone's body is validated against the offer before the pasteboard changes, so a partial or
    /// corrupt body cannot overwrite what the user had copied.
    private func handleOffer(_ payload: Data) {
        guard let json = String(data: payload, encoding: .utf8), let offer = Self.parseOffer(json) else {
            send?(.clipboardReject, Data("hash_mismatch".utf8))
            note("offer rejected: unparseable")
            return
        }
        if paused {
            send?(.clipboardReject, Data("paused".utf8))
            note("offer rejected: paused")
            return
        }
        if offer.origin == ownOriginId {
            send?(.clipboardReject, Data("own_origin".utf8))
            note("offer rejected: own origin")
            return
        }
        if offer.bytes > Self.maxTextBytes {
            send?(.clipboardReject, Data("too_large".utf8))
            note("offer rejected: too large (\(offer.bytes) bytes)")
            return
        }
        pendingOffer = offer
        send?(.clipboardAccept, Data())
        note("offer accepted (\(offer.bytes) bytes)")
    }

    private struct Offer {
        let hash: String
        let bytes: Int
        let origin: String
    }

    private var pendingOffer: Offer?

    /// Called by the session when the body for an accepted offer arrives.
    public func handleTextBody(_ payload: Data) {
        guard let offer = pendingOffer else {
            note("body without an offer ignored")
            return
        }
        let bytes = Array(payload)
        guard bytes.count == offer.bytes, Self.hash(of: bytes) == offer.hash else {
            pendingOffer = nil
            send?(.clipboardReject, Data("hash_mismatch".utf8))
            note("body refused: hash or length mismatch")
            return
        }
        guard let text = String(bytes: bytes, encoding: .utf8) else {
            pendingOffer = nil
            send?(.clipboardReject, Data("hash_mismatch".utf8))
            note("body refused: not valid UTF-8")
            return
        }
        pasteboard.clearContents()
        pasteboard.setString(text, forType: .string)
        lastWrittenChangeCount = pasteboard.changeCount
        lastAppliedHash = offer.hash
        pendingOffer = nil
        send?(.clipboardAck, Data(offer.hash.utf8))
        note("applied \(bytes.count) bytes from the phone")
    }

    private var pollTimer: Timer?

    /// Starts watching the pasteboard. A timer rather than a distributed notification, because
    /// `changeCount` is the only reliable signal that a *user* copy happened (P06: the pasteboard
    /// server posts no notification that distinguishes our own writes).
    public func startPolling(interval: TimeInterval = 1.0) {
        pollTimer?.invalidate()
        pollTimer = Timer.scheduledTimer(withTimeInterval: interval, repeats: true) { [weak self] _ in
            self?.pollPasteboard()
        }
        note("watching the pasteboard")
    }

    public func stopPolling() {
        pollTimer?.invalidate()
        pollTimer = nil
    }

    /// Watches the pasteboard for user copies. Our own writes are recognised by `changeCount` and
    /// skipped, which is what stops the receive path from immediately sending the text back.
    public func pollPasteboard() {
        guard !paused else { return }
        let count = pasteboard.changeCount
        if count == lastWrittenChangeCount { return }
        lastWrittenChangeCount = count
        if pasteboard.data(forType: NSPasteboard.PasteboardType("org.nspasteboard.ConcealedType")) != nil {
            note("skipped a concealed pasteboard entry")
            return
        }
        guard let text = pasteboard.string(forType: .string), !text.isEmpty else { return }
        let bytes = Array(text.utf8)
        guard bytes.count <= Self.maxTextBytes else { return }
        let hash = Self.hash(of: bytes)
        if hash == lastAppliedHash {
            lastAppliedHash = nil
            return
        }
        guard send != nil else { return }
        let origin = ownOriginId
        let offer = """
        {"hash":"\(hash)","bytes":\(bytes.count),"origin":"\(origin)","source":"macOS"}
        """
        send?(.clipboardOffer, Data(offer.utf8))
        send?(.clipboardText, Data(bytes))
        note("sent \(bytes.count) bytes")
    }

    public static func hash(of bytes: [UInt8]) -> String {
        SHA256.hash(data: Data(bytes)).map { String(format: "%02x", $0) }.joined()
    }

    private static func parseOffer(_ json: String) -> Offer? {
        func string(_ key: String) -> String? {
            let marker = "\"\(key)\":\""
            guard let start = json.range(of: marker)?.upperBound else { return nil }
            guard let end = json[start...].firstIndex(of: "\"") else { return nil }
            return String(json[start..<end])
        }
        func int(_ key: String) -> Int? {
            let marker = "\"\(key)\":"
            guard let start = json.range(of: marker)?.upperBound else { return nil }
            let digits = json[start...].prefix { $0.isNumber }
            return Int(digits)
        }
        guard let hash = string("hash"), let bytes = int("bytes"), let origin = string("origin") else {
            return nil
        }
        return Offer(hash: hash, bytes: bytes, origin: origin)
    }
}
