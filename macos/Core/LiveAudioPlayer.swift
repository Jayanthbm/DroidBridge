import AVFoundation
import AudioToolbox
import Foundation
import os

/// Decodes AAC-LC access units from the phone and plays them through the Mac's output device.
///
/// ```
/// AUDIO payloads → AVAudioConverter (AAC-LC → Float32) → AVAudioPlayerNode → main mixer
/// ```
///
/// ## Why a converter and not AVAudioFile
///
/// The stream has no container and no timestamped file: each `AUDIO` message is one raw AAC access
/// unit and the AudioSpecificConfig arrives separately as `audio.format`. A converter takes exactly
/// that pair — compressed packets plus a magic cookie — so no temporary file or ADTS framing is
/// invented just to feed a higher-level player. The raw `AudioConverter` API was tried first and
/// could not be streamed one packet per call; see [configure] for the measured failure.
///
/// ## Why the amplitude is measured
///
/// A refused capture on the phone is silent, not absent (ADR-045 §4): frames keep arriving and every
/// sample is zero. [rms] is computed from the decoded PCM, so "audio is playing" means the Mac
/// decoded non-zero signal — the only claim that is actually evidence.
///
/// ## Latency, and what is deliberately not done
///
/// Buffers are scheduled in arrival order with no re-timing. The phone captures in real time and
/// AAC frames are 1024 samples (~23 ms), so ordering is preserved; what is not corrected is the
/// offset between the audio the Mac hears and the frame on screen. That offset is measured in the
/// P12 evidence rather than silently "fixed" with a guessed delay, because a guessed delay is
/// exactly the kind of unverified correction that makes a later sync bug unattributable.
@MainActor
public final class LiveAudioPlayer: ObservableObject {

    public enum State: String {
        case idle
        case ready
        case playing
        case failed
    }

    private let log = Logger(subsystem: "com.droidbridge.macos", category: "P08Audio")

    @Published public private(set) var state: State = .idle
    @Published public private(set) var statusText = "audio off"
    @Published public private(set) var frames: Int = 0
    @Published public private(set) var bytes: Int = 0
    @Published public private(set) var dropped: Int = 0
    /// Most recent one-second RMS of the decoded signal, in 16-bit sample units.
    @Published public private(set) var rms: Double = 0
    /// True when the last completed window carried no signal above the dither floor.
    @Published public private(set) var silent: Bool = false

    @Published public var muted: Bool = false {
        didSet { player.volume = muted ? 0 : 1 }
    }

    private var engine: AVAudioEngine?
    private let player = AVAudioPlayerNode()
    private var converter: AVAudioConverter?
    private var inputFormat: AVAudioFormat?
    private var format: AudioFormatPayload?
    private var pcmFormat: AVAudioFormat?
    private var scheduledFrames: AVAudioFramePosition = 0

    private var windowStart = Date()
    private var windowSquares = 0.0
    private var windowSamples = 0

    /// Harness-only evidence sink (`P08_AUDIO_TIMELINE_DIR`): one row per decoded access unit, with
    /// the host wall clock at which it was scheduled. Paired with the video recorder's timeline it
    /// gives the A/V offset the P12 evidence needs, without trusting either side's PTS domain.
    private var timelineFile: FileHandle?
    private var timelineIndex = 0

    public init() {}

