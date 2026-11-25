import AVFoundation
import CoreImage
import CoreMedia
import ImageIO
import Foundation
import os

/// P08 — receive a live H.264 stream over the authenticated session and render it.
///
/// This is the runtime counterpart to [VideoReceiverModel] (P07's fixture player): frames arrive as
/// AVCC access units in `video` messages, with parameter sets announced in `mirror.format`. The
/// display path is the same — format description from bare SPS/PPS, AVCC sample buffers into
/// `AVSampleBufferDisplayLayer` — but timing/backpressure are driven by the sender instead of a
/// local timer.
///
/// **Prototype scope:** this receiver exists to produce P08 evidence (first frame, continuity,
/// keyframe recovery). It is not a polished player and does not attempt rate adaptation.
@MainActor
public final class LiveMirrorReceiver: ObservableObject {

    public enum State: String {
        case idle
        case listening
        case awaitingConfirmation
        case connected
        case streaming
        case stopped
        case failed
    }

    private let log = Logger(subsystem: "com.droidbridge.macos", category: "P08Mirror")

    public let layer = AVSampleBufferDisplayLayer()
    private var renderer: AVSampleBufferVideoRenderer { layer.sampleBufferRenderer }

    private var listener: SecureListener?
    private var pinStore: PeerPinStore?
    private var pinnedPeerId: String?

    /// One identity for the life of the app run.
    ///
    /// `startListening` used to generate a fresh identity every time Listen was pressed, which
    /// changed this Mac's pin under the phone's feet: every restart of the session looked like a
    /// peer identity change and forced Forget stored pin + a manual re-pair. Reconnect recovery
    /// therefore could not be exercised at all. The identity is now created once and reused; it is
    /// still per-process, because persistent identity storage is P15's scope (ADR-025).
    private var identity: MacIdentity?
    private var format: CMVideoFormatDescription?

    /// P10 evidence sink. `AVSampleBufferDisplayLayer` decodes internally, so the mirrored pixels are
    /// not reachable for measurement; when `P08_DUMP_DIR` is set this second VTDecompressionSession
    /// decodes the same samples and reports mean luma plus writes a PNG. That turns "is protected
    /// content withheld?" into a number instead of an opinion. Off unless the env var is set.
    private var dumpSession: VTDecompressionSession?
    private var dumpFormat: CMFormatDescription?
    private var dumpDir: URL?
    private var dumpCount = 0
    private let dumpContext = CIContext(options: nil)
    private var dumpLumaSum: Double = 0
    private var dumpLumaCount = 0
    private var dumpLumaMin: Double = 255
    private var dumpLumaMax: Double = 0
    private var formatDescription: MirrorFormatPayload?
    private var streamValidator = MirrorStreamValidator()
    private var firstFrameAt: Date?
    private var streamStartedAt: Date?

    // Live mirroring: sender encoder PTS are in an arbitrary clock domain. Rebase onto a local
    // control timebase so frames present continuously instead of stalling in a different epoch.
    private var timebase: CMTimebase?
    private var ptsBaseUs: Int64?
    private var ptsOffset: CMTime = .zero

    /// Scripted fault hook for the P08 keyframe-recovery case (`--p08-fault-after <seconds>`).
    /// When set, one deliberately corrupt sample is enqueued after that many seconds of streaming.
    /// A random payload does not always flip the renderer to `.failed`, so if it stays healthy a
    /// second later the same recovery branch is driven explicitly (flush + keyframe request) and
    /// the event log records which of the two ran. Test affordance only; inert without the flag.
    public var scriptedFaultAfter: TimeInterval?
    private var scriptedFaultFired = false

    /// Test affordance for P10 evidence: `preventsCapture` is on by default, which also makes the
    /// rendered video invisible to `screencapture`, so a recorded observation of protected content
    /// is impossible. Only enabled by `--p08-allow-capture` on evidence runs.
    public func allowScreenCapture() {
        layer.preventsCapture = false
        emit("CAPTURE_ALLOWED preventsCapture=false")
    }

    @Published public private(set) var state: State = .idle
    @Published public private(set) var lastEvent = "idle"
    @Published public private(set) var events: [String] = []
    /// UI-facing counters publish at a bounded cadence; totals remain exact for evidence.
    @Published public private(set) var framesReceived: Int = 0
    @Published public private(set) var framesEnqueued: Int = 0
    @Published public private(set) var framesDropped: Int = 0
    @Published public private(set) var keyframeRequests: Int = 0
    private var totalFramesReceived = 0
    private var totalFramesEnqueued = 0
    private var totalFramesDropped = 0
    private var lastPublishedFrameCount = 0
    @Published public private(set) var formatChanges: Int = 0
    /// Phase 3 clipboard: shares this session, its own rules live in ClipboardBridgeMac.
    public let clipboard = ClipboardBridgeMac()

