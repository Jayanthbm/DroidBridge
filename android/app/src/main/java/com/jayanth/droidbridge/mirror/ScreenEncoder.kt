package com.jayanth.droidbridge.mirror

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.Display
import android.view.Surface
import java.nio.ByteBuffer

data class EncodedVideoFrame(
    val width: Int,
    val height: Int,
    val rotation: Int,
    val keyframe: Boolean,
    val presentationTimeUs: Long,
    val data: ByteArray,
)

/**
 * Owns the MediaProjection virtual display and the H.264 encoder. All codec
 * work happens on a dedicated looper thread. Rotation rebuilds the encoder and
 * resizes the virtual display; the MediaProjection consent token is reused.
 */
class ScreenEncoder(
    private val context: Context,
    private val projection: MediaProjection,
    private val onFrame: (EncodedVideoFrame) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val thread = HandlerThread("DroidBridge-Encoder").apply { start() }
    private val handler = Handler(thread.looper)
    private val displayManager = context.getSystemService(DisplayManager::class.java)

    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var codecConfig: ByteArray? = null
    private var outputWidth = 0
    private var outputHeight = 0
    private var rotation = 0
    private var density = 0
    private var stopped = false
    private var displayListener: DisplayManager.DisplayListener? = null

    fun start() {
        handler.post {
            if (stopped) return@post
            val display = defaultDisplay() ?: run {
                onError("The phone display is unavailable.")
                return@post
            }
            density = context.resources.displayMetrics.densityDpi
            rotation = display.rotation
            val (width, height) = dimensionsFor(display, rotation)
            try {
                configureCodec(width, height)
                virtualDisplay = projection.createVirtualDisplay(
                    "DroidBridge",
                    width,
                    height,
                    density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    inputSurface,
                    null,
                    handler,
                )
                watchRotation()
            } catch (error: Exception) {
                onError(error.message ?: "Could not start the screen encoder.")
                stopInternal()
            }
        }
    }

    fun requestKeyFrame() {
        handler.post {
            val codec = codec ?: return@post
            runCatching {
                codec.setParameters(
                    Bundle().apply {
                        putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                    },
                )
            }
        }
    }

    fun stop() {
        stopped = true
        handler.post { stopInternal() }
    }

    private fun stopInternal() {
        if (stopped.not()) stopped = true
        displayListener?.let { displayManager.unregisterDisplayListener(it) }
        displayListener = null
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        runCatching { inputSurface?.release() }
        inputSurface = null
        codecConfig = null
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
    }

    private fun configureCodec(width: Int, height: Int) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateFor(width, height))
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.setCallback(codecCallback, handler)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = encoder.createInputSurface()
        encoder.start()
        codec = encoder
        outputWidth = width
        outputHeight = height
        codecConfig = null
    }

    private fun rebuildForRotation(newRotation: Int) {
        if (newRotation == rotation || stopped) return
        val display = defaultDisplay() ?: return
        rotation = newRotation
        val (width, height) = dimensionsFor(display, newRotation)
        try {
            virtualDisplay?.surface = null
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            codec = null
            runCatching { inputSurface?.release() }
            inputSurface = null
            codecConfig = null
            configureCodec(width, height)
            virtualDisplay?.resize(width, height, density)
            virtualDisplay?.surface = inputSurface
        } catch (error: Exception) {
            onError(error.message ?: "Could not rotate the screen encoder.")
            stopInternal()
        }
    }

    private fun watchRotation() {
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                if (displayId != Display.DEFAULT_DISPLAY) return
                val newRotation = defaultDisplay()?.rotation ?: return
                handler.post { rebuildForRotation(newRotation) }
            }
        }
        displayListener = listener
        displayManager.registerDisplayListener(listener, handler)
    }

    private val codecCallback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

        override fun onOutputBufferAvailable(
            codec: MediaCodec,
            index: Int,
            info: MediaCodec.BufferInfo,
        ) {
            try {
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) return
                if (info.size <= 0) return
                val buffer = codec.getOutputBuffer(index) ?: return
                val data = ByteArray(info.size)
                buffer.position(info.offset)
                buffer.limit(info.offset + info.size)
                buffer.get(data)
                val keyframe = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                val accessUnit = if (keyframe) prependConfig(data) else data
                onFrame(
                    EncodedVideoFrame(
                        width = outputWidth,
                        height = outputHeight,
                        rotation = rotationDegrees(rotation),
                        keyframe = keyframe,
                        presentationTimeUs = info.presentationTimeUs,
                        data = accessUnit,
                    ),
                )
            } catch (error: Exception) {
                onError(error.message ?: "The video encoder failed.")
            } finally {
                runCatching { codec.releaseOutputBuffer(index, false) }
            }
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            outputWidth = format.getInteger(MediaFormat.KEY_WIDTH, outputWidth)
            outputHeight = format.getInteger(MediaFormat.KEY_HEIGHT, outputHeight)
            val parts = listOf("csd-0", "csd-1").mapNotNull { key ->
                format.getByteBuffer(key)?.let { buffer ->
                    val bytes = ByteArray(buffer.remaining())
                    buffer.duplicate().get(bytes)
                    ensureStartCode(bytes)
                }
            }
            if (parts.isNotEmpty()) codecConfig = parts.reduce { a, b -> a + b }
        }

        override fun onError(codec: MediaCodec, error: MediaCodec.CodecException) {
            onError(error.message ?: "The H.264 encoder stopped.")
        }
    }

    private fun prependConfig(accessUnit: ByteArray): ByteArray {
        val config = codecConfig ?: return accessUnit
        if (hasNalType(accessUnit, 7) && hasNalType(accessUnit, 8)) return accessUnit
        return config + accessUnit
    }

    private fun defaultDisplay(): Display? =
        displayManager.getDisplay(Display.DEFAULT_DISPLAY)

    private fun dimensionsFor(display: Display, rotation: Int): Pair<Int, Int> {
        val mode = display.mode
        val naturalWidth = mode.physicalWidth
        val naturalHeight = mode.physicalHeight
        return if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            naturalHeight to naturalWidth
        } else {
            naturalWidth to naturalHeight
        }
    }

    private fun rotationDegrees(rotation: Int): Int = when (rotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }

    private fun bitrateFor(width: Int, height: Int): Int {
        val pixels = width.toLong() * height.toLong()
        return (pixels * 4).coerceIn(4_000_000L, 12_000_000L).toInt()
    }

    private fun ensureStartCode(data: ByteArray): ByteArray {
        if (data.size >= 4 && data[0] == 0.toByte() && data[1] == 0.toByte() &&
            data[2] == 0.toByte() && data[3] == 1.toByte()
        ) {
            return data
        }
        if (data.size >= 3 && data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 1.toByte()) {
            return data
        }
        return byteArrayOf(0, 0, 0, 1) + data
    }

    private fun hasNalType(data: ByteArray, type: Int): Boolean {
        var index = 0
        while (index + 4 < data.size) {
            if (data[index] == 0.toByte() && data[index + 1] == 0.toByte() && data[index + 2] == 1.toByte()) {
                if ((data[index + 3].toInt() and 0x1f) == type) return true
                index += 3
            } else {
                index++
            }
        }
        return false
    }
}

private fun MediaFormat.getInteger(key: String, fallback: Int): Int =
    if (containsKey(key)) getInteger(key) else fallback
