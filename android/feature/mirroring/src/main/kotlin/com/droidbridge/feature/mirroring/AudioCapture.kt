package com.droidbridge.feature.mirroring

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.util.Log
import com.droidbridge.protocol.media.AudioFormat as WireAudioFormat
import com.droidbridge.protocol.media.AudioPayload
import com.droidbridge.protocol.session.ControlMessage
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * P08 audio: capture the phone's media playback and send it on the same session as the screen.
 *
 * ```
 * AudioPlaybackCaptureConfiguration → AudioRecord(PCM 16-bit) → MediaCodec AAC-LC
 *     → audio.format / AUDIO over the authenticated session
 * ```
 *
 * ## What it can and cannot hear
 *
 * Playback capture is the only public path, and the platform bounds it (R-15, measured in P12):
 * media, game and unknown usages that allow capture arrive; `USAGE_VOICE_COMMUNICATION` — call
 * audio — is always digital silence. The capture configuration names the three allowed usages
 * explicitly rather than relying on a default, so what the stream contains is what this file says it
 * contains.
 *
 * ## Why silence is measured, not assumed
 *
 * A refused capture is silent, not absent: frames keep arriving at full rate carrying zeros
 * (ADR-045 §4). [Stats.rms] is therefore computed from the PCM that was actually captured, so the
 * UI can say "playing" versus "captured silence" instead of showing a live-looking meter over
 * nothing. The Mac measures the decoded signal independently, which is what makes the end-to-end
 * claim evidence rather than a hope.
 *
 * ## Why AAC-LC
 *
 * Every Android device has an AAC-LC encoder in `MediaCodec`, and macOS decodes it natively — no
 * bundled codec, no device-specific gap. 128 kbit/s stereo at 44.1 kHz is transparent enough for
 * media and small enough to ride next to a screen stream on the same Wi-Fi link.
 */