    /// Mirrors `clipboard.status` so SwiftUI updates when the bridge reports a change.
    @Published public private(set) var clipboardStatus = "idle"

    @Published public private(set) var pairingCode: String?
    @Published public private(set) var peerPin: String?

    public init() {
        layer.preventsCapture = true
        layer.videoGravity = .resizeAspect
        configureTimebase()
    }

    public func startListening(
        peerId: String = "android-p08",
        serviceName: String = "DroidBridge-P08-Mac",
        resetPin: Bool = false,
        port: UInt16 = 0
    ) {
        stop()
        do {
            let identity: MacIdentity
            if let existing = self.identity {
                identity = existing
            } else {
                let fresh = try MacIdentity.generate()
                self.identity = fresh
                identity = fresh
            }
            let store = PeerPinStore(service: "com.droidbridge.p08.peer-pins")
            pinStore = store
            pinnedPeerId = peerId
            if resetPin { store.forgetAll() }
            let secure = SecureListener(
                identity: identity,
                pinStore: store,
                peerId: peerId,
                trustMode: .storedPin,
                idleTimeout: idleTimeoutSeconds,
                pairingTimeout: 300
            )
            clipboard.ownOriginId = identity.pin
            clipboard.send = { [weak self] type, payload in
                guard let self, let live = self.listener else { return }
                do {
                    try live.sendControl(type: type, payload: payload)
                } catch {
                    self.emit("CLIPBOARD_SEND_FAILED \(type) \(error)")
                }
            }
            clipboard.onStatus = { [weak self] line in
                Task { @MainActor in
                    self?.clipboardStatus = line
                    self?.emit("CLIPBOARD \(line)")
                }
            }
            wire(secure)
            // A fixed port lets a harness restart the listener on the same address, which is the only
            // way to reproduce "the peer's session dropped and came back" without a fresh discovery
            // round: reconnect uses the cached host:port.
            try secure.start(serviceName: serviceName, port: port)
            listener = secure
            state = .listening
            emit("LISTENING port=\(secure.port) peer-id=\(peerId) identity-pin=\(identity.pin)")
        } catch {
            state = .failed
            emit("LISTEN_FAILED \(error)")
        }
    }

    /// When set, an unauthenticated peer whose pin matches is confirmed without a click.
    public var expectedPeerPin: String?

    /// When set, an unauthenticated peer is confirmed without a click **only if** the short
    /// authentication code derived from this handshake equals this value.
    ///
    /// This exists because unattended runs have no way to click, and confirming on an operator's
    /// behalf without checking is exactly what the pairing design forbids. The operator reads the
    /// code on both screens first, then passes it here; a different code is never confirmed.
    public var expectedPairingCode: String?

    /// Seconds of silence before the listener drops the session. Long runs that pause between
    /// phases (consent prompts, screen-off checks) need a wider window than the 120 s default.
    public var idleTimeoutSeconds: TimeInterval = 120

    public func confirmPairing() {
        guard let listener else { return }
        do {
            try listener.confirmPairing()
            state = .connected
            emit("PAIRING_CONFIRMED")
        } catch {
            emit("PAIRING_CONFIRM_FAILED \(error)")
        }
    }

    public func rejectPairing() {
        listener?.rejectPairing()
        emit("PAIRING_REJECTED")
    }

    public func requestKeyframe() {
        guard let listener else { return }
        do {
            try listener.sendControl(type: .mirrorKeyframeRequest)
            keyframeRequests += 1
            emit("KEYFRAME_REQUEST_SENT count=\(keyframeRequests)")
        } catch {
            emit("KEYFRAME_REQUEST_FAILED \(error)")
        }
    }

    public func stop() {
        clipboard.stopPolling()
        listener?.stop()
        listener = nil
        publishFrameCounters(force: true)
        state = .stopped
        emit("STOPPED framesReceived=\(totalFramesReceived) framesEnqueued=\(totalFramesEnqueued) dropped=\(totalFramesDropped)")
    }

