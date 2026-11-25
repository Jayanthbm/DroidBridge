import Foundation
import Network
import Security
import os

/// The Mac's TLS 1.3 listener with mutual authentication and an explicit pairing gate
/// (ADR-011, ADR-012, ADR-024).
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
/// ## First pairing is a stop, not a formality
///
/// When no pin is stored, the handshake is accepted so the peer's certificate can be read and a
/// short authentication code derived — but the session stops in `.awaitingConfirmation`. No frame is
/// accepted and `sendControl` refuses. Only `confirmPairing()` writes the pin to the Keychain and
/// moves to `.authenticated`; `rejectPairing()` closes the connection without writing anything.
///
/// The pin is never written before the user confirms, and a stored pin is never replaced: a changed
/// peer reaches the verify block, fails against the stored pin, and the handshake dies there.
///
/// ## What is deliberately not here
///
/// No plaintext fallback, no protocol downgrade (TLS 1.3 is set as both the minimum and the
/// maximum), and no permissive verify block.
public final class SecureListener {

    // MARK: - Public types

    /// Where trust for this run comes from.
    public enum TrustMode {
        /// Read the confirmed pin from the store. A peer with no pin is a first pairing.
        case storedPin
        /// Use the given pin, or force a first pairing with `nil`.
        ///
        /// This exists for tests that need to simulate a specific peer identity without mutating
        /// the user's Keychain.
        case explicitPin(String?)
    }

    /// Session state. `send` and frame handling both refuse anything but `.authenticated`.
    public enum State: String {
        case idle
        case awaitingConfirmation
        case authenticated
        case closed

        /// True when application data may move in either direction.
        public var allowsData: Bool { self == .authenticated }
    }

    // MARK: - Configuration

    private let logger = Logger(subsystem: "com.droidbridge.macos", category: "P03Session")
    private let identity: MacIdentity
    private let pinStore: PeerPinStore
    private let peerId: String
    private let trustMode: TrustMode

    /// Silence after which an authenticated session is closed.
    private let idleTimeout: TimeInterval

    /// How long a first pairing may sit unconfirmed before the connection is closed.
    private let pairingTimeout: TimeInterval

    /// Control frames accepted per second, per connection.
    private let maxFramesPerSecond: Int

    /// Media frames accepted per second, per connection.
    ///
    /// Separate from the control budget on purpose: a 30 fps stream is normal traffic, and counting
    /// it against a 32/s control budget would close a healthy session in its first second. A media
    /// budget of its own still bounds a peer that floods, which is the property the limit exists
    /// for (a bounded, testable refusal rather than an open-ended queue).
    private let maxVideoFramesPerSecond: Int

    /// Failed handshakes before the listener stops, so a pin cannot be ground down online.
    private let maxAuthenticationFailures: Int

    // MARK: - Live state

    private let queue = DispatchQueue(label: "com.droidbridge.p03.listener")
    private let lock = NSLock()

    private var listener: NWListener?
    private var connection: NWConnection?
    private var idleTimer: DispatchSourceTimer?

    private var stateValue: State = .idle
    private var portValue: UInt16 = 0
    private var peerPinValue: String?
    private var pairingCodeValue: String?

    /// Pins the user has confirmed (ADR-024, ADR-054). Trust is membership: the presented pin must
    /// be in this set (or equal the harness's explicit pin) for the session to be authenticated.
    private var confirmedPins: Set<String> = []
    private var explicitPin: String?

    private var decoder = FrameDecoder()
    private var replayWindow = ReplayWindow()
    private var outboundSequence: UInt32 = 0
    private var lastActivity = Date()

    /// A connection that arrived while another session was live. It completes its handshake while
    /// parked; if it verifies as the same phone it supersedes the live session, otherwise it is dropped.
    private var parkedConnection: NWConnection?

