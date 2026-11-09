import AVFoundation
import CoreMedia
import Foundation
import VideoToolbox

/// P07 — Annex-B parsing and CMVideoFormatDescription/CMSampleBuffer construction.
///
/// MediaCodec emits Annex-B (start codes). CoreMedia wants a format description carrying **bare**
/// SPS/PPS NALs plus sample buffers whose NALs are 4-byte length-prefixed. This file is the
/// producer side of that conversion; P07's tests prove it against a real ffmpeg-generated stream
/// and a synthetic stream containing emulation-prevention bytes.
enum H264Error: Error, Equatable {
    case noStartCode
    case noParameterSets
    case parameterSetHasStartCode
    case unsupportedNalLengthSize(Int32)
    case invalidTiming(String)
    case blockBuffer(OSStatus)
    case sampleBuffer(OSStatus)
    case formatDescription(OSStatus)
}

struct H264NAL: Equatable {
    /// NAL unit type from the low five bits of the header.
    let type: Int
    /// NAL bytes without the start code, with emulation-prevention bytes preserved.
    let bytes: [UInt8]

    var isVCL: Bool { type >= 1 && type <= 5 }
    var isKeyframe: Bool { type == 5 }
}

struct H264AccessUnit {
    let nals: [H264NAL]
    let isKeyframe: Bool
    let ptsUs: Int64
}

enum H264AnnexB {

    /// Splits an Annex-B byte stream into NAL units.
    ///
    /// Both 3-byte and 4-byte start codes are accepted. Trailing zero bytes before the next start
    /// code are Annex-B padding and are stripped from the NAL payload; bytes inside the payload,
    /// including 0x00 0x00 0x03 emulation-prevention sequences, are preserved exactly.
    static func parse(_ data: Data) throws -> [H264NAL] {
        let bytes = [UInt8](data)
        var starts: [(offset: Int, length: Int)] = []
        var index = 0
        while index + 3 <= bytes.count {
            if bytes[index] == 0, bytes[index + 1] == 0, bytes[index + 2] == 1 {
                starts.append((index, 3))
                index += 3
            } else if index + 4 <= bytes.count,
                      bytes[index] == 0, bytes[index + 1] == 0,
                      bytes[index + 2] == 0, bytes[index + 3] == 1 {
                starts.append((index, 4))
                index += 4
            } else {
                index += 1
            }
        }
        guard !starts.isEmpty else { throw H264Error.noStartCode }

        var nals: [H264NAL] = []
        for (position, start) in starts.enumerated() {
            let payloadStart = start.offset + start.length
            let payloadEnd = position + 1 < starts.count ? starts[position + 1].offset : bytes.count
            guard payloadStart < payloadEnd else { continue }
            var payload = Array(bytes[payloadStart..<payloadEnd])
            // Strip trailing_zero_8bits only. Emulation-prevention bytes are internal, not trailing.
            while payload.last == 0 { payload.removeLast() }
            guard let header = payload.first else { continue }
            nals.append(H264NAL(type: Int(header & 0x1F), bytes: payload))
        }
        guard !nals.isEmpty else { throw H264Error.noStartCode }
        return nals
    }

    /// Returns the bare SPS and PPS from the first parameter-set pair.
    static func parameterSets(from nals: [H264NAL]) throws -> (sps: [UInt8], pps: [UInt8]) {
        guard let sps = nals.first(where: { $0.type == 7 })?.bytes,
              let pps = nals.first(where: { $0.type == 8 })?.bytes else {
            throw H264Error.noParameterSets
        }
        // A bare parameter set must not still carry an Annex-B start code. This is the negative
        // case P07 asks to prove: passing the SPS/PPS with start codes must fail.
        if hasStartCode(sps) || hasStartCode(pps) {
            throw H264Error.parameterSetHasStartCode
        }
        return (sps, pps)
    }

    /// Groups VCL NALs into one access unit per VCL NAL.
    ///
    /// The ffmpeg fixture and MediaCodec's screen encoder emit one slice per frame. Multi-slice
    /// access units are out of scope for P07 and would need first_mb_in_slice parsing; that
    /// limitation is recorded in the report.
    static func accessUnits(from nals: [H264NAL], frameDurationUs: Int64) -> [H264AccessUnit] {
        var units: [H264AccessUnit] = []
        var pendingSEI: [H264NAL] = []
        for nal in nals {
            if nal.type == 6 {
                pendingSEI.append(nal)
                continue
            }
            guard nal.isVCL else { continue }
            var unitNALs = pendingSEI
            pendingSEI.removeAll()
            unitNALs.append(nal)
            units.append(
                H264AccessUnit(
                    nals: unitNALs,
                    isKeyframe: nal.isKeyframe,
                    ptsUs: Int64(units.count) * frameDurationUs
                )
            )
        }
        return units
    }

    /// Converts NAL payloads to the AVCC wire shape: four-byte big-endian length before each NAL.
    static func lengthPrefixed(_ nals: [H264NAL]) -> Data {
        var data = Data()
        for nal in nals {
            let length = UInt32(nal.bytes.count)
            data.append(UInt8((length >> 24) & 0xFF))
            data.append(UInt8((length >> 16) & 0xFF))
            data.append(UInt8((length >> 8) & 0xFF))
            data.append(UInt8(length & 0xFF))
            data.append(contentsOf: nal.bytes)
        }
        return data
    }

