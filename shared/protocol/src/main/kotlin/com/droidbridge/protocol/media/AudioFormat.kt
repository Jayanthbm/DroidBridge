package com.droidbridge.protocol.media

import java.util.Base64

/**
 * Payload of the `audio.format` control message.
 *
 * ## Why AAC-LC
 *
 * Every Android device ships a `MediaCodec` AAC-LC encoder, and macOS decodes it natively, so the
 * format needs no bundled codec on either side. Opus compresses better at the same quality but has
 * no guaranteed *encoder* on Android, and a missing encoder would turn "audio is on" into a device-
 * specific failure the user cannot fix.
 *
 * ## Why the AudioSpecificConfig travels
 *
 * The encoder's `csd-0` buffer is the MPEG-4 AudioSpecificConfig. A decoder can guess a sample rate
 * from the SBR-free AAC header, but guessing means trusting the payload to be well formed; the
 * configuration is announced once, in the control channel, exactly as `mirror.format` announces the
 * H.264 parameter sets. The payload after it is a bare AAC access unit.
 *
 * JSON, base64 for the binary field, fixed key order so the encoding is deterministic and assertable.
 */
class AudioFormat(
    /** Output sample rate in Hz: 44100 or 48000 in practice, 8000..48000 accepted. */
    val sampleRate: Int,
    /** 1 (mono) or 2 (stereo). */
    val channels: Int,
    /** Nominal encoder bitrate in bits per second; the actual rate varies per frame. */
    val bitrate: Int,
    /** MPEG-4 AudioSpecificConfig as produced by the encoder, normally 2 bytes for AAC-LC. */
    val audioSpecificConfig: ByteArray,
) {

    /** The only codec this protocol version carries. A different value is a refused format. */
    val codec: String get() = CODEC_AAC_LC

    init {
        require(sampleRate in ALLOWED_SAMPLE_RATES) {
            "sampleRate must be in ${ALLOWED_SAMPLE_RATES.first}..${ALLOWED_SAMPLE_RATES.last}, was $sampleRate"
        }
        require(channels in ALLOWED_CHANNELS) {
            "channels must be one of $ALLOWED_CHANNELS, was $channels"
        }
        require(bitrate > 0) { "bitrate must be positive, was $bitrate" }
        require(audioSpecificConfig.isNotEmpty()) { "AudioSpecificConfig is empty" }
    }

    /** Serialises the payload for an `audio.format` control message. */
    fun toJsonPayload(): String = buildString {
        append('{')
        append("\"codec\":\"").append(codec).append("\",")
        append("\"sampleRate\":").append(sampleRate).append(',')
        append("\"channels\":").append(channels).append(',')
        append("\"bitrate\":").append(bitrate).append(',')
        append("\"audioSpecificConfig\":\"")
        append(BASE64_ENCODER.encodeToString(audioSpecificConfig)).append("\"")
        append('}')
    }

    override fun toString(): String =
        "AudioFormat($codec ${sampleRate}Hz ${channels}ch ${bitrate / 1000}kbps " +
            "asc=${audioSpecificConfig.size}B)"

    companion object {
        const val CODEC_AAC_LC: String = "aac-lc"

        val ALLOWED_SAMPLE_RATES: IntRange = 8_000..48_000
        val ALLOWED_CHANNELS: Set<Int> = linkedSetOf(1, 2)

        private val BASE64_ENCODER: Base64.Encoder = Base64.getEncoder()
    }
}
