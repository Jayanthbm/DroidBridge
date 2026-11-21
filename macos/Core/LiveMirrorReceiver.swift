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
        resetPin: Bool = false
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
            if resetPin { store.forget(peerId) }
            let secure = SecureListener(
                identity: identity,
                pinStore: store,
                peerId: peerId,
                trustMode: .storedPin,
                idleTimeout: idleTimeoutSeconds,
                pairingTimeout: 300
            )
            wire(secure)
            try secure.start(serviceName: serviceName)
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
        listener?.stop()
        listener = nil
        publishFrameCounters(force: true)
        state = .stopped
        emit("STOPPED framesReceived=\(totalFramesReceived) framesEnqueued=\(totalFramesEnqueued) dropped=\(totalFramesDropped)")
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
                self.emit("READY peer-pin=\(pin) authenticated=\(authenticated) code=\(ShortAuthenticationCode.formatForDisplay(code))")
                // Harness: confirm only when the peer pin is the one the run expected. The operator
                // still compares the displayed code on both screens; this only removes the click.
                if !authenticated, let expected = self.expectedPeerPin, expected == pin {
                    self.emit("AUTO_CONFIRM peer-pin matches the expected pin")
                    self.confirmPairing()
                } else if !authenticated, self.expectedPeerPin != nil {
                    self.emit("AUTO_CONFIRM_SKIPPED peer pin does not match the expected pin")
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
            Task { @MainActor in self?.handle(message) }
        }
    }

    private func handle(_ message: ControlMessage) {
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
            switch try streamValidator.validate(ptsUs: video.ptsUs, isIDR: video.isKeyframe) {
            case .enqueue:
                break
            case .waitForRecoveryIDR:
                totalFramesDropped += 1
                publishFrameCounters()
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
        guard renderer.isReadyForMoreMediaData else {
            totalFramesDropped += 1
            publishFrameCounters()
            if totalFramesDropped % 30 == 0 { emit("BACKPRESSURE dropped=\(totalFramesDropped)") }
            return
        }

        do {
            let presentation = presentationTime(for: video.ptsUs)
            let sample = try makeSampleBuffer(avcc: video.avcc, format: format, presentation: presentation)
            renderer.enqueue(sample)
            totalFramesEnqueued += 1
            publishFrameCounters()
            dumpIfConfigured(sample)
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

    private func dumpIfConfigured(_ sample: CMSampleBuffer) {
        if dumpDir == nil {
            guard let path = ProcessInfo.processInfo.environment["P08_DUMP_DIR"] else { return }
            let dir = URL(fileURLWithPath: path, isDirectory: true)
            try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            dumpDir = dir
            emit("DUMP enabled dir=\(dir.path)")
        }
        guard let dir = dumpDir, let format else { return }

        if dumpSession == nil || dumpFormat != format {
            setupDumpSession(format: format)
        }
        // The evidence session joins mid-GOP and cannot start until it sees an IDR, so ask the
        // sender for one; without this the sink stays silent for the whole session.
        if !dumpKeyframeRequested {
            dumpKeyframeRequested = true
            requestKeyframeIfNeeded(reason: "dump-session")
        }
        guard let session = dumpSession else { return }
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
        if decodeStatus != noErr, dumpCount % 30 == 1 {
            emit("DUMP submit failed status=\(decodeStatus) n=\(dumpCount)")
        }
    }

    private var dumpKeyframeRequested = false

    private func setupDumpSession(format: CMFormatDescription) {
        dumpSession.map { VTDecompressionSessionInvalidate($0) }
        dumpFormat = format
        let spec: [CFString: Any] = [
            kVTVideoDecoderSpecification_EnableHardwareAcceleratedVideoDecoder: false as CFBoolean,
        ]
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
        else { emit("DUMP session created") }
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
        dumpLumaSum += luma
        dumpLumaCount += 1
        dumpLumaMin = min(dumpLumaMin, luma)
        dumpLumaMax = max(dumpLumaMax, luma)

        let index = dumpLumaCount
        // Per-frame so a measurement phase can be attributed by timestamp; cumulative stats stay
        // available for the whole session.
        emit(String(format: "LUMA f=%d l=%.1f", index, luma))
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
        lastEvent = line
        events.append(line)
        if events.count > 300 { events.removeFirst(events.count - 300) }
        log.info("\(line, privacy: .public)")
        NSLog("p08: %@", line)
        FileHandle.standardError.write(Data("[p08] \(line)\n".utf8))
    }
}