    /// Pairing revocation: clears the stored phone pin so the next connection is a first pairing
    /// again — the code must be compared and confirmed by a human. Kept next to the store that
    /// `startListening` created, so revocation cannot miss the pin that the live listener uses.
    public func forgetPeerPin() {
        guard let pinStore, let pinnedPeerId else {
            emit("FORGET skipped: no listener has stored a pin in this run")
            return
        }
        pinStore.forgetAll()
        emit("FORGET done peer-id=\(pinnedPeerId)")
    }

    private func wire(_ secure: SecureListener) {
        secure.onEvent = { [weak self] event in
            Task { @MainActor in self?.emit("EVENT \(event)") }
        }
        secure.onReady = { [weak self] pin, code, authenticated in
            Task { @MainActor in
                guard let self else { return }
                self.peerPin = pin
                self.pairingCode = code
                self.state = authenticated ? .connected : .awaitingConfirmation
                // Clipboard sync begins when the session is trusted, not before: an unconfirmed peer
                // must not be able to make the Mac pasteboard change.
                if authenticated { self.clipboard.startPolling() }
                self.emit("READY peer-pin=\(pin) authenticated=\(authenticated) code=\(ShortAuthenticationCode.formatForDisplay(code))")
                // Harness: confirm only when the peer pin is the one the run expected. The operator
                // still compares the displayed code on both screens; this only removes the click.
                if !authenticated, let expected = self.expectedPeerPin, expected == pin {
                    self.emit("AUTO_CONFIRM peer-pin matches the expected pin")
                    self.confirmPairing()
                } else if !authenticated, self.expectedPeerPin != nil {
                    self.emit("AUTO_CONFIRM_SKIPPED peer pin does not match the expected pin")
                }
                // Code-verified confirmation: only the exact code the operator matched is accepted.
                if !authenticated, let expectedCode = self.expectedPairingCode {
                    let displayed = ShortAuthenticationCode.formatForDisplay(code)
                    if displayed == expectedCode || code == expectedCode {
                        self.emit("AUTO_CONFIRM_CODE_VERIFIED code=\(displayed)")
                        self.confirmPairing()
                    } else {
                        self.emit("AUTO_CONFIRM_CODE_SKIPPED displayed=\(displayed) expected=\(expectedCode)")
                    }
                }
            }
        }
        secure.onAuthenticated = { [weak self] pin in
            Task { @MainActor in
                guard let self else { return }
                self.peerPin = pin
                self.state = .connected
                self.emit("AUTHENTICATED peer-pin=\(pin)")
            }
        }
        secure.onClosed = { [weak self] reason in
            Task { @MainActor in
                guard let self else { return }
                self.state = .stopped
                self.emit("CLOSED \(reason)")
            }
        }
        secure.onFrame = { [weak self] message in
            let isMedia = message.type.isMedia
            Task { @MainActor in
                guard let self else { return }
                self.handle(message)
                if isMedia {
                    self.pendingMedia &+= 1
                    if self.pendingMedia % 30 == 0 { self.emit("MAIN ran=\(self.pendingMedia)") }
                }
            }
        }
    }

    private var handledCount = 0
    private var pendingMedia = 0

    private func handle(_ message: ControlMessage) {
        handledCount += 1
        if dumpDir != nil, handledCount % 30 == 0 { emit("HANDLE n=\(handledCount) type=\(message.type)") }
        switch message.type {
        case .mirrorStarted:
            state = .streaming
            streamStartedAt = Date()
            streamValidator.streamStarted()
            resetPtsAnchor()
            emit("MIRROR_STARTED")
        case .mirrorFormat:
            do {
                let fmt = try MirrorFormatPayload.parse(message.payload)
                formatDescription = fmt
                format = try H264AnnexB.makeFormatDescription(
                    sps: [UInt8](fmt.sps),
                    pps: [UInt8](fmt.pps),
                    nalLengthSize: fmt.nalLengthSize
                )
                formatChanges += 1
                recordParameterSets(sps: [UInt8](fmt.sps), pps: [UInt8](fmt.pps))
                Self.recordNALLengthSize = Int(fmt.nalLengthSize)
                renderer.flush()
                streamValidator.formatChanged()
                resetPtsAnchor()
                emit("MIRROR_FORMAT \(fmt.width)x\(fmt.height) rotation=\(fmt.rotation) nalLengthSize=\(fmt.nalLengthSize) sps=\(fmt.sps.count) pps=\(fmt.pps.count)")
            } catch {
                state = .failed
                emit("MIRROR_FORMAT_FAILED \(error)")
            }
        case .mirrorStop:
            let reason = String(data: message.payload, encoding: .utf8) ?? "unknown"
            state = .connected
            emit("MIRROR_STOP reason=\(reason)")
        case .video:
            handleVideo(message.payload)
        case .ping:
            do {
                try listener?.sendControl(type: .pong, payload: message.payload)
            } catch {
                emit("PONG_FAILED \(error)")
            }
        case .clipboardOffer, .clipboardAccept, .clipboardAck, .clipboardReject, .clipboardPause:
            clipboard.handle(type: message.type, payload: message.payload)
        case .clipboardText:
            clipboard.handleTextBody(message.payload)
        default:
            break
        }
    }

