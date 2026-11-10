package com.droidbridge.experiment.mirroring

/**
 * Pure decisions about the encode geometry and which frames may be sent.
 *
 * Kept out of the service so the rules that decide what reaches the wire can be unit-tested without
 * a device. Everything here was shaped by a measurement, not by preference:
 *
 * - the encoder reports 2-pixel width/height alignment, so an odd size is rejected (P01);
 * - a phone rotated to portrait produces a tall capture, and encoding it into a wide encoder surface
 *   squashes it, so the encode size follows the content's own aspect (P08);
 * - the first frame after `mirror.format`, and after any receiver flush, must be an IDR, or the
 *   receiver decodes P-frames against a decoder with no reference picture (NETWORK_PROTOCOL §7).
 */
object MirrorPolicy {

    /** Longest encoded side, in pixels. */
    const val MAX_LONG_SIDE: Int = 1280

    /** The encoder aligns to 2 pixels; anything odd is refused at configure time. */
    const val ALIGNMENT: Int = 2

    /**
     * Fits [width]×[height] into [maxLongSide] without changing the aspect, aligned to 2.
     *
     * The cap exists for the target Mac: an M1 with 8 GB decodes 1280-wide H.264 comfortably and
     * has no reason to decode the panel's full 1440×3120 for a window that is smaller than that.
     * Mirroring the panel resolution would triple the bitrate for pixels nobody sees.
     */
    fun fitEncodeSize(
        width: Int,
        height: Int,
        maxLongSide: Int = MAX_LONG_SIDE,
    ): Pair<Int, Int> {
        require(width > 0) { "width must be positive, was $width" }
        require(height > 0) { "height must be positive, was $height" }
        require(maxLongSide >= ALIGNMENT) { "maxLongSide must be at least $ALIGNMENT, was $maxLongSide" }

        val longSide = maxOf(width, height)
        val scale = if (longSide <= maxLongSide) 1.0 else maxLongSide.toDouble() / longSide
        val scaledWidth = align((width * scale).toInt().coerceAtLeast(ALIGNMENT))
        val scaledHeight = align((height * scale).toInt().coerceAtLeast(ALIGNMENT))
        return scaledWidth to scaledHeight
    }

    /** Rounds up to the encoder's alignment. */
    fun align(value: Int): Int =
        if (value % ALIGNMENT == 0) value else value + (ALIGNMENT - value % ALIGNMENT)

    /**
     * True when a newly reported content size needs a different encode size to stay undistorted.
     *
     * A one-pixel wobble is not a rotation: re-encoding for it would force a keyframe and a format
     * message for no visible benefit, so a change is only honoured when the aspect moves by more
     * than [aspectTolerance].
     */
    fun needsReEncode(
        currentWidth: Int,
        currentHeight: Int,
        contentWidth: Int,
        contentHeight: Int,
        aspectTolerance: Double = 0.02,
    ): Boolean {
        if (contentWidth <= 0 || contentHeight <= 0) return false
        val (targetWidth, targetHeight) = fitEncodeSize(contentWidth, contentHeight)
        if (targetWidth == currentWidth && targetHeight == currentHeight) return false

        val currentAspect = currentWidth.toDouble() / currentHeight
        val targetAspect = targetWidth.toDouble() / targetHeight
        return kotlin.math.abs(currentAspect - targetAspect) > aspectTolerance
    }

    /**
     * Decides whether one encoded access unit may be sent.
     *
     * The gate exists because a receiver cannot start on a P-frame. After `mirror.format` the
     * producer therefore discards frames until the next IDR instead of sending a prefix of frames the
     * decoder would fail to use.
     */
    class FrameGate {
        private var awaitingKeyframe = true

        /** True while frames are being discarded until an IDR arrives. */
        val isAwaitingKeyframe: Boolean get() = awaitingKeyframe

        /** Re-arms the gate: after a format change, a new session or a receiver keyframe request. */
        fun requireKeyframe() {
            awaitingKeyframe = true
        }

        /** Returns true when this frame should be sent. */
        fun admit(isKeyframe: Boolean): Boolean {
            if (awaitingKeyframe) {
                if (!isKeyframe) return false
                awaitingKeyframe = false
            }
            return true
        }
    }
}
