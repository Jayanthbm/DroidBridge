import Foundation
import AVFoundation
import VideoToolbox
import Observation
import AppKit
import SwiftUI

/// Receives DroidBridge VIDEO_FRAME payloads, converts Annex-B H.264 access
/// units to AVCC sample buffers, and renders them through a
/// VideoToolbox-backed AVSampleBufferDisplayLayer.
@MainActor
@Observable
final class MirrorStream {
    @ObservationIgnored let displayLayer = AVSampleBufferDisplayLayer()
    @ObservationIgnored private var formatDescription: CMVideoFormatDescription?
    @ObservationIgnored private var sps: [UInt8]?
    @ObservationIgnored private var pps: [UInt8]?
    @ObservationIgnored private var lastKeyframeRequest = Date.distantPast
    @ObservationIgnored private var droppedFrames = 0
    @ObservationIgnored private var watchdog: Task<Void, Never>?

    private(set) var hasVideo = false
    private(set) var width = 0
    private(set) var height = 0
    private(set) var rotation = 0
    private(set) var frameCount = 0
    private(set) var lastFrameAt: Date?
    private(set) var isLive = false
    private(set) var isStopped = false
    private(set) var decodeError: String?

    var aspectRatio: CGFloat {
        guard width > 0, height > 0 else { return 9.0 / 19.5 }
        return CGFloat(width) / CGFloat(height)
    }

    @ObservationIgnored var onNeedKeyframe: (() -> Void)?