    private func handleVideo(_ payload: Data) {
        totalFramesReceived += 1
        publishFrameCounters()
        guard let format else {
            totalFramesDropped += 1
            publishFrameCounters()
            if totalFramesDropped <= 3 || totalFramesDropped % 60 == 0 {
                requestKeyframeIfNeeded(reason: "no-format")
            }
            return
        }

        let video: VideoPayload
        do {
            // Validate every received frame even while the renderer is backpressured or failed.
            // Otherwise malformed media could evade protocol checks simply because it was dropped.
            video = try VideoPayload.parse(payload)
            recordIfConfigured(video, ptsUs: UInt64(max(0, video.ptsUs)))
            // Evidence first: a frame the renderer refuses is still a frame that arrived, and the
            // measurement must not inherit the renderer's recovery state.
            let evidencePresentation = presentationTime(for: video.ptsUs)
            let evidenceSample = try makeSampleBuffer(
                avcc: video.avcc, format: format, presentation: evidencePresentation
            )
            dumpIfConfigured(evidenceSample)
            switch try streamValidator.validate(ptsUs: video.ptsUs, isIDR: video.isKeyframe) {
            case .enqueue:
                break
            case .waitForRecoveryIDR:
                totalFramesDropped += 1
                publishFrameCounters()
                // The validator waits for an IDR nobody is asking for; without this the stream
                // stalls for the rest of the session.
                requestKeyframeIfNeeded(reason: "validator-recovery")
                return
            }
        } catch {
            totalFramesDropped += 1
            publishFrameCounters()
            let reason = "invalid video stream: \(error)"
            emit("VIDEO_REJECT \(reason)")
            listener?.fail(reason: reason)
            return
        }

        if renderer.status == .failed || renderer.requiresFlushToResumeDecoding {
            totalFramesDropped += 1
            publishFrameCounters()
            renderer.flush()
            streamValidator.decoderFlushed()
            resetPtsAnchor()
            requestKeyframeIfNeeded(reason: "renderer-failed")
            return
        }
        do {
            let presentation = presentationTime(for: video.ptsUs)
            let sample = try makeSampleBuffer(avcc: video.avcc, format: format, presentation: presentation)
            guard renderer.isReadyForMoreMediaData else {
                totalFramesDropped += 1
                publishFrameCounters()
                if totalFramesDropped % 30 == 0 { emit("BACKPRESSURE dropped=\(totalFramesDropped)") }
                return
            }
            renderer.enqueue(sample)
            totalFramesEnqueued += 1
            publishFrameCounters()
            if firstFrameAt == nil {
                firstFrameAt = Date()
                if let started = streamStartedAt {
                    let ms = Date().timeIntervalSince(started) * 1000
                    emit("FIRST_FRAME latencyMs=\(String(format: "%.1f", ms)) ptsUs=\(video.ptsUs)")
                } else {
                    emit("FIRST_FRAME ptsUs=\(video.ptsUs)")
                }
            }
            if totalFramesEnqueued % 60 == 0 {
                emit("FRAMES received=\(totalFramesReceived) enqueued=\(totalFramesEnqueued) dropped=\(totalFramesDropped) ptsUs=\(video.ptsUs) status=\(statusName())")
            }
            maybeInjectScriptedFault()
        } catch {
            // Sample-buffer construction is local renderer failure, not malformed wire protocol.
            // Drop this frame and request a fresh IDR; don't misclassify a platform error as peer
            // misconduct or close the authenticated session.
            totalFramesDropped += 1
            publishFrameCounters()
            emit("SAMPLE_BUFFER_FAILED \(error) received=\(totalFramesReceived) dropped=\(totalFramesDropped)")
            renderer.flush()
            streamValidator.decoderFlushed()
            resetPtsAnchor()
            requestKeyframeIfNeeded(reason: "sample-buffer-failed")
        }
    }

