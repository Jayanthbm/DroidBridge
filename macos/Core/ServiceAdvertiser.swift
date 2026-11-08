import Foundation
import Network
import os

/// P02 Path A step 1: advertise a Bonjour service and accept inbound TCP connections.
///
/// ADR-010 reverses the usual roles: the **Mac** advertises and listens, and Android dials out
/// through the system NSD picker. That is the only arrangement where Android can connect without
/// holding `ACCESS_LOCAL_NETWORK`, because advertising always requires that permission while a
/// picker-selected service can be connected to without it (R-02).
///
/// This type is the Mac half of that. It does no TLS and no framing: P03 owns those. What it
/// proves is narrower and more useful — that a `NWListener` can advertise the service type, be
/// discovered over Bonjour, accept a TCP connection, and echo bytes back on the same connection.
///
/// Sandbox note: this needs `com.apple.security.network.server` (already in the audited
/// entitlement set) and the user's Local Network permission on first use.
public final class ServiceAdvertiser {

    private let log = Logger(subsystem: "com.droidbridge.macos", category: "advertise")

    private var listener: NWListener?
    private var connections: [NWConnection] = []
    private let queue = DispatchQueue(label: "com.droidbridge.advertise")

    /// Called on the main queue whenever the state changes, so the UI can show it.
    public var onStateChange: ((AdvertiseState) -> Void)?
    /// Called on the main queue for each line of evidence worth recording.
    public var onEvent: ((String) -> Void)?

    public private(set) var state: AdvertiseState = .idle {
        didSet {
            let newState = state
            DispatchQueue.main.async { [weak self] in self?.onStateChange?(newState) }
        }
    }

    public init() {}

    /// Starts advertising on an ephemeral port.
    ///
    /// The port is deliberately ephemeral (`.any`) rather than fixed: a fixed port is a
    /// collision waiting to happen and Bonjour exists precisely so the port does not matter.
    public func start() {
        stop()

        do {
            let parameters = NWParameters.tcp
            parameters.allowLocalEndpointReuse = true

            let listener = try NWListener(using: parameters, on: .any)
            self.listener = listener

            let txt = ServiceTxtRecord(sessionId: ServiceTxtRecord.randomSessionId())
            listener.service = NWListener.Service(
                name: "DroidBridge",
                type: ServiceType.droidBridge,
                domain: nil,
                txtRecord: Self.txtRecordData(txt),
            )

            listener.stateUpdateHandler = { [weak self] newState in
                guard let self else { return }
                switch newState {
                case .ready:
                    let port = listener.port?.rawValue ?? 0
                    self.state = .advertising(name: "DroidBridge", port: port)
                    self.emit("advertising type=\(ServiceType.droidBridge) port=\(port) sid=\(txt.sessionId)")
                case .failed(let error):
                    self.state = .failed(error.localizedDescription)
                    self.emit("advertiser failed: \(error.localizedDescription)")
                case .cancelled:
                    self.state = .idle
                    self.emit("advertiser cancelled")
                default:
                    break
                }
            }

            listener.newConnectionHandler = { [weak self] connection in
                self?.accept(connection)
            }

            listener.start(queue: queue)
            emit("advertiser starting")
        } catch {
            state = .failed(error.localizedDescription)
            emit("advertiser could not start: \(error.localizedDescription)")
        }
    }

    public func stop() {
        connections.forEach { $0.cancel() }
        connections.removeAll()
        listener?.cancel()
        listener = nil
        state = .idle
    }

    /// Accepts one connection and echoes whatever arrives.
    ///
    /// The echo is the point: a one-way write would not prove the duplex path that P02 step 5
    /// requires. Bytes are echoed verbatim, so Android can compare what it sent with what came
    /// back instead of trusting a fixed reply.
    private func accept(_ connection: NWConnection) {
        connections.append(connection)
        let peer = connection.endpoint.debugDescription
        emit("inbound connection from \(peer)")

        connection.stateUpdateHandler = { [weak self] newState in
            guard let self else { return }
            switch newState {
            case .ready:
                self.emit("connection ready: \(peer)")
                self.receiveLoop(on: connection, peer: peer)
            case .failed(let error):
                self.emit("connection failed: \(error.localizedDescription)")
                self.drop(connection)
            case .cancelled:
                self.emit("connection cancelled: \(peer)")
                self.drop(connection)
            default:
                break
            }
        }
        connection.start(queue: queue)
    }

    private func receiveLoop(on connection: NWConnection, peer: String) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) {
            [weak self] data, _, isComplete, error in
            guard let self else { return }

            if let data, !data.isEmpty {
                self.emit("received \(data.count) bytes from \(peer); echoing")
                connection.send(content: data, completion: .contentProcessed { sendError in
                    if let sendError {
                        self.emit("echo failed: \(sendError.localizedDescription)")
                    }
                })
            }

            if let error {
                self.emit("receive error: \(error.localizedDescription)")
                self.drop(connection)
                return
            }
            if isComplete {
                self.emit("peer closed connection: \(peer)")
                self.drop(connection)
                return
            }
            self.receiveLoop(on: connection, peer: peer)
        }
    }

    private func drop(_ connection: NWConnection) {
        connection.cancel()
        connections.removeAll { $0 === connection }
    }

    private func emit(_ message: String) {
        log.info("\(message, privacy: .public)")
        // Also to stderr. Unified logging is the right place for a shipped app, but for a
        // prototype the evidence has to be capturable from a shell, and `log show` on a
        // short-lived process is unreliable. stderr lands in the redirect.
        FileHandle.standardError.write(Data("[advertise] \(message)\n".utf8))
        DispatchQueue.main.async { [weak self] in self?.onEvent?(message) }
    }

    /// Encodes a TXT record. Bonjour wants length-prefixed `key=value` pairs, not a plist.
    static func txtRecordData(_ record: ServiceTxtRecord) -> Data {
        var data = Data()
        // Sorted so the bytes are deterministic and a test can assert them exactly.
        for (key, value) in record.dictionary.sorted(by: { $0.key < $1.key }) {
            let entry = Array("\(key)=\(value)".utf8)
            // Each entry is at most 255 bytes; anything longer must be split, and these are tiny.
            data.append(UInt8(entry.count))
            data.append(contentsOf: entry)
        }
        return data
    }
}
