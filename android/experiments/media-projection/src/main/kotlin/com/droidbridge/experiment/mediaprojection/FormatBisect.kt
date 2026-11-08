package com.droidbridge.experiment.mediaprojection

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log

/**
 * Finds which MediaFormat key the encoder refuses.
 *
 * `configure()` failed with `0x80001001` (OMX_ErrorUndefined) and the stack gives no clue which
 * key was rejected. Rather than guess and re-flash, this configures the encoder once per candidate
 * key set and records the first that succeeds, so the answer is measured.
 *
 * Each attempt uses a fresh codec instance: a codec that failed to configure is not reusable.
 */
object FormatBisect {

    private const val TAG = "P01Bisect"

    private fun base(): MediaFormat =
        MediaFormat.createVideoFormat(EncoderProbe.MIME, 1280, 720).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
        }

    /** Applies one candidate key on top of the base format. */
    private data class Candidate(val name: String, val apply: MediaFormat.() -> Unit)

    private val candidates = listOf(
        // Established by the first pass: without KEY_I_FRAME_INTERVAL this encoder refuses to
        // configure at all, and with it alone it configures. So I_FRAME_INTERVAL is the base and
        // each other key is added to it one at a time to find which is rejected.
        Candidate("base: I_FRAME_INTERVAL only") {
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        },
        Candidate("+ MAX_B_FRAMES=0") {
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        },
        Candidate("+ REPEAT_PREVIOUS_FRAME_AFTER") {
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 250_000L)
        },
        Candidate("+ PROFILE=Baseline") {
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
        },
        Candidate("+ PROFILE=Baseline + LEVEL=31") {
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
        },
        Candidate("+ PROFILE=High") {
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
        },
        Candidate("+ REPEAT_PREVIOUS_FRAME_AFTER (no MAX_B_FRAMES)") {
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 250_000L)
        },
        Candidate("I_FRAME_INTERVAL + REPEAT + PROFILE=Baseline") {
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 250_000L)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
        },
    )

    /** Runs every candidate and returns one line per attempt. */
    fun run(): String = buildString {
        for (candidate in candidates) {
            val format = base().apply(candidate.apply)
            val outcome = try {
                val codec = MediaCodec.createEncoderByType(EncoderProbe.MIME)
                try {
                    codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    "OK"
                } finally {
                    runCatching { codec.release() }
                }
            } catch (e: Exception) {
                "${e.javaClass.simpleName}: ${e.message}"
            }
            append("${candidate.name} -> $outcome\n")
            Log.i(TAG, "${candidate.name} -> $outcome")
        }
    }
}