    /// Builds the decoder for a newly announced stream. Returns false when the format is unusable.
    @discardableResult
    public func configure(_ payload: AudioFormatPayload) -> Bool {
        stop()
        format = payload
        frames = 0
        bytes = 0
        dropped = 0
        rms = 0
        silent = false
        scheduledFrames = 0

        guard let pcm = AVAudioFormat(
            commonFormat: .pcmFormatFloat32,
            sampleRate: Double(payload.sampleRate),
            channels: AVAudioChannelCount(payload.channels),
            interleaved: false
        ) else {
            fail("AVAudioFormat refused \(payload.sampleRate)Hz \(payload.channels)ch")
            return false
        }
        pcmFormat = pcm

        // The AudioSpecificConfig is the decoder configuration, so the format description carries
        // it as the magic cookie and AVAudioConverter is built from that pair. AudioToolbox's raw
        // AudioConverter was the first attempt and could not be driven one access unit at a time:
        // every call after the first reported OSStatus 0 with zero output packets, because the
        // end-of-input signal each call flushed the decoder's priming buffer (2026-11-26 device
        // run: AUDIO_FIRST_FRAME frames=1024 once, then AUDIO_DECODE_FAILED status=0 frames=0).
        var descriptor = AudioStreamBasicDescription(
            mSampleRate: Double(payload.sampleRate),
            mFormatID: kAudioFormatMPEG4AAC,
            mFormatFlags: 0,
            mBytesPerPacket: 0,
            mFramesPerPacket: 1024,
            mBytesPerFrame: 0,
            mChannelsPerFrame: UInt32(payload.channels),
            mBitsPerChannel: 0,
            mReserved: 0
        )
        var description: CMAudioFormatDescription?
        let created = payload.audioSpecificConfig.withUnsafeBytes { raw -> OSStatus in
            guard let base = raw.baseAddress else { return -1 }
            return CMAudioFormatDescriptionCreate(
                allocator: kCFAllocatorDefault,
                asbd: &descriptor,
                layoutSize: 0,
                layout: nil,
                magicCookieSize: payload.audioSpecificConfig.count,
                magicCookie: base,
                extensions: nil,
                formatDescriptionOut: &description
            )
        }
        guard created == noErr, let description else {
            fail("AudioSpecificConfig rejected status=\(created)")
            return false
        }
        let inputFormat = AVAudioFormat(cmAudioFormatDescription: description)
        guard let newConverter = AVAudioConverter(from: inputFormat, to: pcm) else {
            fail("AVAudioConverter refused aac-lc \(payload.sampleRate)Hz \(payload.channels)ch")
            return false
        }
        self.inputFormat = inputFormat
        converter = newConverter

        let newEngine = AVAudioEngine()
        newEngine.attach(player)
        newEngine.connect(player, to: newEngine.mainMixerNode, format: pcm)
        newEngine.mainMixerNode.outputVolume = 1
        player.volume = muted ? 0 : 1
        do {
            newEngine.prepare()
            try newEngine.start()
        } catch {
            fail("engine start failed: \(error.localizedDescription)")
            return false
        }
        engine = newEngine
        player.play()

        state = .ready
        statusText = "audio \(payload.sampleRate)Hz \(payload.channels)ch"
        emit("AUDIO_FORMAT \(payload.descriptionText)")
        return true
    }

    /// Decodes one access unit and schedules it. `ptsUs` is logged with the first frame of a stream.
    public func enqueue(ptsUs: Int64, accessUnit: Data) {
        guard let converter, let inputFormat, let pcmFormat else { return }
        guard let engine, engine.isRunning else { return }
        bytes += accessUnit.count

        let capacity: AVAudioFrameCount = 4096
        guard let pcm = AVAudioPCMBuffer(pcmFormat: pcmFormat, frameCapacity: capacity) else { return }

        // AVAudioCompressedBuffer is the streaming form of the same packet: one access unit plus
        // its size. The converter keeps its priming state across calls, which is what the raw
        // AudioConverter call sequence could not do.
        let packet = AVAudioCompressedBuffer(
            format: inputFormat,
            packetCapacity: 1,
            maximumPacketSize: max(accessUnit.count, 1)
        )
        accessUnit.copyBytes(to: packet.data.assumingMemoryBound(to: UInt8.self), count: accessUnit.count)
        packet.byteLength = UInt32(accessUnit.count)
        packet.packetCount = 1
        packet.packetDescriptions?.pointee = AudioStreamPacketDescription(
            mStartOffset: 0,
            mVariableFramesInPacket: 0,
            mDataByteSize: UInt32(accessUnit.count)
        )

        var supplied = false
        var error: NSError?
        let status = converter.convert(to: pcm, error: &error) { _, inputStatus in
            if supplied {
                inputStatus.pointee = .noDataNow
                return nil
            }
            supplied = true
            inputStatus.pointee = .haveData
            return packet
        }

        if let error {
            dropped += 1
            if dropped <= 3 || dropped % 100 == 0 {
                emit("AUDIO_DECODE_FAILED status=\(status.rawValue) \(error.localizedDescription) dropped=\(dropped)")
            }
            return
        }
        guard pcm.frameLength > 0 else {
            dropped += 1
            if dropped <= 3 || dropped % 100 == 0 {
                emit("AUDIO_DECODE_EMPTY status=\(status.rawValue) dropped=\(dropped)")
            }
            return
        }

        // pcm.frameLength is set by the converter.
        if let dir = HarnessFlags.environment("P08_AUDIO_TIMELINE_DIR") {
            writeTimeline(directory: dir, pcm: pcm, ptsUs: ptsUs)
        }
        measureAmplitude(pcm)

        // Bound the queue at half a second. Without this, a slow decode or a stalled output device
        // makes the Mac fall further and further behind, and the offset to the picture grows
        // without any single frame looking wrong.
        let queued = scheduledFrames - playedFrames()
        if queued > AVAudioFramePosition(pcmFormat.sampleRate / 2) {
            dropped += 1
            if dropped <= 3 || dropped % 100 == 0 {
                emit("AUDIO_BACKLOG_DROP queued=\(queued) dropped=\(dropped)")
            }
            return
        }

        scheduledFrames += AVAudioFramePosition(pcm.frameLength)
        player.scheduleBuffer(pcm, at: nil, options: [], completionHandler: nil)
        frames += 1
        if state != .playing {
            state = .playing
            statusText = "playing"
            emit("AUDIO_FIRST_FRAME ptsUs=\(ptsUs) frames=\(pcm.frameLength)")
        }
    }

