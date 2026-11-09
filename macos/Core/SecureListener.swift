import Foundation
import Network
import Security
import os

/// The Mac's TLS 1.3 listener with mutual authentication (ADR-012, ADR-011).
///
/// ## The trust model, stated plainly
///
/// There is no CA and no chain. The Android peer presents a self-signed certificate, and this
/// listener accepts it **only** if its public-key digest matches a pin the user confirmed. That
/// means the default TLS verification cannot be used — it would reject every self-signed peer — and
/// the usual shortcut of "trust everything" would accept every peer, including an attacker's.
///
/// The pin check therefore happens in the TLS handshake itself, via
/// `sec_protocol_options_set_verify_block`. Doing it after the handshake returns would mean the
/// handshake completed with an unverified peer and any bytes written before the check would already
/// be on the wire. Failing inside the verify block means an unpinned peer never completes a
/// handshake, so no application data can move.
///
/// ## What is deliberately not here
///
/// No plaintext fallback, no protocol downgrade (TLS 1.3 is set as both the minimum and the
/// maximum), and no permissive verify block. When `expectedPin` is nil the listener accepts the
/// handshake so the peer's certificate can be read and a short authentication code derived — but
/// the session is reported as unconfirmed and no application data is sent until the user compares
/// the codes.
public final class SecureListener {

    private let logger = Logger(subsystem: "com.droidbridge.macos", category: "P03Session")

    private let identity: MacIdentity
    private let expectedPin: String?
    private let queue = DispatchQueue(label: "com.droidbridge.p03.listener")

    private var listener: NWListener?
    private var connection: NWConnection?

    /// Called when a connection reaches `.ready`, with the peer's pin.
    public var onReady: ((_ peerPin: String) -> Void)?
    /// Called when bytes arrive.
    public var onData: ((_ data: Data) -> Void)?
    /// Called when the connection ends, with a human-readable reason.
    public var onClosed: ((_ reason: String) -> Void)?
    /// Called for each line of evidence worth recording.
    public var onEvent: ((_ event: String) -> Void)?

    /// The port the listener bound to. Valid after `start`.
    public private(set) var port: UInt16 = 0

    public init(identity: MacIdentity, expectedPin: String? = nil) {
        self.identity = identity
        self.expectedPin = expectedPin
    }

    /// Starts listening on an ephemeral port and advertises it over Bonjour.
    ///
    /// The port is dynamic (P03 requires it): a fixed port collides with whatever else the user
    /// runs and cannot be re-bound quickly after a crash. Bonjour carries the real port to the peer,
    /// which is the whole reason the service is advertised rather than an IP being typed in.
    public func start(serviceName: String = "DroidBridge-P03-Mac") throws {
        let tls = NWProtocolTLS.Options()

        // TLS 1.3 only. Not "preferred": a fallback is a downgrade path, and there is no legacy
        // peer to accommodate between two apps we ship.
        sec_protocol_options_set_min_tls_protocol_version(tls.securityProtocolOptions, .TLSv13)
        sec_protocol_options_set_max_tls_protocol_version(tls.securityProtocolOptions, .TLSv13)

        // Present our identity so the peer can authenticate us in return.
        let secIdentity = sec_identity_create(identity.secIdentity)
        guard let secIdentity else {
            throw SecureListenerError.identityUnavailable
        }
        sec_protocol_options_set_local_identity(tls.securityProtocolOptions, secIdentity)

        // Mutual authentication: require the peer's certificate. Without this the peer would be
        // anonymous and the pin would only protect one direction.
        sec_protocol_options_set_peer_authentication_required(tls.securityProtocolOptions, true)

        // The pin check, inside the handshake.
        let expectedPin = self.expectedPin
        let logger = self.logger
        let onEvent = self.onEvent
        sec_protocol_options_set_verify_block(
            tls.securityProtocolOptions,
            { _, secTrust, complete in
                // sec_trust_copy_ref returns a non-optional SecTrustRef.
                let trust = sec_trust_copy_ref(secTrust).takeUnretainedValue()
                guard let leaf = SecTrustGetCertificateAtIndex(trust, 0) else {
                    logger.error("verify: peer presented no certificate")
                    onEvent?("verify: peer presented no certificate")
                    complete(false)
                    return
                }

                let actualPin = MacIdentity.pin(ofSPKI: MacIdentity.subjectPublicKeyInfo(of: leaf))

                guard let expectedPin else {
                    // First pairing: accept so the code can be derived and shown. The session is
                    // reported unconfirmed and no application data is sent until the user confirms.
                    logger.info("verify: first pairing, peer pin \(actualPin, privacy: .public)")
                    onEvent?("verify: first pairing, peer pin \(actualPin)")
                    complete(true)
                    return
                }

                if actualPin == expectedPin {
                    logger.info("verify: pin matched \(actualPin, privacy: .public)")
                    onEvent?("verify: pin matched")
                    complete(true)
                } else {
                    // Fail the handshake itself, not a warning after it.
                    logger.error("verify: PIN MISMATCH expected \(expectedPin, privacy: .public) got \(actualPin, privacy: .public)")
                    onEvent?("verify: PIN MISMATCH expected \(expectedPin) got \(actualPin)")
                    complete(false)
                }
            },
            queue
        )

        let parameters = NWParameters(tls: tls, tcp: NWProtocolTCP.Options())
        parameters.allowLocalEndpointReuse = true

        let listener = try NWListener(using: parameters, on: .any)
        self.listener = listener

        listener.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            switch state {
            case .ready:
                let boundPort = listener.port?.rawValue ?? 0
                self.port = boundPort
                self.logger.info("listening on port \(boundPort, privacy: .public)")
                self.onEvent?("listening on port \(boundPort)")
            case .failed(let error):
                self.logger.error("listener failed: \(error.localizedDescription, privacy: .public)")
                self.onEvent?("listener failed: \(error.localizedDescription)")
                self.onClosed?("listener failed: \(error.localizedDescription)")
            default:
                break
            }
        }