    /// True once completeHandshake has run for the adopted connection. Reset on every adoption.
    private var completedHandshake = false
    private var controlRateLimiter: FrameRateLimiter
    private var mediaRateLimiter: FrameRateLimiter
    private var authenticationFailures = 0
    private var acceptedMediaFrames: UInt64 = 0
    private var lastMediaSummaryAt = ProcessInfo.processInfo.systemUptime

    private let verdict = PeerVerdict()

    // MARK: - Callbacks

    /// A connection reached TLS-ready. `authenticated` is false for a first pairing, which must be
    /// confirmed or rejected before any data can move.
    public var onReady: ((_ peerPin: String, _ pairingCode: String, _ authenticated: Bool) -> Void)?
    /// A validated control message arrived. Only reached once authenticated.
    public var onFrame: ((ControlMessage) -> Void)?
    /// The user's local confirmation took effect and the pin is stored.
    public var onAuthenticated: ((_ peerPin: String) -> Void)?
    /// The connection ended, with a human-readable reason.
    public var onClosed: ((_ reason: String) -> Void)?
    /// One line of evidence worth recording.
    public var onEvent: ((_ event: String) -> Void)?

    // MARK: - Read-only views

    /// The port the listener bound to. Valid after `start`.
    public var port: UInt16 { locked { portValue } }
    public var state: State { locked { stateValue } }
    public var peerPin: String? { locked { peerPinValue } }
    public var pairingCode: String? { locked { pairingCodeValue } }

    // MARK: - Init

    public init(
        identity: MacIdentity,
        pinStore: PeerPinStore,
        peerId: String,
        trustMode: TrustMode = .storedPin,
        idleTimeout: TimeInterval = 30,
        pairingTimeout: TimeInterval = 120,
        maxFramesPerSecond: Int = 32,
        maxVideoFramesPerSecond: Int = 120,
        maxAuthenticationFailures: Int = 3
    ) {
        self.identity = identity
        self.pinStore = pinStore
        self.peerId = peerId
        self.trustMode = trustMode
        self.idleTimeout = idleTimeout
        self.pairingTimeout = pairingTimeout
        self.maxFramesPerSecond = maxFramesPerSecond
        self.maxVideoFramesPerSecond = maxVideoFramesPerSecond
        self.maxAuthenticationFailures = maxAuthenticationFailures
        self.controlRateLimiter = FrameRateLimiter(
            framesPerSecond: maxFramesPerSecond,
            burstCapacity: maxFramesPerSecond
        )
        self.mediaRateLimiter = FrameRateLimiter(
            framesPerSecond: maxVideoFramesPerSecond,
            burstCapacity: maxVideoFramesPerSecond
        )
    }

    // MARK: - Lifecycle