    public func stop() {
        player.stop()
        engine?.stop()
        try? timelineFile?.close()
        timelineFile = nil
        converter = nil
        inputFormat = nil
        engine = nil
        pcmFormat = nil
        format = nil
        scheduledFrames = 0
        if state != .failed {
            state = .idle
            statusText = "audio off"
        }
    }

    private func playedFrames() -> AVAudioFramePosition {
        guard let nodeTime = player.lastRenderTime, let playerTime = player.playerTime(forNodeTime: nodeTime) else {
            return 0
        }
        return playerTime.sampleTime
    }

    private func measureAmplitude(_ pcm: AVAudioPCMBuffer) {
        guard let data = pcm.floatChannelData else { return }
        let frames = Int(pcm.frameLength)
        let channels = Int(pcm.format.channelCount)
        guard frames > 0, channels > 0 else { return }

        var sum = 0.0
        for channel in 0..<channels {
            let samples = data[channel]
            for index in 0..<frames {
                let value = Double(samples[index])
                sum += value * value
            }
        }
        windowSquares += sum
        windowSamples += frames * channels

        let now = Date()
        guard now.timeIntervalSince(windowStart) >= 1.0 else { return }
        let mean = windowSquares / Double(max(windowSamples, 1))
        // Float samples are ±1; scale to the 16-bit units the phone reports so both sides of the
        // evidence can be compared without a conversion step in the middle.
        let level = (mean.squareRoot() * 32_768).rounded()
        rms = level
        silent = level < 32
        emit("AUDIO rms=\(Int(level)) \(silent ? "silence" : "signal") frames=\(frames) total=\(self.frames)")
        windowStart = now
        windowSquares = 0
        windowSamples = 0
    }

    /// Harness-only timeline writer. RMS is computed per access unit (not per one-second window) so
    /// a 150 ms beep is visible at buffer resolution; values are in the same 16-bit units as the
    /// phone's `AUDIO rms=` lines.
    private func writeTimeline(directory: String, pcm: AVAudioPCMBuffer, ptsUs: Int64) {
        if timelineFile == nil {
            try? FileManager.default.createDirectory(atPath: directory, withIntermediateDirectories: true)
            let path = directory + "/audio.csv"
            FileManager.default.createFile(atPath: path, contents: nil)
            timelineFile = FileHandle(forWritingAtPath: path)
            timelineFile?.write(Data("index,wallUs,ptsUs,frames,rms16\n".utf8))
        }
        var sum = 0.0
        var count = 0
        if let data = pcm.floatChannelData {
            let frames = Int(pcm.frameLength)
            let channels = Int(pcm.format.channelCount)
            for channel in 0..<channels {
                let samples = data[channel]
                for frame in 0..<frames {
                    let value = Double(samples[frame])
                    sum += value * value
                }
                count += frames
            }
        }
        let rms = count > 0
            ? Int(((sum / Double(count)).squareRoot() * 32_768).rounded())
            : 0
        timelineIndex += 1
        let wallUs = Int64(Date().timeIntervalSince1970 * 1_000_000)
        let line = "\(timelineIndex),\(wallUs),\(ptsUs),\(pcm.frameLength),\(rms)\n"
        timelineFile?.write(Data(line.utf8))
    }

    private func fail(_ reason: String) {
        state = .failed
        statusText = "audio failed: \(reason)"
        log.error("\(reason, privacy: .public)")
    }

    private func emit(_ line: String) {
        log.info("\(line, privacy: .public)")
        print(line)
    }
}