    // MARK: - P10 evidence dump

    /// Writes every received AVCC frame to a .h264 file plus a CSV timeline, so the measurement can
    /// run offline after the session instead of competing with the live renderer for the decoder.
    private nonisolated func recordParameterSets(sps: [UInt8], pps: [UInt8]) {
        Self.recordSPS = sps
        Self.recordPPS = pps
        Self.recordWroteParameters = false
    }

    private nonisolated func recordIfConfigured(_ video: VideoPayload, ptsUs: UInt64) {
        guard let dir = ProcessInfo.processInfo.environment["P08_RECORD_DIR"] else { return }
        recordQueue.async {
            let fm = FileManager.default
            if Self.recordFile == nil {
                try? fm.createDirectory(atPath: dir, withIntermediateDirectories: true)
                Self.recordFile = FileHandle(forWritingAtPath: dir + "/stream.h264")
                if Self.recordFile == nil {
                    fm.createFile(atPath: dir + "/stream.h264", contents: nil)
                    Self.recordFile = FileHandle(forWritingAtPath: dir + "/stream.h264")
                }
                Self.recordTimeline = FileHandle(forWritingAtPath: dir + "/timeline.csv")
                if Self.recordTimeline == nil {
                    fm.createFile(atPath: dir + "/timeline.csv", contents: nil)
                    Self.recordTimeline = FileHandle(forWritingAtPath: dir + "/timeline.csv")
                }
                Self.recordTimeline?.write(Data("index,ptsUs,bytes,wall\n".utf8))
            }
            var frame = Data()
            if !Self.recordWroteParameters {
                for ps in [Self.recordSPS, Self.recordPPS] {
                    guard let ps, !ps.isEmpty else { continue }
                    frame.append(contentsOf: [0, 0, 0, 1])
                    frame.append(contentsOf: ps)
                }
                Self.recordWroteParameters = true
            }
            // video.avcc is length-prefixed NAL units; a start code per NAL is what a decoder
            // reading the file back expects.
            frame.append(Self.annexB(fromLengthPrefixed: video.avcc, nalLengthSize: Self.recordNALLengthSize))
            Self.recordFile?.write(frame)
            Self.recordFrameCount &+= 1
            let line = "\(Self.recordFrameCount),\(ptsUs),\(video.avcc.count),\(Date().timeIntervalSince1970)\n"
            Self.recordTimeline?.write(Data(line.utf8))
        }
    }

    private func dumpIfConfigured(_ sample: CMSampleBuffer) {
        guard ProcessInfo.processInfo.environment["P08_DUMP_DIR"] != nil else { return }
        // VTDecompressionSessionDecodeFrame blocks in a semaphore wait on this platform, so
        // decoding inline stalls whichever thread called it - on the main thread that stops every
        // later frame handler and the sink goes silent after roughly one decoder queue depth.
        evidenceQueue.async { [weak self] in
            guard let self else { return }
            guard !Self.evidenceBusy else { return }
            Self.evidenceBusy = true
            // Watchdog: a decoder that never returns must not silence the sink for the session.
            let token = Self.evidenceToken &+ 1
            Self.evidenceToken = token
            DispatchQueue.global().asyncAfter(deadline: .now() + 5) {
                if Self.evidenceToken == token, Self.evidenceBusy {
                    Self.evidenceBusy = false
                    self.emit("EVIDENCE stale-lock cleared")
                }
            }
            defer { Self.evidenceBusy = false }
            self.dumpOnEvidenceQueue(sample)
        }
    }