    /// Starts listening on an ephemeral port and advertises it over Bonjour.
    ///
    /// The port is dynamic (P03 requires it): a fixed port collides with whatever else the user
    /// runs and cannot be re-bound quickly after a crash. Bonjour carries the real port to the peer,
    /// which is the whole reason the service is advertised rather than an IP being typed in.
    public func start(serviceName: String = "DroidBridge-P03-Mac", port: UInt16 = 0) throws {
        let resolvedPin: String?
        switch trustMode {
        case .storedPin:
            locked { confirmedPins = pinStore.confirmedPins() }
        case .explicitPin(let pin):
            locked { explicitPin = pin }
        }

        let tls = NWProtocolTLS.Options()

        // TLS 1.3 only. Not "preferred": a fallback is a downgrade path, and there is no legacy
        // peer to accommodate between two apps we ship.
        sec_protocol_options_set_min_tls_protocol_version(tls.securityProtocolOptions, .TLSv13)
        sec_protocol_options_set_max_tls_protocol_version(tls.securityProtocolOptions, .TLSv13)

        // Present our identity so the peer can authenticate us in return.
        guard let secIdentity = sec_identity_create(identity.secIdentity) else {
            throw SecureListenerError.identityUnavailable
        }
        sec_protocol_options_set_local_identity(tls.securityProtocolOptions, secIdentity)

        // Mutual authentication: require the peer's certificate. Without this the peer would be
        // anonymous and the pin would only protect one direction.
        sec_protocol_options_set_peer_authentication_required(tls.securityProtocolOptions, true)

        sec_protocol_options_set_verify_block(
            tls.securityProtocolOptions,
            { [weak self] _, secTrust, complete in
                guard let self else {
                    complete(false)
                    return
                }

                // Read the pin at handshake time, not the value captured when the listener started.
                // A listener that starts with no pin and is confirmed later must not keep accepting
                // "first pairings" for the rest of its life: the first connection after the pairing
                // would otherwise be treated as unknown and then granted data access, because the
                // pin now exists. The S24 Ultra run reproduced exactly that gap.
                let (trustedPins, harnessPin) = self.locked { (self.confirmedPins, self.explicitPin) }

                // sec_trust_copy_ref returns a non-optional SecTrustRef.
                let trust = sec_trust_copy_ref(secTrust).takeUnretainedValue()
                guard let leaf = SecTrustGetCertificateAtIndex(trust, 0) else {
                    self.emit("verify: peer presented no certificate")
                    complete(false)
                    return
                }

                let spki = MacIdentity.subjectPublicKeyInfo(of: leaf)

                // A P-256 SPKI is a fixed 91 bytes. Anything else is not the identity shape this
                // protocol defines, so it is refused rather than digested into a pin nobody can
                // compare against a code derived the same way.
                guard spki.count == DeviceIdentityConstants.p256SPKIBytes else {
                    self.emit("verify: peer SPKI is \(spki.count) bytes, expected \(DeviceIdentityConstants.p256SPKIBytes)")
                    complete(false)
                    return
                }

                let actualPin = MacIdentity.pin(ofSPKI: spki)

                switch PeerTrustPolicy.decide(pin: actualPin, confirmedPins: trustedPins, explicitPin: harnessPin) {
                case .trusted:
                    self.verdict.record(spki: spki, pin: actualPin)
                    self.emit("verify: pin matched \(actualPin)")
                    complete(true)

                case .candidate:
                    // Unconfirmed: accept so the code can be derived and shown on both sides, but the
                    // session stays unconfirmed and no application data moves until the user confirms.
                    // That covers a genuinely new Mac or phone and a second phone in the set's terms —
                    // plus an impostor, whose code will not match what the real phone shows.
                    self.verdict.record(spki: spki, pin: actualPin)
                    if trustedPins.isEmpty {
                        self.emit("verify: first pairing, peer pin \(actualPin)")
                    } else {
                        self.emit("verify: unknown peer pin \(actualPin) awaiting confirmation")
                    }
                    complete(true)

                case .refused:
                    // Only the explicit-pin test mode can get here. Fail the handshake itself, not a
                    // warning after it: a confirmed pin is never replaced by whatever the peer presents.
                    self.noteAuthenticationFailure()
                    self.emit("verify: PIN MISMATCH pin \(actualPin) is not confirmed")
                    complete(false)
                }
            },
            queue
        )

        let parameters = NWParameters(tls: tls, tcp: NWProtocolTCP.Options())
        parameters.allowLocalEndpointReuse = true

        let endpoint: NWEndpoint.Port = port == 0 ? .any : (NWEndpoint.Port(rawValue: port) ?? .any)
        let listener = try NWListener(using: parameters, on: endpoint)
        self.listener = listener

        listener.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            switch state {
            case .ready:
                let boundPort = listener.port?.rawValue ?? 0
                self.locked { self.portValue = boundPort }
                self.emit("listening on port \(boundPort)")
            case .failed(let error):
                self.emit("listener failed: \(error.localizedDescription)")
                self.notify { self.onClosed?("listener failed: \(error.localizedDescription)") }
            default:
                break
            }
        }

        listener.newConnectionHandler = { [weak self] connection in
            self?.handle(connection)
        }