internal class AudioCapture(
    private val context: Context,
    private val projection: MediaProjection,
    private val transport: MirrorTransport,
) {

    companion object {
        private const val TAG = "P08Audio"

        /** 44.1 kHz stereo is the intersection every device handles without a resampler surprise. */
        const val SAMPLE_RATE = 44_100
        const val CHANNELS = 2
        const val BITRATE = 128_000

        private const val CHANNEL_MASK_IN = AudioFormat.CHANNEL_IN_STEREO
        private const val CHANNEL_COUNT = 2
        private const val MAX_INPUT_BYTES = 16 * 1024
    }

    /** Live counters, read by the service's stats and the notification. */
    class Stats {
        @Volatile var state: String = "idle"
        @Volatile var framesSent: Long = 0
        @Volatile var bytesSent: Long = 0
        @Volatile var rms: Double = 0.0
        @Volatile var silent: Boolean = false
    }

    val stats = Stats()

    private val running = java.util.concurrent.atomic.AtomicBoolean(false)
    private var thread: Thread? = null
    private var record: AudioRecord? = null
    private var encoder: MediaCodec? = null

    /** True when the user granted the microphone permission playback capture needs. */
    fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Starts capturing. Returns false when audio is unavailable; the screen stream keeps running.
     *
     * Every failure here is logged with its reason and surfaced through [Stats.state], because
     * "audio is on but the Mac hears nothing" is otherwise indistinguishable from "the phone is quiet".
     */
    // Lint cannot see through hasPermission() above; the check is the guard for this whole path.
    @android.annotation.SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (!hasPermission()) {
            stats.state = "off: microphone permission not granted"
            Log.w(TAG, "audio unavailable: ${stats.state}")
            return false
        }
        if (running.get()) return true

        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val formatBuilder = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(CHANNEL_MASK_IN)
            .build()

        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_MASK_IN, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) {
            stats.state = "off: device refuses ${SAMPLE_RATE}Hz stereo capture ($minBuffer)"
            Log.w(TAG, stats.state)
            return false
        }

        val newRecord = try {
            AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(formatBuilder)
                .setBufferSizeInBytes(minBuffer * 2)
                .build()
        } catch (e: Exception) {
            stats.state = "off: AudioRecord failed (${e.javaClass.simpleName})"
            Log.w(TAG, stats.state, e)
            return false
        }

        if (newRecord.state != AudioRecord.STATE_INITIALIZED) {
            stats.state = "off: AudioRecord state=${newRecord.state}"
            Log.w(TAG, stats.state)
            runCatching { newRecord.release() }
            return false
        }

        val mediaFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS)
        mediaFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
        mediaFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_BYTES)

        val newEncoder = try {
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            codec
        } catch (e: Exception) {
            stats.state = "off: AAC encoder failed (${e.javaClass.simpleName})"
            Log.w(TAG, stats.state, e)
            runCatching { newRecord.release() }
            return false
        }

        record = newRecord
        encoder = newEncoder
        stats.framesSent = 0
        stats.bytesSent = 0
        stats.rms = 0.0
        stats.silent = false
        stats.state = "capturing ${SAMPLE_RATE}Hz stereo"
        running.set(true)

        val worker = Thread({ pump(newRecord, newEncoder) }, "p08-audio")
        worker.isDaemon = true
        thread = worker
        worker.start()
        Log.i(TAG, "audio started rate=$SAMPLE_RATE channels=$CHANNELS bitrate=$BITRATE")
        return true
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        thread?.interrupt()
        thread = null
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
        encoder = null
        stats.state = "stopped"
        Log.i(TAG, "audio stopped frames=${stats.framesSent} bytes=${stats.bytesSent} rms=${fmt(stats.rms)}")
    }

    /**
     * Reads PCM, encodes it, and sends one AUDIO frame per encoder output buffer.
     *
     * The presentation timestamps come from the count of samples fed rather than from a wall clock:
     * that is the same clock the decoder reconstitutes, so a gap in capture does not silently shift
     * everything after it.
     */
    private fun pump(record: AudioRecord, encoder: MediaCodec) {
        val pcm = ByteArray(MAX_INPUT_BYTES)
        val info = MediaCodec.BufferInfo()
        var samplesFed = 0L
        var formatAnnounced = false
        var windowStart = System.nanoTime()
        var windowSumSquares = 0.0
        var windowSamples = 0L

        try {
            record.startRecording()
            while (running.get()) {
                val read = record.read(pcm, 0, pcm.size)
                if (read <= 0) {
                    if (read == AudioRecord.ERROR_INVALID_OPERATION || read == AudioRecord.ERROR_BAD_VALUE) {
                        stats.state = "stopped: AudioRecord read error $read"
                        Log.w(TAG, stats.state)
                        return
                    }
                    continue
                }

                accumulateRms(pcm, read) { sumSquares, samples ->
                    windowSumSquares += sumSquares
                    windowSamples += samples
                }
                val now = System.nanoTime()
                if (now - windowStart >= 1_000_000_000L) {
                    publishWindow(windowSumSquares, windowSamples)
                    windowStart = now
                    windowSumSquares = 0.0
                    windowSamples = 0
                }

                var offset = 0
                while (offset < read && running.get()) {
                    val inputIndex = encoder.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val buffer = encoder.getInputBuffer(inputIndex) ?: break
                        buffer.clear()
                        val take = minOf(buffer.remaining(), read - offset)
                        buffer.put(pcm, offset, take)
                        val ptsUs = samplesFed * 1_000_000L / SAMPLE_RATE
                        encoder.queueInputBuffer(inputIndex, 0, take, ptsUs, 0)
                        samplesFed += take / (2L * CHANNEL_COUNT)
                        offset += take
                    }
                }

                while (true) {
                    val outputIndex = encoder.dequeueOutputBuffer(info, 0)
                    if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        formatAnnounced = announceFormat(encoder.outputFormat) || formatAnnounced
                        continue
                    }
                    if (outputIndex < 0) break
                    val buffer = encoder.getOutputBuffer(outputIndex)
                    if (buffer != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        // Never send audio before its format: a frame the Mac cannot configure a
                        // decoder for is worse than a frame that is a moment late.
                        if (!formatAnnounced) formatAnnounced = announceFormat(encoder.outputFormat)
                        if (formatAnnounced) {
                            val aac = ByteArray(info.size)
                            buffer.position(info.offset)
                            buffer.get(aac)
                            val payload = AudioPayload.encode(info.presentationTimeUs.coerceAtLeast(0), aac)
                            if (transport.send(ControlMessage.Type.AUDIO, payload)) {
                                stats.framesSent += 1
                                stats.bytesSent += aac.size.toLong()
                            }
                        }
                    }
                    encoder.releaseOutputBuffer(outputIndex, false)
                }
            }
        } catch (e: InterruptedException) {
            // Normal stop path.
        } catch (e: Exception) {
            stats.state = "stopped: ${e.javaClass.simpleName}: ${e.message}"
            Log.w(TAG, stats.state, e)
        }
    }

    /** Sends the AudioSpecificConfig once per stream, before the first access unit. */
    private fun announceFormat(outputFormat: MediaFormat): Boolean {
        val csd = outputFormat.getByteBuffer("csd-0") ?: run {
            Log.w(TAG, "encoder reported no csd-0; cannot announce a format")
            return false
        }
        val asc = ByteArray(csd.remaining())
        csd.get(asc)
        val format = WireAudioFormat(
            sampleRate = SAMPLE_RATE,
            channels = CHANNELS,
            bitrate = BITRATE,
            audioSpecificConfig = asc,
        )
        val sent = transport.send(
            ControlMessage.Type.AUDIO_FORMAT,
            format.toJsonPayload().toByteArray(Charsets.UTF_8),
        )
        if (sent) {
            Log.i(
                TAG,
                "audio.format sent rate=${format.sampleRate} channels=${format.channels} " +
                    "bitrate=${format.bitrate} asc=${asc.size}B",
            )
        }
        return sent
    }

    private inline fun accumulateRms(pcm: ByteArray, length: Int, sink: (Double, Long) -> Unit) {
        var sum = 0.0
        var count = 0L
        var i = 0
        while (i + 1 < length) {
            val sample = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toDouble()
            sum += sample * sample
            count += 1
            i += 2
        }
        if (count > 0) sink(sum, count)
    }

    private fun publishWindow(sumSquares: Double, samples: Long) {
        if (samples == 0L) return
        val rms = sqrt(sumSquares / samples)
        stats.rms = rms
        // About -60 dBFS: below this a meter is showing dither, not content.
        stats.silent = rms < 32.0
        Log.i(
            TAG,
            "audio rms=${fmt(rms)} ${if (stats.silent) "silence" else "signal"} " +
                "frames=${stats.framesSent} bytes=${stats.bytesSent}",
        )
    }

    private fun fmt(value: Double): String = String.format(java.util.Locale.US, "%.1f", value)
}