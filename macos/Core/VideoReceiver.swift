import AVFoundation
import CoreMedia
import Foundation
import os

/// P07 — play a generated H.264 Annex-B stream through AVSampleBufferDisplayLayer.
///
/// The receiver deliberately uses the per-frame `AVSampleBufferVideoRenderer.enqueueSampleBuffer`
/// path. On macOS 27 that method is deprecated in favour of the new sample-buffer receiver API;
/// the build warning is part of the P07 deprecation audit rather than something suppressed.
@MainActor
public final class VideoReceiverModel: ObservableObject {

    public enum State: String {
        case idle
        case loading
        case loaded
        case playing
        case paused
        case failed
    }

    private let log = Logger(subsystem: "com.droidbridge.macos", category: "P07Video")
    private let frameDurationUs: Int64 = 16_666  // 60 fps fixture

    public let layer = AVSampleBufferDisplayLayer()
    private var renderer: AVSampleBufferVideoRenderer { layer.sampleBufferRenderer }

    private var accessUnits: [H264AccessUnit] = []
    private var format: CMVideoFormatDescription?
    private var nextIndex = 0
    private var timer: Timer?
    private var observer: NSObjectProtocol?
    private var enqueued = 0

    @Published public private(set) var state: State = .idle
    @Published public private(set) var lastEvent = "idle"
    @Published public private(set) var events: [String] = []
    @Published public private(set) var frameCount = 0
    @Published public private(set) var readyForDisplay = false

    public init(autoPlay: Bool = false, streamPath: String? = nil) {
        layer.preventsCapture = true
        layer.videoGravity = .resizeAspect
        observer = NotificationCenter.default.addObserver(
            forName: NSNotification.Name.AVSampleBufferDisplayLayerReadyForDisplayDidChange,
            object: layer,
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor in
                guard let self else { return }
                self.readyForDisplay = self.layer.isReadyForDisplay
                self.emit("READY_FOR_DISPLAY \(self.readyForDisplay)")
            }
        }

        if let path = streamPath {
            load(path: path, autoPlay: autoPlay)
        } else if let url = Bundle.main.url(forResource: "p07-test", withExtension: "h264") {
            load(path: url.path, autoPlay: autoPlay)
        } else {
            emit("NO_FIXTURE: pass --p07-stream <path> or bundle p07-test.h264")
        }

        if CommandLine.arguments.contains("--p07-recover") {
            DispatchQueue.main.asyncAfter(deadline: .now() + 2.0) { [weak self] in
                self?.recover()
            }
        }
    }

    deinit {
        if let observer { NotificationCenter.default.removeObserver(observer) }
    }

    public func load(path: String, autoPlay: Bool = false) {
        pause()
        state = .loading
        do {
            let data = try Data(contentsOf: URL(fileURLWithPath: path))
            let nals = try H264AnnexB.parse(data)
            let sets = try H264AnnexB.parameterSets(from: nals)
            let format = try H264AnnexB.makeFormatDescription(sps: sets.sps, pps: sets.pps)
            let units = H264AnnexB.accessUnits(from: nals, frameDurationUs: frameDurationUs)
            try H264AnnexB.validateStrictlyIncreasing(units.map(\.ptsUs))
            self.format = format
            self.accessUnits = units
            nextIndex = 0
            frameCount = units.count
            state = .loaded
            emit(
                "LOADED path=\(path) bytes=\(data.count) nals=\(nals.count) " +
                    "units=\(units.count) sps=\(sets.sps.count) pps=\(sets.pps.count) " +
                    "preventsCapture=\(layer.preventsCapture) status=\(statusName())"
            )
            if autoPlay { play() }
        } catch {
            state = .failed
            emit("LOAD_FAILED \(error)")
        }
    }

    public func play() {
        guard !accessUnits.isEmpty else {
            emit("PLAY ignored: no stream")
            return
        }
        if nextIndex >= accessUnits.count {
            nextIndex = 0
            renderer.flush()
            emit("RESTART at keyframe after flush")
        }
        state = .playing
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: 1.0 / 60.0, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.enqueueNext() }
        }
        RunLoop.main.add(timer!, forMode: .common)
        emit("PLAY frames=\(accessUnits.count) status=\(statusName()) ready=\(renderer.isReadyForMoreMediaData)")
    }

    public func pause() {
        timer?.invalidate()
        timer = nil
        if state == .playing { state = .paused }
        emit("PAUSE enqueued=\(enqueued) next=\(nextIndex)")
    }

    /// Flushes the decoder and restarts at the first keyframe. P07's recovery path.
    public func recover() {
        pause()
        renderer.flush()
        if #available(macOS 15.4, *) {
            // no-op; the ready-for-display notification will refresh
        }
        nextIndex = 0
        emit("RECOVER flush status=\(statusName()) requiresFlush=\(renderer.requiresFlushToResumeDecoding)")
        play()
    }

    private func enqueueNext() {
        guard state == .playing else { return }
        guard nextIndex < accessUnits.count else {
            pause()
            emit("END frames=\(enqueued) ready=\(readyForDisplay)")
            return
        }
        guard renderer.isReadyForMoreMediaData else {
            // Backpressure: do not build a queue that grows without bound. Leave the index alone
            // and try again on the next tick.
            emit("BACKPRESSURE ready=\(renderer.isReadyForMoreMediaData) next=\(nextIndex)")
            return
        }
        guard let format else {
            state = .failed
            emit("FAILED no format")
            return
        }
        do {
            let unit = accessUnits[nextIndex]
            let sample = try H264AnnexB.makeSampleBuffer(
                for: unit, format: format, frameDurationUs: frameDurationUs
            )
            renderer.enqueue(sample)
            nextIndex += 1
            enqueued += 1
            if enqueued % 30 == 0 {
                emit("FRAMES enqueued=\(enqueued) next=\(nextIndex) status=\(statusName())")
            }
            if renderer.status == .failed {
                state = .failed
                emit("FAILED status=failed error=\(String(describing: renderer.error)) requiresFlush=\(renderer.requiresFlushToResumeDecoding)")
                pause()
            }
        } catch {
            state = .failed
            emit("SAMPLE_FAILED \(error)")
            pause()
        }
    }

    private func statusName() -> String {
        switch renderer.status {
        case .unknown: return "unknown"
        case .rendering: return "rendering"
        case .failed: return "failed"
        @unknown default: return "unknown(\(renderer.status.rawValue))"
        }
    }

    private func emit(_ line: String) {
        lastEvent = line
        events.append(line)
        if events.count > 200 { events.removeFirst(events.count - 200) }
        log.info("\(line, privacy: .public)")
        NSLog("p07: %@", line)
        FileHandle.standardError.write(Data("[p07] \(line)\n".utf8))
    }
}
