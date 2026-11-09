import AppKit
import Combine
import CryptoKit
import Foundation
import os

/// P06 — macOS pasteboard monitoring and the access prompt.
///
/// The question is whether DroidBridge can watch the Mac clipboard automatically. On macOS 15.4+
/// the pasteboard can be in `ask` state, and the system may show an access prompt on the first
/// real read. `changeCount` polling is cheap and does not read content; a real
/// `string(forType:)` read is what can trigger the prompt.
///
/// What this model deliberately does **not** do:
/// - log clipboard content (only length and SHA-256 prefix);
/// - busy-loop (a 500 ms timer, not `while true`);
/// - treat a self-write as an external change (that would echo the text straight back).
@MainActor
public final class PasteboardModel: ObservableObject {

    private let log = Logger(subsystem: "com.droidbridge.macos", category: "P06Pasteboard")
    private let pollInterval: TimeInterval = 0.5

    @Published public private(set) var monitoring = false
    @Published public private(set) var accessBehavior = "unknown"
    @Published public private(set) var lastEvent = "idle"
    @Published public private(set) var events: [String] = []
    @Published public private(set) var pollCount = 0
    @Published public private(set) var readCount = 0

    /// Called on every evidence line, so a scripted run can capture it from stderr.
    public var onEvent: ((String) -> Void)?

    private var timer: Timer?
    private var lastChangeCount: Int = NSPasteboard.general.changeCount
    private var selfWriteChangeCount: Int?
    private var startedAt = Date()
    private let maxEvents = 200

    private static let builtInWrite = "droidbridge-p06-write"

    public init(autoStart: Bool = false, selfWriteAfter: TimeInterval? = nil) {
        accessBehavior = Self.currentAccessBehaviorName()
        if autoStart {
            start()
            if let delay = selfWriteAfter {
                DispatchQueue.main.asyncAfter(deadline: .now() + delay) { [weak self] in
                    self?.writeTestText()
                }
            }
        }
    }

    // MARK: - Access behavior

    private static func currentAccessBehaviorName() -> String {
        if #available(macOS 15.4, *) {
            return describe(NSPasteboard.general.accessBehavior)
        }
        return "unavailable(before macOS 15.4)"
    }

    @available(macOS 15.4, *)
    private static func describe(_ behavior: NSPasteboard.AccessBehavior) -> String {
        switch behavior {
        case .ask: return "ask"
        case .alwaysAllow: return "alwaysAllow"
        case .alwaysDeny: return "alwaysDeny"
        case .default: return "default"
        @unknown default: return "unknown(\(behavior.rawValue))"
        }
    }

    private func refreshAccessBehavior() {
        accessBehavior = Self.currentAccessBehaviorName()
    }

    // MARK: - Monitoring

    public func start() {
        guard !monitoring else { return }
        monitoring = true
        pollCount = 0
        readCount = 0
        startedAt = Date()
        lastChangeCount = NSPasteboard.general.changeCount
        refreshAccessBehavior()
        emit("MONITOR start accessBehavior=\(accessBehavior) changeCount=\(lastChangeCount) pollMs=500")
        let timer = Timer.scheduledTimer(withTimeInterval: pollInterval, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.tick() }
        }
        RunLoop.main.add(timer, forMode: .common)
        self.timer = timer
    }

    public func stop() {
        timer?.invalidate()
        timer = nil
        monitoring = false
        let elapsed = Date().timeIntervalSince(startedAt)
        emit("MONITOR stop polls=\(pollCount) reads=\(readCount) elapsedSec=\(String(format: "%.1f", elapsed))")
    }

    /// Polls only `changeCount`; content is read only when the count changes.
    private func tick() {
        guard monitoring else { return }
        pollCount += 1
        let pasteboard = NSPasteboard.general
        let count = pasteboard.changeCount
        guard count != lastChangeCount else { return }
        lastChangeCount = count
        refreshAccessBehavior()

        if let selfWriteChangeCount, count == selfWriteChangeCount {
            self.selfWriteChangeCount = nil
            emit("CHANGE changeCount=\(count) selfWrite=true suppressed=true accessBehavior=\(accessBehavior)")
            return
        }

        emit("CHANGE changeCount=\(count) selfWrite=false accessBehavior=\(accessBehavior)")
        read(reason: "external-change")
    }

    /// Reads the clipboard once, deliberately. This is the operation the P06 prompt experiment needs.
    public func readNow() {
        refreshAccessBehavior()
        emit("READ manual accessBehavior=\(accessBehavior)")
        read(reason: "manual")
    }

    private func read(reason: String) {
        let pasteboard = NSPasteboard.general
        let started = Date()
        let text = pasteboard.string(forType: .string)
        let elapsedMs = Date().timeIntervalSince(started) * 1000
        readCount += 1
        refreshAccessBehavior()
        if let text {
            emit(
                "READ result=text reason=\(reason) len=\(text.count) " +
                    "bytes=\(text.utf8.count) sha=\(Self.shortHash(text)) " +
                    "elapsedMs=\(String(format: "%.1f", elapsedMs)) accessBehavior=\(accessBehavior)"
            )
        } else {
            emit(
                "READ result=nil-or-denied reason=\(reason) " +
                    "elapsedMs=\(String(format: "%.1f", elapsedMs)) accessBehavior=\(accessBehavior)"
            )
        }
    }

    /// Writes known synthetic text and marks the resulting change as self-authored.
    ///
    /// The next `changeCount` tick must log `selfWrite=true suppressed=true` and must not read the
    /// text back. That is the write-suppression half of the clipboard loop test.
    public func writeTestText() {
        let pasteboard = NSPasteboard.general
        refreshAccessBehavior()
        pasteboard.clearContents()
        let ok = pasteboard.setString(Self.builtInWrite, forType: .string)
        selfWriteChangeCount = pasteboard.changeCount
        emit(
            "WRITE ok=\(ok) len=\(Self.builtInWrite.count) " +
                "changeCount=\(pasteboard.changeCount) accessBehavior=\(accessBehavior)"
        )
    }

    /// Clears the clipboard without reading it. Used to reset between rows.
    public func clearClipboard() {
        NSPasteboard.general.clearContents()
        selfWriteChangeCount = NSPasteboard.general.changeCount
        emit("CLEAR changeCount=\(NSPasteboard.general.changeCount) selfWrite=true")
    }

    // MARK: - Evidence

    private static func shortHash(_ text: String) -> String {
        let digest = SHA256.hash(data: Data(text.utf8))
        return digest.map { String(format: "%02x", $0) }.joined().prefix(12).description
    }

    private func emit(_ line: String) {
        lastEvent = line
        events.append(line)
        if events.count > maxEvents {
            events.removeFirst(events.count - maxEvents)
        }
        log.info("\(line, privacy: .public)")
        // Unified logging as well as stderr: a LaunchServices launch does not inherit a shell's
        // stderr, and the P06 run needs the same evidence either way.
        NSLog("p06: %@", line)
        FileHandle.standardError.write(Data("[p06] \(line)\n".utf8))
        onEvent?(line)
    }
}