        listener.newConnectionHandler = { [weak self] connection in
            self?.handle(connection)
        }

        // Advertise the same service type P02 verified, so Android's picker finds it.
        listener.service = NWListener.Service(
            name: serviceName,
            type: "_droidbridge-dev._tcp",
            domain: nil,
            txtRecord: nil
        )

        listener.start(queue: queue)
    }

    private func handle(_ connection: NWConnection) {
        self.connection = connection

        connection.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            switch state {
            case .ready:
                let pin = Self.peerPin(of: connection)
                self.logger.info("connection ready, peer pin \(pin, privacy: .public)")
                self.onEvent?("connection ready, peer pin \(pin)")
                self.onReady?(pin)
                Task { await self.startReceive(on: connection) }
            case .failed(let error):
                self.onEvent?("connection failed: \(error.localizedDescription)")
                self.onClosed?("connection failed: \(error.localizedDescription)")
            case .cancelled:
                self.onClosed?("connection cancelled")
            default:
                break
            }
        }

        connection.start(queue: queue)
    }

    @MainActor
    private func startReceive(on connection: NWConnection) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { [weak self] data, _, isComplete, error in
            guard let self else { return }
            if let data, !data.isEmpty {
                self.onData?(data)
            }
            if let error {
                self.onClosed?("receive error: \(error.localizedDescription)")
            }
            if isComplete {
                self.onClosed?("peer closed")
            }
            // The connection stays alive only for this SSE stream.
            // The server has no client-side receive API, so when the peer closes
            // we resign our own connection. Android is the connector and only
            // closes after the session ends.
            if isComplete {
                connection.cancel()
            }
        }
    }

    /// Sends bytes to the connected peer.
    public func send(_ data: Data) {
        connection?.send(content: data, completion: .contentProcessed { [weak self] error in
            if let error {
                self?.logger.error("send failed: \(error.localizedDescription, privacy: .public)")
            }
        })
    }

    /// Stops listening and closes any connection. Idempotent.
    public func stop() {
        connection?.cancel()
        connection = nil
        listener?.cancel()
        listener = nil
    }

    // MARK: - Helpers

    /// Reads the peer's pin back out of an established connection.
    ///
    /// The verify block already made the trust decision; this only reports what was accepted, so the
    /// UI and the log can name the peer.
    private static func peerPin(of connection: NWConnection) -> String {
        guard let metadata = connection.metadata(definition: NWProtocolTLS.definition) as? NWProtocolTLS.Metadata else {
            return "(no tls metadata)"
        }
        let secMetadata = metadata.securityProtocolMetadata

        // The peer chain is delivered through a block, not a single copy accessor.
        var leaf: SecCertificate?
        _ = sec_protocol_metadata_access_peer_certificate_chain(secMetadata) { certificate in
            if leaf == nil {
                leaf = sec_certificate_copy_ref(certificate).takeUnretainedValue()
            }
        }
        guard let leaf else {
            return "(no peer certificate)"
        }
        return MacIdentity.pin(ofSPKI: MacIdentity.subjectPublicKeyInfo(of: leaf))
    }
}

/// The listener could not start.
public enum SecureListenerError: Error, CustomStringConvertible {
    case identityUnavailable

    public var description: String {
        switch self {
        case .identityUnavailable:
            return "could not wrap the identity for Network.framework"
        }
    }
}