    private func dumpOnEvidenceQueue(_ sample: CMSampleBuffer) {
        if dumpDir == nil {
            guard let path = ProcessInfo.processInfo.environment["P08_DUMP_DIR"] else { return }
            let dir = URL(fileURLWithPath: path, isDirectory: true)
            try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            dumpDir = dir
            emit("DUMP enabled dir=\(dir.path)")
        }
        guard let dir = dumpDir, let format else { return }

        if dumpSession == nil || dumpFormat != format {
            dumpSetups += 1
            setupDumpSession(format: format)
        }
        // The evidence session joins mid-GOP and cannot start until it sees an IDR, so ask the
        // sender for one; without this the sink stays silent for the whole session.
        if !dumpKeyframeRequested {
            dumpKeyframeRequested = true
            lastDumpKeyframeAt = Date()
            requestKeyframeIfNeeded(reason: "dump-session")
        }
        // The evidence decoder needs a periodic IDR to stay alive; if it has produced nothing for a
        // second while frames keep arriving, ask for one (bounded to once per two seconds).
        let now = Date()
        let silent = lastDumpImageAt.map { now.timeIntervalSince($0) > 1 } ?? true
        let canAsk = lastDumpKeyframeAt.map { now.timeIntervalSince($0) > 2 } ?? true
        if silent, canAsk {
            lastDumpKeyframeAt = now
            requestKeyframeIfNeeded(reason: "dump-stall")
        }
        guard let session = dumpSession else {
            emit("DUMP no-session call#\(dumpCount + 1) setups=\(dumpSetups)")
            return
        }
        dumpCount += 1
        var decodeStatus = VTDecompressionSessionDecodeFrame(
            session,
            sampleBuffer: sample,
            flags: [._EnableAsynchronousDecompression],
            infoFlagsOut: nil
        ) { [weak self] status, flags, imageBuffer, pts, duration in
            guard status == noErr, let imageBuffer = imageBuffer else {
                self?.emit("DUMP decode status=\(status) image=\(imageBuffer != nil)")
                return
            }
            self?.recordDumpedFrame(imageBuffer, dir: dir, flags: flags)
        }
        if dumpCount % 15 == 0 || decodeStatus != noErr {
            emit("DUMP submit n=\(dumpCount) status=\(decodeStatus)")
        }
    }

    private var dumpKeyframeRequested = false
    private var dumpSetups = 0
    private nonisolated let recordQueue = DispatchQueue(label: "com.droidbridge.p08.record")
    private nonisolated(unsafe) static var recordFile: FileHandle?
    private nonisolated(unsafe) static var recordTimeline: FileHandle?
    private nonisolated(unsafe) static var recordFrameCount: UInt64 = 0
    private nonisolated(unsafe) static var recordSPS: [UInt8]?
    private nonisolated(unsafe) static var recordPPS: [UInt8]?
    private nonisolated(unsafe) static var recordWroteParameters = false
    private nonisolated(unsafe) static var recordNALLengthSize = 4

    private nonisolated static func annexB(fromLengthPrefixed data: Data, nalLengthSize: Int) -> Data {
        var out = Data(); var i = data.startIndex
        let n = max(1, min(4, nalLengthSize))
        while i + n <= data.endIndex {
            var len = 0
            for k in 0..<n { len = (len << 8) | Int(data[data.index(i, offsetBy: k)]) }
            let start = data.index(i, offsetBy: n)
            guard len > 0, data.distance(from: start, to: data.endIndex) >= len else { break }
            out.append(contentsOf: [0, 0, 0, 1])
            out.append(data[start..<data.index(start, offsetBy: len)])
            i = data.index(start, offsetBy: len)
        }
        return out.isEmpty ? data : out
    }

    private nonisolated let evidenceQueue = DispatchQueue(
        label: "com.droidbridge.p08.evidence", qos: .utility
    )
    private nonisolated(unsafe) static var evidenceBusy = false
    private nonisolated(unsafe) static var evidenceToken = 0
    private var lastDumpImageAt: Date?
    private var lastDumpKeyframeAt: Date?

    private func setupDumpSession(format: CMFormatDescription) {
        dumpSession.map { VTDecompressionSessionInvalidate($0) }
        dumpFormat = format
        // Let VideoToolbox pick the decoder. Forcing the software path made
        // RemoteVideoDecoder_DecodeFrameWithOptions block indefinitely in a semaphore wait.
        let spec: [CFString: Any] = [:]
        let attrs: [CFString: Any] = [
            kCVPixelBufferPixelFormatTypeKey: Int(kCVPixelFormatType_32BGRA),
            // Without an IOSurface-backed pool the decompressor accepts frames and never calls
            // back, which reads as a silent measurement gap rather than an error.
            kCVPixelBufferIOSurfacePropertiesKey: [:] as CFDictionary,
        ]
        var session: VTDecompressionSession?
        let status = VTDecompressionSessionCreate(
            allocator: kCFAllocatorDefault,
            formatDescription: format,
            decoderSpecification: spec as CFDictionary,
            imageBufferAttributes: attrs as CFDictionary,
            outputCallback: nil,
            decompressionSessionOut: &session
        )
        dumpSession = status == noErr ? session : nil
        if status != noErr { emit("DUMP session failed status=\(status)") }
        else { emit("DUMP session created setups=\(dumpSetups)") }
    }

