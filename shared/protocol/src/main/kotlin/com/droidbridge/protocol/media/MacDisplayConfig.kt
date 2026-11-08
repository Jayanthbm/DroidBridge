package com.droidbridge.protocol.media

/**
 * The complete macOS receiver settings for mirroring, in one place (ADR-028).
 *
 * macOS has no analogue of the encoder config above — it never touches pixels, it only
 * *watches* them — so the shape is smaller: how to build the format description, how the
 * display layer is fed, and how a flush recovery requests a keyframe.
 */
class MacDisplayConfig private constructor(
    /** Adaptive quality: lets the layer pick a sensible quality for the measured bandwidth. */
    val adaptiveQuality: Boolean = true,
    /**
     * Keeps the mirrored phone screen out of a Mac screen recording (R-31). Default true
     * because mirroring is the whole product and a leak is a privacy incident.
     */
    val preventsCapture: Boolean = true,
    /**
     * Legacy enqueue API (`AVSampleBufferDisplayLayer`) is the only path that compiles
     * today. The replacement, `AVSampleBufferVideoRenderer.Receiver`, is documented as
     * macOS 27+ and is not shipping. Do not raise this version number to silence the
     * deprecation warning — the adapter that isolates this enum is the whole point.
     */
    val displayLayerApi: DisplayLayerApi = DisplayLayerApi.LEGACY_ENQUEUE,
) {

    enum class DisplayLayerApi {
        LEGACY_ENQUEUE,  // AVSampleBufferDisplayLayer / AVQueuedSampleBufferRendering
        RECEIVER,        // AVSampleBufferVideoRenderer.Receiver (macOS 27+)
    }
}

/**
 * One decoded frame for the Mac viewer, in CoreMedia shape.
 *
 * Produced here as a value object so the macOS side and the tests can reason about timing
 * without touching the display layer. [ptsUs] arrives from Android's
 * `MediaCodec.BufferInfo.presentationTimeUs` and is rebased on the Mac by the transport
 * (NETWORK_PROTOCOL §7).
 */
data class DisplayFrame(
    val ptsUs: Long,
    val payload: ByteArray,
    val isKeyframe: Boolean,
) {
    /** CoreMedia timescale of 1/1,000,000, carrying microseconds exactly (R-30). */
    val timescale: Int get() = 1_000_000
}