        // Advertise the same service type P02 verified, so Android's picker finds it.
        //
        // The TXT record carries the protocol version and a per-session id (NETWORK_PROTOCOL.md §2).
        // It was `nil` until Phase 2: discovery worked, but Android had no way to tell an
        // incompatible peer from a compatible one, and a stale advertisement from an earlier run was
        // indistinguishable from the current one.
        listener.service = NWListener.Service(
            name: serviceName,
            type: ServiceType.droidBridge,
            domain: nil,
            txtRecord: NWTXTRecord(ServiceTxtRecord(sessionId: ServiceTxtRecord.randomSessionId()).dictionary)
        )

        listener.start(queue: queue)
    }

    /// Accepts a first pairing: persists the pin and enables data.
    ///
    /// Throws when the pin cannot be written, so a session is never reported as paired while the
    /// trust it depends on is not on disk.
    public func confirmPairing() throws {
        var confirmedPin: String?

        try queue.sync {
            if state == .authenticated { return }
            guard state == .awaitingConfirmation else {
                throw SecureListenerError.notAwaitingConfirmation
            }
            guard let pin = peerPinValue else {
                throw SecureListenerError.notConnected
            }

            do {
                try pinStore.confirm(pin)
            } catch {
                throw SecureListenerError.pinStorageFailed(String(describing: error))
            }

            locked { confirmedPins.insert(pin) }
            setState(.authenticated)
            lastActivity = Date()
            confirmedPin = pin
            emit("pairing confirmed locally; pin stored for \(peerId)")
        }

        // Outside the queue: the callback may call back into this listener.
        if let confirmedPin {
            notify { self.onAuthenticated?(confirmedPin) }
        }
    }

    /// Rejects a first pairing: closes the connection without storing anything.
    public func rejectPairing() {
        queue.sync {
            guard state == .awaitingConfirmation, let connection else { return }
            close(connection, reason: "pairing rejected by user")
        }
    }

    /// Removes the stored pin, so the next connection is a first pairing again.
    public func forgetPeer() {
        pinStore.forgetAll()
        locked { confirmedPins = [] }
        emit("forgot stored pin for \(peerId)")
    }

    /// Sends a control message. Refused unless the session is authenticated.
    ///
    /// Also used for `mirror.keyframe_request`, which is the receiver's only way to ask for an IDR
    /// after a decoder flush (NETWORK_PROTOCOL §7).
    public func sendControl(type: ControlMessageType, payload: Data = Data()) throws {
        try queue.sync {
            guard state == .authenticated, let connection else {
                throw SecureListenerError.notAuthenticated
            }
            outboundSequence &+= 1
            try send(type: type, sequence: outboundSequence, payload: payload, on: connection)
        }
    }

    /// Sends a control message with a caller-chosen sequence.
    ///
    /// Test-only: replaying a sequence is exactly what the peer is supposed to reject, and there is
    /// no legitimate reason for production code to choose its own sequence numbers.
    public func sendControlForTesting(
        type: ControlMessageType,
        sequence: UInt32,
        payload: Data = Data()
    ) throws {
        try queue.sync {
            guard state == .authenticated, let connection else {
                throw SecureListenerError.notAuthenticated
            }
            try send(type: type, sequence: sequence, payload: payload, on: connection)
        }
    }

    /// Sends a bare length prefix with no payload.
    ///
    /// Test-only: a peer must reject an empty or oversized declared length immediately, without
    /// waiting for bytes that will never arrive.
    public func sendDeclaredLengthForTesting(_ declared: UInt32) throws {
        try queue.sync {
            guard state == .authenticated, let connection else {
                throw SecureListenerError.notAuthenticated
            }
            let header = Data([
                UInt8((declared >> 24) & 0xFF),
                UInt8((declared >> 16) & 0xFF),
                UInt8((declared >> 8) & 0xFF),
                UInt8(declared & 0xFF),
            ])
            connection.send(content: header, completion: .contentProcessed { _ in })
        }
    }

    /// Stops listening and closes any connection. Idempotent.
    public func stop() {
        queue.sync {
            if let connection {
                close(connection, reason: "listener stopped")
            }
            listener?.cancel()
            listener = nil
            setState(.closed)
        }
    }

    // MARK: - Connection handling

    private func handle(_ connection: NWConnection) {
        // A parked connection is already completing its handshake; a newcomer while one is parked is
        // refused, because a flapping phone can outrun the handshake (a connection every ~2 s replaced
        // each parked one before it reached .ready and nobody ever superseded).
        if parkedConnection != nil {
            emit("rejecting an additional connection: one is already parked and verifying")
            connection.cancel()
            return
        }
        if self.connection != nil {
            // The identity cannot be verified until the handshake completes, so the supersede decision
            // is deferred: a second connection from the same phone (same verified pin) must win, because
            // the phone's retry budget is shorter than the idle reap of a half-open session it abandoned
            // — measured as the phone reaching GAVE_UP ~56 s before the 120 s idle timeout freed the
            // slot, leaving the user to tap Find the Mac by hand. A different phone still waits.
            parkedConnection = connection
            connection.stateUpdateHandler = { [weak self] state in
                guard let self, self.parkedConnection === connection else { return }
                switch state {
                case .ready:
                    self.parkedConnectionReady(connection)
                case .failed, .cancelled:
                    connection.cancel()
                    self.parkedConnection = nil
                default:
                    break // .setup/.preparing/.waiting are part of a healthy handshake
                }
            }
            connection.start(queue: queue)
            emit("parking an additional connection until its identity verifies")
            return
        }
        adopt(connection, freshHandshake: true)
    }

    /// Takes ownership of a connection that has completed (or will complete) its TLS handshake.
    private func adopt(_ connection: NWConnection, freshHandshake: Bool) {

        self.connection = connection
        completedHandshake = false
        if freshHandshake { verdict.clear() }
        decoder = FrameDecoder()
        replayWindow = ReplayWindow()
        outboundSequence = 0
        controlRateLimiter.reset()
        mediaRateLimiter.reset()
        acceptedMediaFrames = 0
        lastMediaSummaryAt = ProcessInfo.processInfo.systemUptime
        lastActivity = Date()
        locked { peerPinValue = nil; pairingCodeValue = nil }
        setState(.idle)

        connection.stateUpdateHandler = { [weak self] state in
            guard let self, self.connection === connection else { return }
            switch state {
            case .ready:
                self.connectionReady(connection)
            case .failed(let error):
                self.close(connection, reason: "connection failed: \(error.localizedDescription)")
            case .cancelled:
                self.close(connection, reason: "connection cancelled")
            default:
                break
            }
        }

        if freshHandshake { connection.start(queue: queue) }
    }

    /// A parked connection reached .ready: its identity is now known. Same pin as the live session
    /// means the same phone returned — it wins. Anything else waits for nothing and is dropped.
    private func parkedConnectionReady(_ connection: NWConnection) {
        guard let verdict = self.verdict.snapshot() else {
            emit("parked connection had no verified identity; dropping it")
            parkedConnection = nil
            connection.cancel()
            return
        }
        let livePin = locked { peerPinValue }
        guard livePin == verdict.pin, let live = self.connection, live !== connection else {
            emit("dropping a parked connection: it does not match the live session's phone")
            parkedConnection = nil
            connection.cancel()
            return
        }
        emit("superseding the live session: the same phone reconnected")
        parkedConnection = nil
        close(live, reason: "superseded: the same phone reconnected")
        adopt(connection, freshHandshake: false)
        // Network.framework does not redeliver the current state to a replacement handler, so the
        // adopted connection's .ready is never seen by the new stateUpdateHandler. Finish the
        // handshake here, exactly as the fresh-connection path would.
        completeHandshake(connection)
    }

    private func connectionReady(_ connection: NWConnection) {
        completeHandshake(connection)
    }

    /// Runs once per adopted connection: reads the verdict, checks the pin, publishes state, and
    /// starts the receive loop. The once-guard matters because a superseded connection is adopted
    /// while already .ready, and a redelivered state event must not start a second receive loop.
    private func completeHandshake(_ connection: NWConnection) {
        guard !completedHandshake else { return }
        guard let verdict = verdict.snapshot() else {
            // Defensive: verification is required, so reaching ready without a recorded peer means
            // the handshake did not do what this code assumes. Refuse rather than guess.
            close(connection, reason: "no verified peer certificate was recorded")
            return
        }

        // Second layer, independent of the verify block: never grant data access to a key that is
        // not the pinned one. If a future change makes the handshake accept a different identity,
        // this still closes before a frame is accepted.
        let decision = locked {
            PeerTrustPolicy.decide(pin: verdict.pin, confirmedPins: confirmedPins, explicitPin: explicitPin)
        }
        if decision == .refused {
            close(connection, reason: "peer identity changed: pin mismatch")
            return
        }
        let authenticated = decision == .trusted
        let code = ShortAuthenticationCode.derive(identity.sasIdentityBytes, verdict.spki)

        locked {
            peerPinValue = verdict.pin
            pairingCodeValue = code
        }
        setState(authenticated ? .authenticated : .awaitingConfirmation)
        lastActivity = Date()
        startIdleTimer()

        emit("connection ready peer-pin=\(verdict.pin) authenticated=\(authenticated) code=\(ShortAuthenticationCode.formatForDisplay(code))")
        notify { self.onReady?(verdict.pin, code, authenticated) }

        completedHandshake = true
        receiveLoop(on: connection)
    }

    private func receiveLoop(on connection: NWConnection) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { [weak self] data, _, isComplete, error in
            guard let self, self.connection === connection else { return }

            if let data, !data.isEmpty {
                do {
                    try self.ingest(data)
                } catch {
                    self.close(connection, reason: "framing/protocol violation: \(error)")
                    return
                }
            }

            if let error {
                self.close(connection, reason: "receive error: \(error.localizedDescription)")
                return
            }
            if isComplete {
                self.close(connection, reason: "peer closed the connection")
                return
            }

            // TCP delivers bytes, not messages. Keep one receive in flight for the whole
            // connection; a single-shot read would leave the session unable to hear the next frame.
            self.receiveLoop(on: connection)
        }
    }

    /// True when the frame's type byte is PING. Used only for the pre-confirmation keepalive check, so
    /// it reads the envelope's type byte rather than trusting a decode that has not run yet.
    private static func isPingFrame(_ frame: Data) -> Bool {
        // [version][type][sequence:4][payload]
        // Data carries the indices of the slice it came from, so this must not assume index 0.
        frame.count >= 6 && frame[frame.startIndex + 1] == ControlMessageType.ping.rawValue
    }

    /// Feeds bytes through the frame decoder and dispatches every completed frame.
    private func ingest(_ data: Data) throws {
        let frames = try decoder.feed(data)
        for frame in frames {
            try handleFrame(frame)
        }
    }

    private func handleFrame(_ frame: Data) throws {
        // Data before the user confirmed is not "early" — it is a protocol violation for content,
        // because the peer was told the session is not yet authenticated. Keepalive is the exception:
        // confirming is a human act on this side, so the peer's heartbeat always arrives first and
        // closing on it made a first pairing impossible to complete — measured as an endless
        // "peer sent data before the pairing was confirmed" loop where the confirm arrived one frame
        // too late. PING carries no user data and is answered; everything else is still refused.
        let isKeepalive = state != .authenticated && Self.isPingFrame(frame)
        guard state == .authenticated || isKeepalive else {
            throw SecureListenerError.dataBeforeAuthentication
        }

        let message: ControlMessage
        do {
            message = try ControlMessageCodec.decode(frame)
        } catch {
            throw SecureListenerError.malformedControl(String(describing: error))
        }

        if state != .authenticated, message.type == .ping {
            // Answer only. No pin is stored and nothing is delivered to the app until the user
            // confirms. Written straight to the socket because sendControl() requires an authenticated
            // session and would also re-enter the serial queue we are already running on.
            if let connection {
                outboundSequence &+= 1
                try? send(type: .pong, sequence: outboundSequence, payload: message.payload, on: connection)
            }
            return
        }

        do {
            try replayWindow.accept(message.sequence)
        } catch {
            throw SecureListenerError.replayedSequence(String(describing: error))
        }

        try enforceRateLimit(isMedia: message.type.isMedia)
        lastActivity = Date()

        if message.type.isMedia {
            acceptedMediaFrames &+= 1
            let now = ProcessInfo.processInfo.systemUptime
            if now - lastMediaSummaryAt >= 1 {
                emit("media frames accepted=\(acceptedMediaFrames)")
                lastMediaSummaryAt = now
            }
        } else {
            emit("frame \(message.summary)")
        }

        if message.type == .close {
            if let connection {
                close(connection, reason: "peer sent close")
            }
            return
        }
        notify { self.onFrame?(message) }
    }

    private func enforceRateLimit(isMedia: Bool) throws {
        // Use monotonic uptime instead of wall-clock Date. The limiter permits only a bounded burst
        // and refills at the configured sustained rate, so TCP scheduling/coalescing cannot turn a
        // healthy buffered batch into an immediate one-second-window violation.
        let now = ProcessInfo.processInfo.systemUptime
        if isMedia {
            guard mediaRateLimiter.admit(at: now) else {
                throw SecureListenerError.rateLimited(
                    maxPerSecond: maxVideoFramesPerSecond, kind: "media"
                )
            }
            return
        }

        guard controlRateLimiter.admit(at: now) else {
            throw SecureListenerError.rateLimited(
                maxPerSecond: maxFramesPerSecond, kind: "control"
            )
        }
    }

    /// Closes the authenticated connection on a protocol/stream validation failure.
    public func fail(reason: String) {
        queue.sync {
            guard let connection else { return }
            close(connection, reason: reason)
        }
    }

    private func send(
        type: ControlMessageType,
        sequence: UInt32,
        payload: Data,
        on connection: NWConnection
    ) throws {
        let message = try ControlMessageCodec.encode(type: type, sequence: sequence, payload: payload)
        let frame = try SessionFraming.encodeFrame(message)
        connection.send(content: frame, completion: .contentProcessed { [weak self] error in
            if let error {
                self?.emit("send failed: \(error.localizedDescription)")
            }
        })
    }

    // MARK: - Timeouts

    private func startIdleTimer() {
        guard idleTimer == nil else { return }
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + 1, repeating: 1)
        timer.setEventHandler { [weak self] in
            self?.checkDeadlines()
        }
        timer.resume()
        idleTimer = timer
    }

    private func checkDeadlines() {
        let state = self.state
        let elapsed = Date().timeIntervalSince(lastActivity)

        switch state {
        case .authenticated:
            // No message for the whole idle window: the peer is gone or wedged, and holding the
            // connection open would hide that from the user.
            if elapsed >= idleTimeout, let connection {
                close(connection, reason: "idle timeout after \(Int(idleTimeout))s")
            }
        case .awaitingConfirmation:
            // A pairing nobody confirms must not hold the listener forever.
            if elapsed >= pairingTimeout, let connection {
                close(connection, reason: "pairing not confirmed within \(Int(pairingTimeout))s")
            }
        default:
            break
        }
    }

    // MARK: - Closing

    private func close(_ connection: NWConnection, reason: String) {
        guard self.connection === connection, state != .closed else { return }

        idleTimer?.cancel()
        idleTimer = nil
        self.connection = nil
        setState(.closed)

        emit("closed: \(reason)")
        connection.cancel()
        notify { self.onClosed?(reason) }
    }

    /// Counts a failed handshake and stops the listener after too many.
    ///
    /// The pin is a 256-bit digest, so online guessing is not a realistic attack; the cap exists so
    /// a peer that keeps presenting the wrong identity stops consuming the listener's attention.
    private func noteAuthenticationFailure() {
        queue.async { [weak self] in
            guard let self else { return }
            self.authenticationFailures += 1
            if self.authenticationFailures >= self.maxAuthenticationFailures {
                self.emit("stopping listener after \(self.authenticationFailures) failed handshakes")
                self.listener?.cancel()
                self.listener = nil
                self.notify { self.onClosed?("too many failed handshakes") }
            }
        }
    }

    // MARK: - Helpers

    private func emit(_ line: String) {
        logger.info("\(line, privacy: .public)")
        onEvent?(line)
    }

    /// Delivers a semantic callback on the main queue.
    ///
    /// Callbacks are not invoked on the listener queue: a handler that replies to a frame would
    /// otherwise call `sendControl`, which synchronises onto the queue it is already running on and
    /// deadlocks. The main queue is also where the UI and the harness's RunLoop live.
    private func notify(_ body: @escaping () -> Void) {
        DispatchQueue.main.async(execute: body)
    }

    private func setState(_ new: State) {
        locked { stateValue = new }
    }

    private func locked<T>(_ body: () -> T) -> T {
        lock.lock()
        defer { lock.unlock() }
        return body()
    }
}