    private func recordDumpedFrame(_ buffer: CVPixelBuffer, dir: URL, flags: VTDecodeInfoFlags) {
        let ci = CIImage(cvPixelBuffer: buffer)
        let extent = CIVector(cgRect: ci.extent)
        guard let filter = CIFilter(name: "CIAreaAverage", parameters: [
            kCIInputImageKey: ci,
            kCIInputExtentKey: extent,
        ]), let output = filter.outputImage else { return }

        var pixel = [UInt8](repeating: 0, count: 4)
        dumpContext.render(
            output,
            toBitmap: &pixel,
            rowBytes: 4,
            bounds: CGRect(x: 0, y: 0, width: 1, height: 1),
            format: .RGBA8,
            colorSpace: CGColorSpaceCreateDeviceRGB()
        )
        let luma = 0.2126 * Double(pixel[0]) + 0.7152 * Double(pixel[1]) + 0.0722 * Double(pixel[2])
        lastDumpImageAt = Date()
        dumpLumaSum += luma
        dumpLumaCount += 1
        dumpLumaMin = min(dumpLumaMin, luma)
        dumpLumaMax = max(dumpLumaMax, luma)

        let index = dumpLumaCount
        // Per-frame so a measurement phase can be attributed by timestamp; cumulative stats stay
        // available for the whole session.
        // ~2 lines/second: os_log throttles per-frame info lines at stream rates, which silently
        // hides the measurement instead of reporting it.
        if index % 15 == 0 { emit(String(format: "LUMA f=%d l=%.1f", index, luma)) }
        if index % 30 == 0, let cg = dumpContext.createCGImage(ci, from: ci.extent) {
            let url = dir.appendingPathComponent(String(format: "dump-%05d.png", index))
            if let dest = CGImageDestinationCreateWithURL(url as CFURL, "public.png" as CFString, 1, nil) {
                CGImageDestinationAddImage(dest, cg, nil)
                CGImageDestinationFinalize(dest)
            }
        }
    }

    private func requestKeyframeIfNeeded(reason: String) {
        guard let listener, listener.state.allowsData else { return }
        keyframeRequests += 1
        do {
            try listener.sendControl(type: .mirrorKeyframeRequest)
            emit("KEYFRAME_REQUEST reason=\(reason) count=\(keyframeRequests)")
        } catch {
            emit("KEYFRAME_REQUEST_FAILED \(error)")
        }
    }

    /// Enqueue a single garbage sample once the configured delay has elapsed, which pushes the
    /// renderer into its failure state. The normal handleVideo path then flushes and asks the
    /// sender for a keyframe, so the recovery under test is the real one.
    private func maybeInjectScriptedFault() {
        guard let delay = scriptedFaultAfter, !scriptedFaultFired, let format else { return }
        guard let started = streamStartedAt ?? firstFrameAt else { return }
        guard Date().timeIntervalSince(started) >= delay else { return }
        scriptedFaultFired = true
        var garbage = Data(count: 4096)
        for index in 0..<garbage.count {
            garbage[index] = UInt8.random(in: 0...255)
        }
        do {
            let sample = try makeSampleBuffer(
                avcc: garbage,
                format: format,
                presentation: presentationTime(for: max(streamValidator.lastPTSUs ?? 0, 0))
            )
            renderer.enqueue(sample)
            emit("FAULT_INJECTED method=corrupt-sample bytes=\(garbage.count) " +
                 "afterSeconds=\(String(format: "%.1f", delay))")
        } catch {
            emit("FAULT_INJECTION_FAILED \(error)")
        }

        // A random payload does not reliably flip AVSampleBufferDisplayLayer to `.failed`, because
        // the decoder may discard it without reporting. If the renderer is still healthy shortly
        // after the corrupt sample, drive the same recovery branch explicitly so the flush and
        // keyframe request round trip is still measured. The event records which path ran.
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { [weak self] in
            guard let self else { return }
            if self.renderer.status == .failed || self.renderer.requiresFlushToResumeDecoding {
                self.emit("FAULT_CONFIRMED status=\(self.statusName())")
                return
            }
            self.emit("FAULT_FALLBACK scripted-flush status=\(self.statusName())")
            self.renderer.flush()
            self.streamValidator.decoderFlushed()
            self.resetPtsAnchor()
            self.requestKeyframeIfNeeded(reason: "scripted-fault")
        }
    }

