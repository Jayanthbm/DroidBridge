import Foundation
import Network
import os

/// P03 headless session harness.
///
/// Runs the Mac half of the secure session without a UI, so the acceptance criteria can be measured
/// by a script rather than by tapping buttons. Each line of interest is printed as `P03: ...` on
/// stderr, which is the evidence the report cites:
///
///   P03Harness --pair [--peer-id <id>] [--seconds N] [--pin-service <name>] [--reset-pin]
///                   [--idle-timeout S] [--pairing-timeout S]
///   P03Harness --listen [--expect-pin <hex>] [--seconds N]
///   P03Harness --identity
///
/// `--pair` starts the listener and waits for a peer. On a connection it prints the peer's pin and
/// the pairing code, then waits on stdin for the user's decision:
///
///   confirm     persist the pin and enable data (the real confirmation path)
///   reject      close without storing anything
///   ping [t]    send a ping control frame
///   pong [t]    send a pong control frame
///   replay      resend sequence 1, which a correct peer must reject
///   oversize    send a length prefix above the limit, which a correct peer must reject
///   zero        send a zero length prefix, which a correct peer must reject
///   forget      delete the stored pin, so the next connection is a first pairing
///   state       print the current session state
///   quit        stop the listener and exit
///
/// `--listen` is the non-interactive variant: it uses an explicit expected pin (or none), echoes each
/// ping as a pong, and runs until the deadline. `--identity` prints this run's identity and exits,
/// which is how the report pins the identity format.
///
/// Exit status: 0 when the session reached `authenticated` during the run, 3 when it did not, 1 for
/// an identity or start failure, 2 for a usage error.
enum P03Harness {

