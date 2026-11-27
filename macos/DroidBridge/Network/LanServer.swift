import Foundation
import Network
import Security
import Darwin

struct PairingRequest: Identifiable, Sendable {
    let id: UUID
    let deviceID: String
    let name: String
    let model: String
    let platform: String
    let osVersion: String
    let battery: Int
    let code: String
    let fingerprint: String
}

struct ConnectedPeer: Sendable {
    let sessionID: UUID
    let deviceID: String
    let name: String
    let model: String
    let osVersion: String
    let battery: Int
    let fingerprint: String
}

enum LanServerEvent: Sendable {
    case ready(port: UInt16)
    case pairing(PairingRequest)
    case pairingEnded(UUID)
    case connected(ConnectedPeer)
    case disconnected(deviceID: String, reason: String)
    case video(deviceID: String, payload: Data)
    case mirrorStopped(deviceID: String)
    case failed(String)
}

private final class FingerprintBox: @unchecked Sendable {
    private let lock = NSLock()
    private var stored: String?

    func setIfEmpty(_ value: String) {
        lock.lock()
        defer { lock.unlock() }
        if stored == nil { stored = value }
    }

    var value: String? {
        lock.lock()
        defer { lock.unlock() }
        return stored
    }
}

@MainActor
final class LanServer {
    let identity: MacIdentity
    var eventHandler: ((LanServerEvent) -> Void)?
    private(set) var port: UInt16?

    private let queue = DispatchQueue(label: "com.jayanth.droidbridge.lan", qos: .userInitiated)
    private let trust: PeerTrustStore
    private var listener: NWListener?
    private var sessions: [UUID: PeerSession] = [:]
    private let autoConfirmPairing: Bool

    init(trust: PeerTrustStore = PeerTrustStore()) throws {
        self.trust = trust
        self.identity = try MacIdentityStore.loadOrCreate()
        self.autoConfirmPairing = ProcessInfo.processInfo.arguments.contains("--auto-pair")
    }

    func start() {
        guard listener == nil else { return }
        do {
            let tls = NWProtocolTLS.Options()
            sec_protocol_options_set_min_tls_protocol_version(tls.securityProtocolOptions, .TLSv13)
            guard let localIdentity = identity.networkIdentity else {
                eventHandler?(.failed("Could not create the Mac TLS identity."))
                return
            }
            sec_protocol_options_set_local_identity(tls.securityProtocolOptions, localIdentity)
            sec_protocol_options_set_peer_authentication_required(tls.securityProtocolOptions, true)
            sec_protocol_options_set_verify_block(tls.securityProtocolOptions, { @Sendable _, _, complete in
                // Peer certificates are pinned at the application layer after
                // HELLO, because first-time pairing starts with no pin.
                complete(true)
            }, queue)

            let tcp = NWProtocolTCP.Options()
            tcp.noDelay = true
            tcp.connectionTimeout = 10
            let parameters = NWParameters(tls: tls, tcp: tcp)
            parameters.includePeerToPeer = true
            parameters.allowLocalEndpointReuse = true

            let listener = try NWListener(using: parameters)
            listener.service = NWListener.Service(
                name: "DroidBridge",
                type: "_droidbridge._tcp"
            )
            listener.stateUpdateHandler = { [weak self] state in
                Task { @MainActor in self?.handleListenerState(state) }
            }
            listener.newConnectionHandler = { [weak self] connection in
                Task { @MainActor in self?.accept(connection) }
            }
            self.listener = listener
            listener.start(queue: queue)
        } catch {
            eventHandler?(.failed(error.localizedDescription))
        }
    }

    func stop() {
        listener?.cancel()
        listener = nil
        for session in sessions.values {
            session.connection.cancel()
        }
        sessions.removeAll()
        port = nil
    }

    func confirmPairing(sessionID: UUID) {
        guard let session = sessions[sessionID], !session.connected else { return }
        guard !session.localConfirmed else { return }
        session.localConfirmed = true
        sendControl("pair_confirm", deviceID: identity.deviceID, to: session)
        if session.remoteConfirmed {
            finishPairing(session)
        }
    }

    func cancelPairing(sessionID: UUID) {
        guard let session = sessions[sessionID] else { return }
        sendControl("pair_cancel", to: session)
        eventHandler?(.pairingEnded(sessionID))
        drop(session, reason: "Pairing cancelled")
    }

    func disconnect(deviceID: String) {
        guard let session = sessions.values.first(where: { $0.deviceID == deviceID }) else { return }
        session.closing = true
        sendControl("disconnect", to: session)
        // Let the phone consume the control frame and close its side cleanly.
        // Cancelling the server connection immediately can deliver EOF first,
        // which makes the phone treat a deliberate disconnect as a network
        // drop and reconnect automatically.
        Task { @MainActor [weak self] in
            try? await Task.sleep(for: .seconds(2))
            guard let self, let session = self.sessions[session.id], session.closing else { return }
            self.drop(session, reason: "Disconnected from Mac")
        }
    }