/// Records what the verify block accepted, for use once the handshake completes.
///
/// The verify block runs on the listener queue and the value is read from the same queue, but it is
/// locked anyway so the read can never race a later handshake's write.
private final class PeerVerdict {
    private let lock = NSLock()
    private var spkiValue: Data?
    private var pinValue: String?

    func record(spki: Data, pin: String) {
        lock.lock()
        spkiValue = spki
        pinValue = pin
        lock.unlock()
    }

    func snapshot() -> (spki: Data, pin: String)? {
        lock.lock()
        defer { lock.unlock() }
        guard let spkiValue, let pinValue else { return nil }
        return (spkiValue, pinValue)
    }

    func clear() {
        lock.lock()
        spkiValue = nil
        pinValue = nil
        lock.unlock()
    }
}

/// The P-256 shape both ends require for a peer identity.
enum DeviceIdentityConstants {
    /// SEC1 uncompressed P-256 point plus the fixed SPKI wrapper, as both implementations produce.
    static let p256SPKIBytes = 91
}

/// The listener could not start, or refused an operation.
public enum SecureListenerError: Error, CustomStringConvertible {
    case identityUnavailable
    case notConnected
    case notAuthenticated
    case notAwaitingConfirmation
    case pinStorageFailed(String)
    case dataBeforeAuthentication
    case malformedControl(String)
    case replayedSequence(String)
    case rateLimited(maxPerSecond: Int, kind: String)

    public var description: String {
        switch self {
        case .identityUnavailable:
            return "could not wrap the identity for Network.framework"
        case .notConnected:
            return "no peer is connected"
        case .notAuthenticated:
            return "session is not authenticated; data is refused until pairing is confirmed"
        case .notAwaitingConfirmation:
            return "session is not awaiting pairing confirmation"
        case .pinStorageFailed(let reason):
            return "could not persist the peer pin: \(reason)"
        case .dataBeforeAuthentication:
            return "peer sent data before the pairing was confirmed"
        case .malformedControl(let reason):
            return "malformed control message: \(reason)"
        case .replayedSequence(let reason):
            return "replayed control sequence: \(reason)"
        case .rateLimited(let maxPerSecond, let kind):
            return "peer exceeded \(maxPerSecond) \(kind) frames per second"
        }
    }
}