    static func run(_ arguments: [String]) -> Int32 {
        let log = Logger(subsystem: "com.droidbridge.macos", category: "P03Harness")

        func emit(_ line: String) {
            // stderr as well as os.Logger: a short-lived process is often killed before the unified
            // log has flushed, and the harness output is the evidence.
            FileHandle.standardError.write(Data("P03: \(line)\n".utf8))
            log.info("\(line, privacy: .public)")
        }

        func value(of flag: String) -> String? {
            guard let index = arguments.firstIndex(of: flag), index + 1 < arguments.count else {
                return nil
            }
            return arguments[index + 1]
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
        emit("identity cert-der-bytes=\((SecCertificateCopyData(identity.certificate) as Data).count)")

        if arguments.contains("--identity") {
            return 0
        }

        let isPair = arguments.contains("--pair")
        let isListen = arguments.contains("--listen")
        guard isPair != isListen else {
            emit("FAIL usage: --pair [--peer-id <id>] [--seconds N] | --listen [--expect-pin <hex>] [--seconds N] | --identity")
            return 2
        }

        var seconds: Double = isPair ? 300 : 120
        if let raw = value(of: "--seconds"), let parsed = Double(raw) {
            seconds = parsed
        }

        let peerId = value(of: "--peer-id") ?? "android-p03"
        let store = PeerPinStore(service: value(of: "--pin-service") ?? "com.droidbridge.p03.peer-pins")

        let trustMode: SecureListener.TrustMode
        if isListen {
            trustMode = .explicitPin(value(of: "--expect-pin"))
        } else {
            if arguments.contains("--reset-pin") {
                store.forget(peerId)
                emit("reset stored pin for \(peerId)")
            }
            trustMode = .storedPin
        }

        // Overridable so a scripted run can observe timeouts without waiting the production window.
        let idleTimeout = value(of: "--idle-timeout").flatMap(Double.init) ?? 30
        let pairingTimeout = value(of: "--pairing-timeout").flatMap(Double.init) ?? 120

        let listener = SecureListener(
            identity: identity,
            pinStore: store,
            peerId: peerId,
            trustMode: trustMode,
            idleTimeout: idleTimeout,
            pairingTimeout: pairingTimeout
        )

        var sawAuthenticated = false

        listener.onEvent = { emit($0) }
        listener.onReady = { pin, code, authenticated in
            sawAuthenticated = authenticated
            emit("READY peer-pin=\(pin) authenticated=\(authenticated) code=\(code)")
            emit("CODE \(ShortAuthenticationCode.formatForDisplay(code))")

            if authenticated {
                emit("AUTHENTICATED (stored pin matched; no user action needed)")
            } else {
                emit("AWAITING_CONFIRMATION compare the code on the phone, then type 'confirm' or 'reject'")
            }
        }
        listener.onAuthenticated = { pin in
            sawAuthenticated = true
            emit("AUTHENTICATED peer-pin=\(pin) pin stored")
        }
        listener.onFrame = { message in
            emit("FRAME type=\(message.type.label) seq=\(message.sequence) payload-bytes=\(message.payload.count)")
            if isListen, message.type == .ping {
                do {
                    try listener.sendControl(type: .pong, payload: message.payload)
                    emit("SENT pong seq-follows payload-bytes=\(message.payload.count)")
                } catch {
                    emit("FAIL echo: \(error)")
                }
            }
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

        emit("mode=\(isPair ? "pair" : "listen") peer-id=\(peerId) expect-pin=\(value(of: "--expect-pin") ?? (isPair ? "(from store, if any)" : "(none: first pairing)"))")
        emit("waiting \(Int(seconds))s")

        if isPair {
            startCommandThread(listener: listener, emit: emit)
        }

        // Callbacks are delivered on the main queue, so the main run loop must be running for the
        // session to make progress.
        RunLoop.current.run(until: Date().addingTimeInterval(seconds))
        listener.stop()
        // One more turn so the stop's callbacks are printed before exit.
        RunLoop.current.run(until: Date().addingTimeInterval(0.2))

        emit("state=\(listener.state.rawValue) authenticated-during-run=\(sawAuthenticated)")
        emit("stopped")
        return sawAuthenticated ? 0 : 3
    }

    // MARK: - Stdin commands

    private static func startCommandThread(
        listener: SecureListener,
        emit: @escaping (String) -> Void
    ) {
        let thread = Thread {
            emit("commands: confirm | reject | ping | pong | replay | oversize | zero | forget | state | quit")
            while let raw = readLine(strippingNewline: true) {
                let line = raw.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !line.isEmpty else { continue }

                let parts = line.split(separator: " ", maxSplits: 1).map(String.init)
                let command = parts[0].lowercased()
                let argument = parts.count > 1 ? parts[1] : nil

                do {
                    switch command {
                    case "confirm":
                        try listener.confirmPairing()
                        emit("COMMAND confirm accepted")
                    case "reject":
                        listener.rejectPairing()
                        emit("COMMAND reject accepted")
                    case "ping":
                        try listener.sendControl(type: .ping, payload: Data((argument ?? "ping").utf8))
                        emit("COMMAND ping sent")
                    case "pong":
                        try listener.sendControl(type: .pong, payload: Data((argument ?? "pong").utf8))
                        emit("COMMAND pong sent")
                    case "replay":
                        // Sequence 1 was used by the first ping, so this is a genuine replay.
                        try listener.sendControlForTesting(type: .ping, sequence: 1, payload: Data("replay".utf8))
                        emit("COMMAND replay sent (sequence 1)")
                    case "oversize":
                        try listener.sendDeclaredLengthForTesting(UInt32(SessionFraming.maxPayloadBytes) + 1)
                        emit("COMMAND oversize length prefix sent")
                    case "zero":
                        try listener.sendDeclaredLengthForTesting(0)
                        emit("COMMAND zero length prefix sent")
                    case "forget":
                        listener.forgetPeer()
                        emit("COMMAND forget done")
                    case "state":
                        emit("STATE state=\(listener.state.rawValue) peer-pin=\(listener.peerPin ?? "-") code=\(listener.pairingCode ?? "-")")
                    case "quit":
                        listener.stop()
                        emit("COMMAND quit")
                        exit(0)
                    default:
                        emit("FAIL unknown command '\(command)'")
                    }
                } catch {
                    emit("FAIL \(command): \(error)")
                }
            }
            emit("stdin closed; session continues until the deadline")
        }
        thread.name = "p03-commands"
        thread.start()
    }
}