    func waitForPairingDevice() {
        // The laptop is advertised continuously; opening the pairing sheet is UI-only.
    }

    func requestKeyframe(deviceID: String) {
        guard let session = sessions.values.first(where: { $0.deviceID == deviceID }) else { return }
        sendControl("keyframe", to: session)
    }

    func stopMirroring(deviceID: String) {
        guard let session = sessions.values.first(where: { $0.deviceID == deviceID }) else { return }
        sendControl("stop_mirror", to: session)
    }

    private func handleListenerState(_ state: NWListener.State) {
        switch state {
        case .ready:
            if let port = listener?.port?.rawValue {
                self.port = port
                eventHandler?(.ready(port: port))
            }
        case .failed(let error):
            eventHandler?(.failed(error.localizedDescription))
        case .cancelled:
            port = nil
        default:
            break
        }
    }

    private func accept(_ connection: NWConnection) {
        let session = PeerSession(connection: connection)
        sessions[session.id] = session
        connection.stateUpdateHandler = { [weak self] state in
            Task { @MainActor in
                guard let self else { return }
                switch state {
                case .ready:
                    self.handleReady(sessionID: session.id)
                case .failed(let error):
                    self.drop(sessionID: session.id, reason: error.localizedDescription)
                case .cancelled:
                    self.drop(sessionID: session.id, reason: "Connection closed")
                default:
                    break
                }
            }
        }
        connection.start(queue: queue)
    }

    private func handleReady(sessionID: UUID) {
        guard let session = sessions[sessionID] else { return }
        guard let tlsMetadata = session.connection.metadata(definition: NWProtocolTLS.definition) as? NWProtocolTLS.Metadata else {
            drop(session, reason: "TLS metadata unavailable")
            return
        }

        let box = FingerprintBox()
        _ = sec_protocol_metadata_access_peer_certificate_chain(tlsMetadata.securityProtocolMetadata) { certificate in
            let cert = sec_certificate_copy_ref(certificate).takeRetainedValue()
            let data = SecCertificateCopyData(cert) as Data
            box.setIfEmpty(SHA256Fingerprint.hex(data))
        }
        guard let fingerprint = box.value else {
            drop(session, reason: "The phone did not present a client certificate")
            return
        }

        session.peerFingerprint = fingerprint
        let trusted = trust.isTrusted(fingerprint: fingerprint)
        do {
            try sendHello(to: session, paired: trusted)
        } catch {
            drop(session, reason: error.localizedDescription)
            return
        }
        receiveNext(sessionID: session.id)
    }

    private func sendHello(to session: PeerSession, paired: Bool) throws {
        let os = ProcessInfo.processInfo.operatingSystemVersion
        let hello = WireHello(
            deviceID: identity.deviceID,
            name: identity.displayName,
            model: macModelName(),
            platform: "macos",
            fingerprint: identity.fingerprint,
            paired: paired,
            battery: nil,
            osVersion: "macOS \(os.majorVersion).\(os.minorVersion)"
        )
        let data = try WireCodec.frame(type: .hello, message: hello)
        send(data, to: session)
    }

    private func receiveNext(sessionID: UUID) {
        guard let session = sessions[sessionID] else { return }
        session.connection.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { [weak self] data, _, isComplete, error in
            Task { @MainActor in
                guard let self, let session = self.sessions[sessionID] else { return }
                if let data, !data.isEmpty {
                    do {
                        let frames = try session.decoder.append(data)
                        for frame in frames {
                            self.handle(frame: frame, session: session)
                        }
                    } catch {
                        self.drop(session, reason: error.localizedDescription)
                        return
                    }
                }
                if let error {
                    self.drop(session, reason: error.localizedDescription)
                    return
                }
                if isComplete {
                    self.drop(session, reason: "The phone closed the session")
                    return
                }
                self.receiveNext(sessionID: sessionID)
            }
        }
    }

    private func handle(frame: WireFrame, session: PeerSession) {
        do {
            switch frame.type {
            case .hello:
                let hello = try WireCodec.decode(WireHello.self, from: frame.payload)
                try handleHello(hello, session: session)
            case .control:
                let control = try WireCodec.decode(WireControl.self, from: frame.payload)
                try handleControl(control, session: session)
            case .ping:
                // Echo the heartbeat payload so the peer can measure liveness.
                send(WireCodec.frame(type: .ping, payload: frame.payload), to: session)
            case .video:
                guard session.connected, let deviceID = session.deviceID else { return }
                eventHandler?(.video(deviceID: deviceID, payload: frame.payload))
            case .audio, .clipboard:
                break
            }
        } catch {
            drop(session, reason: error.localizedDescription)
        }
    }

