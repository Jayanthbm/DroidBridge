package com.droidbridge.protocol.media

/**
 * The complete Android-side encoder recipe for mirroring, in one place.
 *
 * Everything here is the documented, machine-verified part of the framing contract (ADR-027 /
 * R-29). Nothing before this object reads pixels, and nothing after it converts anything.
 *
 * The owner's machine owns the actual `MediaCodec` call, because it needs the target
 * device: `configure()`, `createInputSurface()`, `createVirtualDisplay()` and the encoder's
 * accept/reject behaviour are measured there (P01/U18), not reproduced here.
 */
class AndroidCodecConfig private constructor(
    /** Encode size and rate the device actually accepts, measured at runtime (P01). */
    val width: Int,
    val height: Int,
    val bitrateKbps: Int,
    /** Profile the encoder must declare; an unset profile is device-selected and may reject the session. */
    val profile: H264Profile,
) {

    /** Surface-fed: `CONFIGURE_FLAG_ENCODE` with a `createInputSurface()` feed. No pixels, no conversion. */
    val surfaceMode = true

    /** Default is zero — no B-frames, keep samples in decode order (R-29). */
    val maxBFrames = 0

    /**
     * Required because the documented API-31 quality floor applies to VBR only
     * (R-29). `preferred` asks for a bitrate close to the target; `cbr` if the
     * device supports it is what makes the budget measurable.
     */
    enum class BitrateMode(val label: String) {
        PREFERRED("preferred"),
        CBR("cbr"),
    }

    /** VBR is the documented default; the floor makes the budget meaningless there. */
    val bitrateMode: BitrateMode = BitrateMode.PREFERRED

    /**
     * API 34: the encoder must shrink or grow with the captured content, and a re-encode
     * follows (NETWORK_PROTOCOL §7). This is the target every time.
     */
    val onCapturedContentResize = true

    /**
     * Keeps a static screen producing frames so a frozen viewer is not mistaken for a
     * healthy session (R-29). [seconds] between repeats of the last frame; 1.0 is a sane
     * starting value for a settings list.
     */
    val repeatPreviousFrameAfterSeconds: Double = 1.0

    /** I-frame interval in seconds; keep it short enough that a reconnect can recover fast. */
    val iFrameIntervalSeconds: Double = 2.0

    companion object {

        /** Profiles approved for mirroring: deterministic and widely supported. */
        enum class H264Profile {
            BASELINE, HIGH
        }

        /**
         * Builds the config from what the device measured, so a size the encoder rejects
         * can never reach the wire.
         */
        fun fromDeviceAccepted(
            width: Int,
            height: Int,
            bitrateKbps: Int,
            profile: H264Profile,
        ): AndroidCodecConfig = AndroidCodecConfig(width, height, bitrateKbps, profile)
    }
}
