import Foundation
import Network
import os

/// P03 headless session harness.
///
/// Runs the Mac half of the secure session without a UI, so the acceptance criteria can be measured
/// by a script rather than by tapping buttons. Each criterion prints one `P03:` line, which is the
/// evidence the report cites.
///
/// Usage:
///   P03Harness --listen [--expect-pin <hex>] [--seconds N]
///   P03Harness --identity
///
/// `--listen` starts the TLS listener, advertises it over Bonjour, and prints every event until it
/// is killed or `--seconds` elapses. `--identity` prints this run's pin and SPKI digest and exits,
/// which is how the report pins the identity format.
enum P03Harness {

    static func run(_ arguments: [String]) -> Int32 {
        let log = Logger(subsystem: "com.droidbridge.macos", category: "P03Harness")

        func emit(_ line: String) {
            // stderr as well as os.Logger: a short-lived process is often killed before the unified
            // log has flushed, and the harness output is the evidence.
            FileHandle.standardError.write(Data("P03: \(line)\n".utf8))
            log.info("\(line, privacy: .public)")
        }

        let identity: MacIdentity
        do {
            identity = try MacIdentity.generate()
        } catch {
            emit("FAIL identity: \(error)")
            return 1
        }

        emit("identity pin=\(identity.pin)")
        emit("identity spki-bytes=\(identity.subjectPublicKeyInfo.count)")
        emit("identity spki-sha256=\(identity.pin)")
        emit("identity cert-der-bytes=\((SecCertificateCopyData(identity.certificate) as Data).count)")

        if arguments.contains("--identity") {
            return 0
        }

        guard arguments.contains("--listen") else {
            emit("FAIL usage: --listen [--expect-pin <hex>] [--seconds N] | --identity")
            return 2
        }

        var expectedPin: String?
        if let index = arguments.firstIndex(of: "--expect-pin"), index + 1 < arguments.count {
            expectedPin = arguments[index + 1]
        }

        var seconds: Double = 120
        if let index = arguments.firstIndex(of: "--seconds"), index + 1 < arguments.count,
           let value = Double(arguments[index + 1]) {
            seconds = value
        }

        let listener = SecureListener(identity: identity, expectedPin: expectedPin)
        listener.onEvent = { emit($0) }
        listener.onReady = { pin in
            emit("READY peer-pin=\(pin)")
        }
        listener.onData = { data in
            // Echo back, so the Android side can prove the channel is duplex.
            emit("DATA bytes=\(data.count) text=\(String(decoding: data.prefix(64), as: UTF8.self))")
            listener.send(data)
            emit("ECHO bytes=\(data.count)")
        }
        listener.onClosed = { reason in
            emit("CLOSED \(reason)")
        }

        do {
            try listener.start()
        } catch {
            emit("FAIL start: \(error)")
            return 1
        }

        emit("expect-pin=\(expectedPin ?? "(none: first pairing)")")
        emit("waiting \(Int(seconds))s")

        // Run the listener's queue for the requested window.
        RunLoop.current.run(until: Date().addingTimeInterval(seconds))

        listener.stop()
        emit("stopped")
        return 0
    }
}