    /// Builds the CoreMedia format description from bare SPS/PPS.
    ///
    /// `nalLengthSize` is deliberately restricted to 4: P07's sample buffers are AVCC with 4-byte
    /// lengths, and a different value would produce a broken picture. The wrong-size case is
    /// rejected rather than silently formatted with the wrong header size.
    static func makeFormatDescription(
        sps: [UInt8],
        pps: [UInt8],
        nalLengthSize: Int32 = 4
    ) throws -> CMVideoFormatDescription {
        if hasStartCode(sps) || hasStartCode(pps) {
            throw H264Error.parameterSetHasStartCode
        }
        guard nalLengthSize == 4 else {
            throw H264Error.unsupportedNalLengthSize(nalLengthSize)
        }
        var format: CMVideoFormatDescription?
        let status = sps.withUnsafeBufferPointer { spsBuffer in
            pps.withUnsafeBufferPointer { ppsBuffer in
                let pointers: [UnsafePointer<UInt8>] = [
                    spsBuffer.baseAddress!,
                    ppsBuffer.baseAddress!,
                ]
                let sizes: [Int] = [sps.count, pps.count]
                return pointers.withUnsafeBufferPointer { pointerBuffer in
                    sizes.withUnsafeBufferPointer { sizeBuffer in
                        CMVideoFormatDescriptionCreateFromH264ParameterSets(
                            allocator: kCFAllocatorDefault,
                            parameterSetCount: 2,
                            parameterSetPointers: pointerBuffer.baseAddress!,
                            parameterSetSizes: sizeBuffer.baseAddress!,
                            nalUnitHeaderLength: nalLengthSize,
                            formatDescriptionOut: &format
                        )
                    }
                }
            }
        }
        guard status == noErr, let format else {
            throw H264Error.formatDescription(status)
        }
        return format
    }

    /// Creates one CMSampleBuffer for an access unit, using a 1/1,000,000 timescale.
    static func makeSampleBuffer(
        for unit: H264AccessUnit,
        format: CMVideoFormatDescription,
        frameDurationUs: Int64
    ) throws -> CMSampleBuffer {
        let data = lengthPrefixed(unit.nals)
        var blockBuffer: CMBlockBuffer?
        let blockStatus = CMBlockBufferCreateWithMemoryBlock(
            allocator: kCFAllocatorDefault,
            memoryBlock: nil,
            blockLength: data.count,
            blockAllocator: kCFAllocatorDefault,
            customBlockSource: nil,
            offsetToData: 0,
            dataLength: data.count,
            flags: 0,
            blockBufferOut: &blockBuffer
        )
        guard blockStatus == noErr, let blockBuffer else {
            throw H264Error.blockBuffer(blockStatus)
        }
        let copyStatus = data.withUnsafeBytes { raw in
            CMBlockBufferReplaceDataBytes(
                with: raw.baseAddress!,
                blockBuffer: blockBuffer,
                offsetIntoDestination: 0,
                dataLength: data.count
            )
        }
        guard copyStatus == noErr else { throw H264Error.blockBuffer(copyStatus) }

        var timing = CMSampleTimingInfo(
            duration: CMTime(value: frameDurationUs, timescale: 1_000_000),
            presentationTimeStamp: CMTime(value: unit.ptsUs, timescale: 1_000_000),
            decodeTimeStamp: .invalid
        )
        var sampleSize = data.count
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
        guard sampleStatus == noErr, let sampleBuffer else {
            throw H264Error.sampleBuffer(sampleStatus)
        }
        if let attachments = CMSampleBufferGetSampleAttachmentsArray(sampleBuffer, createIfNecessary: true) {
            let dictionary = unsafeBitCast(
                CFArrayGetValueAtIndex(attachments, 0),
                to: CFMutableDictionary.self
            )
            let notSync = !unit.isKeyframe
            CFDictionarySetValue(
                dictionary,
                Unmanaged.passUnretained(kCMSampleAttachmentKey_NotSync).toOpaque(),
                Unmanaged.passUnretained(notSync ? kCFBooleanTrue : kCFBooleanFalse).toOpaque()
            )
        }
        return sampleBuffer
    }

    /// Pure timing rule: PTS must be strictly increasing. Used by the receiver and unit tests.
    static func validateStrictlyIncreasing(_ pts: [Int64]) throws {
        guard pts.count > 0 else { throw H264Error.invalidTiming("empty") }
        for index in 1..<pts.count where pts[index] <= pts[index - 1] {
            throw H264Error.invalidTiming(
                "PTS \(pts[index]) is not greater than \(pts[index - 1]) at index \(index)"
            )
        }
    }

    private static func hasStartCode(_ bytes: [UInt8]) -> Bool {
        if bytes.count >= 3, bytes[0] == 0, bytes[1] == 0, bytes[2] == 1 { return true }
        if bytes.count >= 4, bytes[0] == 0, bytes[1] == 0, bytes[2] == 0, bytes[3] == 1 { return true }
        return false
    }
}
