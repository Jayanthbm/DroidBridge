package com.droidbridge.experiment.mediaprojection

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.droidbridge.core.protocol.MediaFraming
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * P01: capture the screen with user consent and encode it to H.264 from the capture surface.
 *
 * The pipeline is the one ADR-027 commits to, and there is deliberately no alternative path:
 *
 *   MediaProjection → VirtualDisplay(surface) → MediaCodec H.264 → Annex-B→AVCC → counters
 *
 * The encoder is **surface-fed** (`configure(format, null, null, CONFIGURE_FLAG_ENCODE)` →
 * `createInputSurface()` → `createVirtualDisplay()`), so no pixel buffer is ever allocated and no
 * colour conversion happens in app code. Every frame that reaches the app has already been
 * encoded, which is why the framing conversion is the only thing this prototype can get wrong.
 *
 * What it measures, because P01 asks for measured facts:
 * - frames encoded, bytes produced, keyframes seen, and the first SPS/PPS;
 * - whether frames keep arriving on a still screen (KEY_REPEAT_PREVIOUS_FRAME_AFTER);
 * - the output format the encoder actually reports, versus what was requested;
 * - whether `flush()` without `start()` stops production (the documented async-mode trap).
 *
 * It transmits nothing. There is no socket in this module.
 */
class ProjectionService : Service() {

    companion object {
        private const val TAG = "P01Projection"
        private const val CHANNEL_ID = "p01_projection"
        private const val NOTIFICATION_ID = 4101

        const val ACTION_START = "com.droidbridge.experiment.mediaprojection.START"
        const val ACTION_STOP = "com.droidbridge.experiment.mediaprojection.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"

        /** Shared with the UI so the screen can show live counters without binding. */
        val framesEncoded = AtomicInteger()
        val bytesEncoded = AtomicLong()
        val keyframesSeen = AtomicInteger()
        val isRunning = AtomicBoolean(false)
        val lastEvent = java.util.concurrent.atomic.AtomicReference("idle")
        val probeReport = java.util.concurrent.atomic.AtomicReference("")

        fun start(context: Context, resultCode: Int, resultData: Intent) {
            val intent = Intent(context, ProjectionService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, ProjectionService::class.java).apply { action = ACTION_STOP },
            )
        }
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: MediaCodec? = null
    private var encoderThread: HandlerThread? = null
    private var encoderHandler: Handler? = null