    init(onNeedKeyframe: (() -> Void)? = nil) {
        self.onNeedKeyframe = onNeedKeyframe
        displayLayer.videoGravity = .resizeAspect
        displayLayer.backgroundColor = NSColor.black.cgColor
        displayLayer.isOpaque = true
        displayLayer.flushAndRemoveImage()
        watchdog = Task { @MainActor [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(1))
                guard let self else { return }
                // Android's encoder can legitimately send no frames for a static
                // screen; only mark the stream inactive after a generous pause.
                if self.isLive, let last = self.lastFrameAt,
                   Date().timeIntervalSince(last) > 8.0 {
                    self.isLive = false
                }
            }
        }
    }

    func enqueue(_ payload: Data) {
        guard payload.count > 5 else { return }
        let headerLength = payload.prefix(4).reduce(0) { ($0 << 8) | Int($1) }
        guard headerLength > 0, payload.count >= 4 + headerLength else { return }
        let metadata = payload.subdata(in: 4..<(4 + headerLength))
        let accessUnit = payload.subdata(in: (4 + headerLength)..<payload.count)
        guard let json = try? JSONSerialization.jsonObject(with: metadata) as? [String: Any] else {
            fail("The video frame metadata was invalid.")
            return
        }

        let newWidth = json["w"] as? Int ?? width
        let newHeight = json["h"] as? Int ?? height
        let newRotation = json["rotation"] as? Int ?? rotation
        let keyframe = (json["key"] as? Bool) ?? false
        let pts = (json["pts"] as? NSNumber)?.int64Value ?? 0

        if newWidth != width || newHeight != height || newRotation != rotation {
            width = newWidth
            height = newHeight
            rotation = newRotation
        }

        let units = nalUnits(in: accessUnit)
        guard !units.isEmpty else { return }

        var sawSPS = false
        var sawPPS = false
        for unit in units {
            guard let first = unit.first else { continue }
            switch first & 0x1f {
            case 7:
                if sps != Array(unit) { sps = Array(unit); formatDescription = nil }
                sawSPS = true
            case 8:
                if pps != Array(unit) { pps = Array(unit); formatDescription = nil }
                sawPPS = true
            default:
                break
            }
        }
        if sawSPS || sawPPS || keyframe {
            ensureFormatDescription()
        }
        guard let formatDescription else {
            needKeyframe()
            return
        }

        if displayLayer.status == .failed || displayLayer.requiresFlushToResumeDecoding {
            displayLayer.flush()
            self.formatDescription = nil
            needKeyframe()
            return
        }
        guard displayLayer.isReadyForMoreMediaData || keyframe else {
            droppedFrames += 1
            if droppedFrames > 12 {
                droppedFrames = 0
                needKeyframe()
            }
            return
        }
        droppedFrames = 0

        var avcc = Data(capacity: accessUnit.count)
        var vclCount = 0
        for unit in units {
            guard let first = unit.first else { continue }
            let type = first & 0x1f
            if type == 7 || type == 8 { continue }
            var length = UInt32(unit.count).bigEndian
            withUnsafeBytes(of: &length) { avcc.append(contentsOf: $0) }
            avcc.append(unit)
            vclCount += 1
        }
        guard vclCount > 0, !avcc.isEmpty else { return }

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
        guard blockStatus == kCMBlockBufferNoErr, let blockBuffer else {
            fail("Could not allocate a video buffer.")
            return
        }
        let replaceStatus = avcc.withUnsafeBytes { bytes in
            CMBlockBufferReplaceDataBytes(
                with: bytes.baseAddress!,
                blockBuffer: blockBuffer,
                offsetIntoDestination: 0,
                dataLength: avcc.count
            )
        }
        guard replaceStatus == kCMBlockBufferNoErr else {
            fail("Could not fill a video buffer.")
            return
        }

        var timing = CMSampleTimingInfo(
            duration: CMTime(value: 1, timescale: 30),
            presentationTimeStamp: CMTime(value: pts, timescale: 1_000_000),
            decodeTimeStamp: .invalid
        )
        var sampleSize = avcc.count
        var sampleBuffer: CMSampleBuffer?
        let sampleStatus = CMSampleBufferCreateReady(
            allocator: kCFAllocatorDefault,
            dataBuffer: blockBuffer,
            formatDescription: formatDescription,
            sampleCount: 1,
            sampleTimingEntryCount: 1,
            sampleTimingArray: &timing,
            sampleSizeEntryCount: 1,
            sampleSizeArray: &sampleSize,
            sampleBufferOut: &sampleBuffer
        )
        guard sampleStatus == noErr, let sampleBuffer else {
            fail("Could not create a video sample.")
            return
        }

        if let attachments = CMSampleBufferGetSampleAttachmentsArray(sampleBuffer, createIfNecessary: true) {
            let dictionary = unsafeBitCast(
                CFArrayGetValueAtIndex(attachments, 0),
                to: CFMutableDictionary.self
            )
            CFDictionarySetValue(
                dictionary,
                Unmanaged.passUnretained(kCMSampleAttachmentKey_DisplayImmediately).toOpaque(),
                Unmanaged.passUnretained(kCFBooleanTrue).toOpaque()
            )
            if !keyframe {
                CFDictionarySetValue(
                    dictionary,
                    Unmanaged.passUnretained(kCMSampleAttachmentKey_NotSync).toOpaque(),
                    Unmanaged.passUnretained(kCFBooleanTrue).toOpaque()
                )
            }
        }

        displayLayer.enqueue(sampleBuffer)
        frameCount += 1
        lastFrameAt = Date()
        hasVideo = true
        isLive = true
        isStopped = false
        decodeError = nil
    }

    func markStopped() {
        isStopped = true
        isLive = false
    }

    func reset() {
        displayLayer.flushAndRemoveImage()
        formatDescription = nil
        sps = nil
        pps = nil
        hasVideo = false
        isLive = false
        isStopped = false
        frameCount = 0
        width = 0
        height = 0
        rotation = 0
        decodeError = nil
    }

    private func ensureFormatDescription() {
        if formatDescription != nil { return }
        guard let sps, let pps, !sps.isEmpty, !pps.isEmpty else { return }
        let spsData = NSData(bytes: sps, length: sps.count)
        let ppsData = NSData(bytes: pps, length: pps.count)
        let pointers: [UnsafePointer<UInt8>] = [
            spsData.bytes.assumingMemoryBound(to: UInt8.self),
            ppsData.bytes.assumingMemoryBound(to: UInt8.self)
        ]
        let sizes = [sps.count, pps.count]
        var description: CMFormatDescription?
        let status = pointers.withUnsafeBufferPointer { pointerBuffer in
            sizes.withUnsafeBufferPointer { sizeBuffer in
                CMVideoFormatDescriptionCreateFromH264ParameterSets(
                    allocator: kCFAllocatorDefault,
                    parameterSetCount: 2,
                    parameterSetPointers: pointerBuffer.baseAddress!,
                    parameterSetSizes: sizeBuffer.baseAddress!,
                    nalUnitHeaderLength: 4,
                    formatDescriptionOut: &description
                )
            }
        }
        guard status == noErr, let description else {
            fail("The H.264 parameter sets were invalid.")
            return
        }
        formatDescription = description
        displayLayer.flushAndRemoveImage()
    }

    private func needKeyframe() {
        let now = Date()
        guard now.timeIntervalSince(lastKeyframeRequest) > 1.5 else { return }
        lastKeyframeRequest = now
        onNeedKeyframe?()
    }

    private func fail(_ message: String) {
        decodeError = message
        displayLayer.flush()
        formatDescription = nil
        needKeyframe()
    }

    private func nalUnits(in data: Data) -> [Data] {
        let bytes = [UInt8](data)
        var units: [Data] = []
        var index = 0
        var start: Int?
        while index + 3 <= bytes.count {
            if bytes[index] == 0, bytes[index + 1] == 0, bytes[index + 2] == 1 {
                if let start, index > start {
                    units.append(data.subdata(in: start..<index))
                }
                index += 3
                start = index
            } else {
                index += 1
            }
        }
        if let start, start < bytes.count {
            units.append(data.subdata(in: start..<bytes.count))
        }
        return units.filter { !$0.isEmpty }
    }
}

@MainActor
struct VideoSurfaceView: NSViewRepresentable {
    let displayLayer: AVSampleBufferDisplayLayer

    func makeNSView(context: Context) -> NSView {
        let view = NSView()
        view.identifier = NSUserInterfaceItemIdentifier("DroidBridgeVideoStage")
        view.wantsLayer = true
        displayLayer.frame = view.bounds
        displayLayer.autoresizingMask = [.layerWidthSizable, .layerHeightSizable]
        view.layer = displayLayer
        return view
    }

    func updateNSView(_ nsView: NSView, context: Context) {
        displayLayer.frame = nsView.bounds
    }
}
