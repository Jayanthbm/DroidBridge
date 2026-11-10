package com.droidbridge.experiment.mirroring

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.droidbridge.core.protocol.MediaFraming
import com.droidbridge.protocol.media.AccessUnit
import com.droidbridge.protocol.media.H264ParameterSets
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One surface-fed H.264 encoder for one encode geometry (ADR-027).
 *
 * The codec is fed by a `Surface` the `VirtualDisplay` renders into, so no pixel buffer is allocated
 * and no colour conversion happens in app code. Every output buffer is already encoded; the only
 * thing this class does to it is the framing conversion that ADR-027 puts on the producer, plus
 * removing parameter sets that `mirror.format` already carried.
 *
 * ## One instance per geometry
 *
 * An encoder cannot be re-configured after `start()`, so a rotation or a different capture aspect is
 * a *new* encoder. The service owns that decision; this class refuses to pretend a geometry change is
 * a resize.
 *
 * ## Where the parameter sets come from
 *
 * Usually `csd-0`/`csd-1` in the output format, which is what the S24 Ultra reports. Some encoders
 * instead repeat SPS/PPS in-band, so if the codec-specific data is missing the parameter sets are
 * taken from the first keyframe that carries them. Both paths end in the same
 * [H264ParameterSets], which refuses start codes — the framing mistake that would otherwise reach
 * the Mac.
 */