    private val requestedWidth = 1280
    private val requestedHeight = 720
    private val requestedFrameRate = 30
    private val requestedBitrate = 4_000_000

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // The system, the user, or the screen-sharing chip stopped the session. Everything
            // must be released here, or the next start fails with a SecurityException (R-04).
            Log.i(TAG, "MediaProjection.Callback.onStop fired")
            lastEvent.set("projection stopped by system or user")
            teardown(reason = "onStop")
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                @Suppress("DEPRECATION")
                val resultData: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)
                if (resultData == null) {
                    lastEvent.set("start failed: no consent data")
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForegroundWithNotification()
                beginSession(resultCode, resultData)
            }

            ACTION_STOP -> {
                lastEvent.set("stop requested by user")
                teardown(reason = "user stop")
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundWithNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "P01 projection experiment",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("P01 projection running")
            .setContentText("Capturing and encoding locally. Nothing is transmitted.")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()

        // The type must be declared at start time, not only in the manifest. minSdk is 31, so
        // the type constant is always available and no version guard is needed.
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )
    }

    private fun beginSession(resultCode: Int, resultData: Intent) {
        try {
            val manager = getSystemService(MediaProjectionManager::class.java)
            // One getMediaProjection() per consent intent (R-04). Reusing the token throws.
            val mp = manager.getMediaProjection(resultCode, resultData)
            if (mp == null) {
                lastEvent.set("getMediaProjection returned null")
                stopSelf()
                return
            }
            projection = mp
            mp.registerCallback(projectionCallback, Handler(mainLooper))

            val report = EncoderProbe.probe()
            probeReport.set(report.toReportText())

            val format = MediaFormat.createVideoFormat(EncoderProbe.MIME, requestedWidth, requestedHeight).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
                )
                setInteger(MediaFormat.KEY_BIT_RATE, requestedBitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, requestedFrameRate)
                // Short GOP so a receiver that flushes can recover quickly. ADR-027.
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                // No reordering: a receiver that assumes decode order equals display order is
                // only correct when there are no B-frames.
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                // A still screen must keep producing frames, or the viewer looks frozen.
                setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 250_000L)
                // PROFILE and LEVEL must be set together on this encoder.
                //
                // Measured, not assumed: setting KEY_PROFILE alone makes configure() fail with
                // CodecException 0x80001001 (OMX_ErrorUndefined), and the stack names no key.
                // A bisect over the format keys showed profile-alone failing while
                // profile+level succeeded, so the encoder requires a level whenever a profile is
                // pinned. Level 3.1 is the correct level for 1280x720@30 and is what the encoder
                // reports supporting.
                setInteger(
                    MediaFormat.KEY_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
                )
                setInteger(
                    MediaFormat.KEY_LEVEL,
                    MediaCodecInfo.CodecProfileLevel.AVCLevel31,
                )
            }

            val codec = MediaCodec.createEncoderByType(EncoderProbe.MIME)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = codec.createInputSurface()
            codec.start()
            encoder = codec

            framesEncoded.set(0)
            bytesEncoded.set(0)
            keyframesSeen.set(0)
            isRunning.set(true)
            lastEvent.set("session started ${requestedWidth}x$requestedHeight@$requestedFrameRate")

            encoderThread = HandlerThread("p01-encoder").also { it.start() }
            encoderHandler = Handler(encoderThread!!.looper)
            encoderHandler?.post { drainEncoder(codec) }

            virtualDisplay = mp.createVirtualDisplay(
                "p01-capture",
                requestedWidth,
                requestedHeight,
                resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface,
                null,
                null,
            )

            Log.i(TAG, "session started; encoder output format after start: ${codec.outputFormat}")
        } catch (e: Exception) {
            // A SecurityException here is the expected result of reusing a consent token, and it
            // is recorded rather than swallowed (R-04).
            Log.e(TAG, "beginSession failed", e)
            lastEvent.set("beginSession failed: ${e.javaClass.simpleName}: ${e.message}")
            teardown(reason = "beginSession failed")
            stopSelf()
        }
    }

    /**
     * Drains the encoder. Every buffer is converted through the shared protocol module, so a
     * framing bug fails here rather than on the Mac.
     */
    private fun drainEncoder(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var sawFormat = false

        while (isRunning.get()) {
            try {
                val index = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val out = codec.outputFormat
                        sawFormat = true
                        Log.i(TAG, "encoder output format: $out")
                        lastEvent.set("output format: ${out.getString(MediaFormat.KEY_MIME)} ${out.getInteger(MediaFormat.KEY_WIDTH)}x${out.getInteger(MediaFormat.KEY_HEIGHT)}")
                    }

                    index >= 0 -> {
                        val buffer = codec.getOutputBuffer(index)
                        if (buffer != null && info.size > 0) {
                            val bytes = ByteArray(info.size)
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            buffer.get(bytes)

                            // The conversion is the point of the prototype: it proves the
                            // shared framing rules against real encoder output.
                            val accessUnit = runCatching {
                                MediaFraming.toAvcc(bytes, info.presentationTimeUs)
                            }.getOrElse { e ->
                                lastEvent.set("framing failed: ${e.javaClass.simpleName}: ${e.message}")
                                null
                            }

                            if (accessUnit != null) {
                                framesEncoded.incrementAndGet()
                                bytesEncoded.addAndGet(accessUnit.payload.size.toLong())
                                if (accessUnit.isKeyframe) keyframesSeen.incrementAndGet()
                            }
                        }
                        codec.releaseOutputBuffer(index, false)
                    }
                }
                if (!sawFormat && info.size > 0) sawFormat = true
            } catch (e: IllegalStateException) {
                // Expected during teardown; anything else is logged.
                if (isRunning.get()) Log.w(TAG, "drain loop IllegalStateException", e)
                return
            }
        }
    }

    private fun teardown(reason: String) {
        isRunning.set(false)
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
        encoder = null
        runCatching { projection?.unregisterCallback(projectionCallback) }
        runCatching { projection?.stop() }
        projection = null
        encoderThread?.quitSafely()
        encoderThread = null
        encoderHandler = null
        Log.i(TAG, "teardown: $reason; frames=${framesEncoded.get()} bytes=${bytesEncoded.get()} keyframes=${keyframesSeen.get()}")
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        teardown(reason = "onDestroy")
        super.onDestroy()
    }
}