    private func handleHello(_ hello: WireHello, session: PeerSession) throws {
        guard hello.protocolVersion == 2 else {
            throw WireProtocolError.invalidJSON
        }
        guard hello.fingerprint == session.peerFingerprint else {
            throw WireProtocolError.invalidJSON
        }
        session.peerHello = hello
        session.deviceID = hello.deviceID

        let trusted = trust.isTrusted(fingerprint: hello.fingerprint)
        if trusted && hello.paired {
            if !session.connected {
                completeConnection(session, hello: hello, storePin: false)
            }
        } else {
            beginPairing(session, hello: hello)
        }
    }

    private func beginPairing(_ session: PeerSession, hello: WireHello) {
        guard session.pairCode == nil, !session.connected else { return }
        guard let peerFingerprint = session.peerFingerprint else { return }
        let code = SHA256Fingerprint.pairingCode(local: identity.fingerprint, peer: peerFingerprint)
        session.pairCode = code
        let request = PairingRequest(
            id: session.id,
            deviceID: hello.deviceID,
            name: hello.name,
            model: hello.model,
            platform: hello.platform,
            osVersion: hello.osVersion ?? "Android",
            battery: hello.battery ?? 0,
            code: code,
            fingerprint: hello.fingerprint
        )
        eventHandler?(.pairing(request))
        session.localConfirmed = false
        session.remoteConfirmed = false

        if autoConfirmPairing {
            Task { @MainActor [weak self] in
                try? await Task.sleep(for: .milliseconds(900))
                self?.confirmPairing(sessionID: session.id)
            }
        }
    }

    private func handleControl(_ control: WireControl, session: PeerSession) throws {
        switch control.action {
        case "pair_confirm":
            guard session.pairCode != nil else { return }
            session.remoteConfirmed = true
            if session.localConfirmed, let hello = session.peerHello {
                completeConnection(session, hello: hello, storePin: true)
            }
        case "pair_cancel":
            eventHandler?(.pairingEnded(session.id))
            drop(session, reason: "Pairing cancelled by phone")
        case "disconnect":
            drop(session, reason: "Disconnected from phone")
        case "mirror_stopped":
            if let deviceID = session.deviceID {
                eventHandler?(.mirrorStopped(deviceID: deviceID))
            }
        default:
            break
        }
    }

    private func finishPairing(_ session: PeerSession) {
        guard let hello = session.peerHello else { return }
        completeConnection(session, hello: hello, storePin: true)
    }

    private func completeConnection(_ session: PeerSession, hello: WireHello, storePin: Bool) {
        guard let fingerprint = session.peerFingerprint else { return }
        if storePin {
            let peer = TrustedPeer(
                fingerprint: fingerprint,
                deviceID: hello.deviceID,
                name: hello.name,
                model: hello.model,
                platform: hello.platform,
                pairedAt: Date()
            )
            trust.remember(peer)
        }
        session.connected = true
        session.pairCode = nil
        let connected = ConnectedPeer(
            sessionID: session.id,
            deviceID: hello.deviceID,
            name: hello.name,
            model: hello.model,
            osVersion: hello.osVersion ?? "Android",
            battery: hello.battery ?? 0,
            fingerprint: fingerprint
        )
        eventHandler?(.pairingEnded(session.id))
        eventHandler?(.connected(connected))
        sendControl("pair_ok", deviceID: identity.deviceID, to: session)
    }

    private func sendControl(_ action: String, deviceID: String? = nil, to session: PeerSession) {
        let control = WireControl(kind: "control", action: action, value: deviceID)
        if let data = try? WireCodec.frame(type: .control, message: control) {
            send(data, to: session)
        }
    }

    private func send(_ data: Data, to session: PeerSession) {
        session.connection.send(content: data, completion: .contentProcessed { _ in })
    }

    private func drop(sessionID: UUID, reason: String) {
        guard let session = sessions.removeValue(forKey: sessionID) else { return }
        session.connection.cancel()
        if let deviceID = session.deviceID, session.connected {
            eventHandler?(.disconnected(deviceID: deviceID, reason: reason))
        }
        if session.pairCode != nil {
            eventHandler?(.pairingEnded(session.id))
        }
    }

    private func drop(_ session: PeerSession, reason: String) {
        drop(sessionID: session.id, reason: reason)
    }

    private func macModelName() -> String {
        var size = 0
        sysctlbyname("hw.model", nil, &size, nil, 0)
        guard size > 0 else { return "Mac" }
        var value = [CChar](repeating: 0, count: size)
        sysctlbyname("hw.model", &value, &size, nil, 0)
        let bytes = value.prefix { $0 != 0 }.map { UInt8(bitPattern: $0) }
        return String(decoding: bytes, as: UTF8.self)
    }
}

@MainActor
private final class PeerSession {
    let id = UUID()
    let connection: NWConnection
    var decoder = WireFrameDecoder()
    var peerFingerprint: String?
    var peerHello: WireHello?
    var deviceID: String?
    var pairCode: String?
    var localConfirmed = false
    var remoteConfirmed = false
    var connected = false
    var closing = false

    init(connection: NWConnection) {
        self.connection = connection
    }
}