class MirrorEncoder(
    val width: Int,
    val height: Int,
    private val frameRate: Int = 30,
    private val bitrate: Int = 4_000_000,
    private val onFormat: (H264ParameterSets) -> Unit,
    private val onAccessUnit: (AccessUnit) -> Unit,
    private val onError: (String) -> Unit,
) {

    companion object {
        private const val TAG = "P08Encoder"
        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val DEQUEUE_TIMEOUT_US = 10_000L

        /** Short GOP: a receiver that flushes recovers within ~2 s even without a keyframe request. */
        private const val I_FRAME_INTERVAL_SECONDS = 2

        /** A still screen must keep producing frames, or the viewer looks frozen. */
        private const val REPEAT_FRAME_AFTER_US = 250_000L
    }

    private var codec: MediaCodec? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private val running = AtomicBoolean(false)

    @Volatile
    private var parameterSets: H264ParameterSets? = null

    /** The surface the VirtualDisplay renders into. Valid after [start]. */
    @Volatile
    var inputSurface: Surface? = null
        private set

    /** Encoder name as reported by the platform, for the evidence log. */
    @Volatile
    var codecName: String = "unknown"
        private set

    /**
     * Starts the codec and its drain loop.
     *
     * @throws Exception from `configure`/`start`. The caller must not leave a half-built pipeline
     *   behind: a `MediaCodec` that was configured but never started still holds a hardware instance.
     */
    fun start() {
        val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SECONDS)
            // No reordering: the receiver assumes decode order is display order.
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, REPEAT_FRAME_AFTER_US)
            // PROFILE and LEVEL must be set together on this encoder family: profile alone fails
            // configure() with CodecException 0x80001001 and names no key (measured in P01).
            setInteger(
                MediaFormat.KEY_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
            )
            setInteger(MediaFormat.KEY_LEVEL, levelFor(width, height))
        }

        val created = MediaCodec.createEncoderByType(MIME)
        try {
            created.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codecName = created.name
            inputSurface = created.createInputSurface()
            created.start()
        } catch (e: Exception) {
            runCatching { created.release() }
            inputSurface = null
            throw e
        }

        codec = created
        running.set(true)

        val drainThread = HandlerThread("p08-encoder-${width}x$height").also { it.start() }
        thread = drainThread
        val drainHandler = Handler(drainThread.looper)
        handler = drainHandler
        drainHandler.post { drain(created) }

        Log.i(TAG, "started ${width}x$height@$frameRate name=$codecName bitrate=$bitrate")
    }

    /** Asks the encoder for an IDR at the next opportunity. */
    fun requestSyncFrame() {
        val codec = codec ?: return
        try {
            codec.setParameters(
                Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) },
            )
        } catch (e: IllegalStateException) {
            // The codec is stopping; a missing keyframe request during teardown is not an error.
            Log.w(TAG, "sync frame request ignored: ${e.javaClass.simpleName}")
        }
    }

    /** Stops the drain loop, then releases the codec and the surface. Idempotent. */
    fun stop() {
        running.set(false)
        handler?.removeCallbacksAndMessages(null)
        thread?.quitSafely()
        thread = null
        handler = null

        val toRelease = codec
        codec = null
        inputSurface?.let { runCatching { it.release() } }
        inputSurface = null
        if (toRelease != null) {
            runCatching { toRelease.stop() }
            runCatching { toRelease.release() }
        }
    }

    private fun drain(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()

        while (running.get()) {
            try {
                when (val index = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        readParameterSets(codec)
                    }

                    else -> if (index >= 0) {
                        val buffer = codec.getOutputBuffer(index)
                        if (buffer != null && info.size > 0) {
                            handleOutput(buffer, info)
                        }
                        codec.releaseOutputBuffer(index, false)
                    }
                }
            } catch (e: IllegalStateException) {
                if (running.get()) onError("encoder stopped unexpectedly: ${e.message}")
                return
            }
        }
    }

    private fun readParameterSets(codec: MediaCodec) {
        val out = runCatching { codec.outputFormat }.getOrNull() ?: return
        val sps = readCodecSpecificData(out, "csd-0")
        val pps = readCodecSpecificData(out, "csd-1")
        val fromCsd = if (sps != null && pps != null) {
            runCatching { H264ParameterSets.fromCodecSpecificData(sps, pps) }.getOrElse { e ->
                // Malformed codec-specific data is reported, then treated as absent: the in-band
                // path is a real fallback and produces a valid format, so refusing the stream here
                // would fail a session that could still work.
                Log.w(TAG, "codec-specific data rejected: ${e.javaClass.simpleName}: ${e.message}")
                null
            }
        } else {
            null
        }

        if (fromCsd != null) {
            publishParameterSets(fromCsd, "csd")
        } else {
            // Some devices emit SPS/PPS in-band and report no codec-specific data. The first
            // keyframe then carries them; the frames before it cannot be decoded anyway.
            Log.i(TAG, "no usable codec-specific data; waiting for in-band sets")
        }
    }

    private fun readCodecSpecificData(format: MediaFormat, key: String): ByteArray? {
        if (!format.containsKey(key)) return null
        val buffer = runCatching { format.getByteBuffer(key) }.getOrNull() ?: return null
        return copyOf(buffer)
    }

    private fun handleOutput(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        val bytes = ByteArray(info.size)
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        buffer.get(bytes)

        // If the codec-specific data never arrived, the in-band parameter sets are the only source.
        // They are read here before the conversion drops them from the payload.
        if (parameterSets == null) {
            val inBand = runCatching {
                val units = MediaFraming.splitNalUnits(bytes)
                H264ParameterSets.fromBareNalUnits(
                    units.first { it.isNotEmpty() && (it[0].toInt() and 0x1F) == 7 },
                    units.first { it.isNotEmpty() && (it[0].toInt() and 0x1F) == 8 },
                )
            }.getOrNull()
            if (inBand != null) publishParameterSets(inBand, "in-band")
        }

        if (parameterSets == null) return

        val accessUnit = runCatching {
            MediaFraming.toAvcc(bytes, info.presentationTimeUs, dropParameterSets = true)
        }.getOrElse { e ->
            // A parameter-set-only buffer is expected at the start of a stream and is not an error:
            // those units were sent in mirror.format instead.
            if (e.message?.contains("only parameter sets") != true) {
                onError("framing failed: ${e.javaClass.simpleName}: ${e.message}")
            }
            return
        }

        onAccessUnit(accessUnit)
    }

    private fun publishParameterSets(sets: H264ParameterSets, source: String) {
        if (parameterSets != null) return
        parameterSets = sets
        Log.i(TAG, "parameter sets from $source sps=${sets.sps.size} pps=${sets.pps.size}")
        onFormat(sets)
    }

    private fun copyOf(buffer: ByteBuffer): ByteArray {
        val copy = buffer.duplicate()
        val bytes = ByteArray(copy.remaining())
        copy.get(bytes)
        return bytes
    }

    private fun levelFor(width: Int, height: Int): Int =
        if (width * height > 1280 * 720) {
            MediaCodecInfo.CodecProfileLevel.AVCLevel4
        } else {
            MediaCodecInfo.CodecProfileLevel.AVCLevel31
        }
}