    private func makeSampleBuffer(avcc: Data, format: CMVideoFormatDescription, presentation: CMTime) throws -> CMSampleBuffer {
        var blockBuffer: CMBlockBuffer?
        let blockStatus = CMBlockBufferCreateWithMemoryBlock(
            allocator: kCFAllocatorDefault,
            memoryBlock: nil,
            blockLength: avcc.count,
            blockAllocator: kCFAllocatorDefault,
            customBlockSource: nil,
            offsetToData: 0,
            dataLength: avcc.count,
            flags: 0,
            blockBufferOut: &blockBuffer
        )
        guard blockStatus == noErr, let blockBuffer else { throw H264Error.blockBuffer(blockStatus) }
        let copyStatus = avcc.withUnsafeBytes { raw in
            CMBlockBufferReplaceDataBytes(
                with: raw.baseAddress!,
                blockBuffer: blockBuffer,
                offsetIntoDestination: 0,
                dataLength: avcc.count
            )
        }
        guard copyStatus == noErr else { throw H264Error.blockBuffer(copyStatus) }

        // Duration unknown per-frame; use a nominal 30fps duration. PTS rebased to local timebase.
        var timing = CMSampleTimingInfo(
            duration: CMTime(value: 33333, timescale: 1_000_000),
            presentationTimeStamp: presentation,
            decodeTimeStamp: .invalid
        )
        var sampleSize = avcc.count
        var sampleBuffer: CMSampleBuffer?
        let sampleStatus = CMSampleBufferCreateReady(
            allocator: kCFAllocatorDefault,
            dataBuffer: blockBuffer,
            formatDescription: format,
            sampleCount: 1,
            sampleTimingEntryCount: 1,
            sampleTimingArray: &timing,
            sampleSizeEntryCount: 1,
            sampleSizeArray: &sampleSize,
            sampleBufferOut: &sampleBuffer
        )
        guard sampleStatus == noErr, let sampleBuffer else { throw H264Error.sampleBuffer(sampleStatus) }
        return sampleBuffer
    }

    private func statusName() -> String {
        switch renderer.status {
        case .unknown: return "unknown"
        case .rendering: return "rendering"
        case .failed: return "failed"
        @unknown default: return "unknown(\(renderer.status.rawValue))"
        }
    }

    private func configureTimebase() {
        var tb: CMTimebase?
        let status = CMTimebaseCreateWithSourceClock(
            allocator: kCFAllocatorDefault,
            sourceClock: CMClockGetHostTimeClock(),
            timebaseOut: &tb
        )
        guard status == noErr, let tb else {
            emit("TIMEBASE_CREATE_FAILED status=\(status)")
            return
        }
        CMTimebaseSetTime(tb, time: CMClockGetTime(CMClockGetHostTimeClock()))
        CMTimebaseSetRate(tb, rate: 1.0)
        timebase = tb
        layer.controlTimebase = tb
    }

    private func publishFrameCounters(force: Bool = false) {
        let receivedDelta = totalFramesReceived - lastPublishedFrameCount
        guard force || receivedDelta >= 30 else { return }
        framesReceived = totalFramesReceived
        framesEnqueued = totalFramesEnqueued
        framesDropped = totalFramesDropped
        lastPublishedFrameCount = totalFramesReceived
    }

    private func resetPtsAnchor() {
        ptsBaseUs = nil
        ptsOffset = .zero
    }

    private func presentationTime(for ptsUs: Int64) -> CMTime {
        guard let tb = timebase else {
            return CMTime(value: ptsUs, timescale: 1_000_000)
        }
        if ptsBaseUs == nil {
            ptsBaseUs = ptsUs
            ptsOffset = CMTimebaseGetTime(tb)
        }
        let deltaUs = ptsUs - (ptsBaseUs ?? ptsUs)
        let delta = CMTime(value: deltaUs, timescale: 1_000_000)
        return CMTimeAdd(ptsOffset, delta)
    }

    private func emit(_ line: String) {
        if !Thread.isMainThread {
            DispatchQueue.main.async { [weak self] in self?.emit(line) }
            return
        }
        lastEvent = line
        events.append(line)
        if events.count > 300 { events.removeFirst(events.count - 300) }
        log.info("\(line, privacy: .public)")
        NSLog("p08: %@", line)
        FileHandle.standardError.write(Data("[p08] \(line)\n".utf8))
    }
}
