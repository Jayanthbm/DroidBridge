package com.droidbridge.experiment.mediaprojection

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.droidbridge.core.protocol.MediaFraming
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * P01: capture the screen with user consent and encode it to H.264 from the capture surface.
 *
 * The pipeline is the one ADR-027 commits to, and there is deliberately no alternative product
 * path:
 *
 *   MediaProjection → VirtualDisplay(surface) → MediaCodec H.264 → Annex-B→AVCC → counters
 *
 * The encoder is **surface-fed** (`configure(format, null, null, CONFIGURE_FLAG_ENCODE)` →
 * `createInputSurface()` → `createVirtualDisplay()`), so no pixel buffer is ever allocated and no
 * colour conversion happens in app code. Every frame that reaches the app has already been
 * encoded, which is why the framing conversion is the only thing the encoder path can get wrong.
 *
 * There is a second, **measurement-only** path used by the secure-content test:
 *
 *   MediaProjection → VirtualDisplay(ImageReader) → sampled luminance
 *
 * It does not encode and it is never used by production code. It exists because "does the platform
 * deliver protected pixels to a capture consumer?" cannot be answered by counting encoded bytes:
 * a black frame and a frozen frame have the same shape. Sampling the consumer surface answers it
 * directly. Nothing here attempts to bypass FLAG_SECURE; the test asserts that it is honoured.
 *
 * What it measures, because P01 asks for measured facts:
 * - frames encoded, bytes produced, keyframes seen, and the first SPS/PPS;
 * - the output format the encoder actually reports (profile, level, bitrate mode), versus the
 *   requested format;
 * - frame and keyframe gaps, so "30 fps requested" is not confused with "30 fps delivered";
 * - `onCapturedContentResize()` values and whether the pipeline rebuilds at the new size;
 * - `onCapturedContentVisibilityChanged()` events;
 * - whether frames keep arriving on a still screen (KEY_REPEAT_PREVIOUS_FRAME_AFTER);
 * - the exact stop reason, so a user stop is not confused with a system stop.
 *
 * It transmits nothing. There is no socket in this module.
 */
class ProjectionService : Service() {

    companion object {
        private const val TAG = "P01Projection"
        private const val CHANNEL_ID = "p01_projection"
        private const val NOTIFICATION_ID = 4101
        private const val STATS_INTERVAL_MS = 5_000L
        private const val PIXEL_SAMPLE_INTERVAL_MS = 250L

        const val ACTION_START = "com.droidbridge.experiment.mediaprojection.START"
        const val ACTION_STOP = "com.droidbridge.experiment.mediaprojection.STOP"
        const val ACTION_TEST_REUSE = "com.droidbridge.experiment.mediaprojection.TEST_REUSE"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val EXTRA_MODE = "mode"
        const val MODE_ENCODE = "encode"
        const val MODE_PIXEL = "pixel"

        /** Shared with the UI so the screen can show live counters without binding. */
        val framesEncoded = AtomicInteger()
        val bytesEncoded = AtomicLong()
        val keyframesSeen = AtomicInteger()
        val isRunning = AtomicBoolean()
        val lastEvent = AtomicReference("idle")
        val probeReport = AtomicReference("")

        /** Measured output-format facts. -1 means the encoder did not report the key. */
        val outputProfile = AtomicInteger(-1)
        val outputLevel = AtomicInteger(-1)
        val outputBitrateMode = AtomicInteger(-1)
        val encodedWidth = AtomicInteger(0)
        val encodedHeight = AtomicInteger(0)
        val maxFrameGapMs = AtomicLong(0)
        val maxKeyframeGapMs = AtomicLong(0)

        /** Rotation / content-change evidence. */
        val resizeEvents = AtomicInteger(0)
        val lastResize = AtomicReference("none")
        val visibilityChanges = AtomicInteger(0)
        val lastVisibility = AtomicReference("none")

        /** Lifecycle evidence. */
        val stopReason = AtomicReference("none")
        val sessionCount = AtomicInteger(0)
        val lastTokenReuse = AtomicReference("not attempted")

        /** Secure-content pixel probe evidence. */
        val pixelMean = AtomicInteger(0)
        val pixelMin = AtomicInteger(255)
        val pixelMax = AtomicInteger(0)
        val pixelBlackSamples = AtomicInteger(0)
        val pixelBrightSamples = AtomicInteger(0)
        val pixelSamples = AtomicInteger(0)

        val mode = AtomicReference(MODE_ENCODE)

        fun start(context: Context, resultCode: Int, resultData: Intent, mode: String = MODE_ENCODE) {
            val intent = Intent(context, ProjectionService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
                putExtra(EXTRA_MODE, mode)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, ProjectionService::class.java).apply { action = ACTION_STOP },
            )
        }

        /**
         * Runs the consent-token reuse probe from inside a foreground service of type
         * `mediaProjection`.
         *
         * This placement is the test: `getMediaProjection` refuses to run at all unless such a
         * service is in the foreground (measured on the S24 Ultra), so an activity-side attempt
         * reports the FGS precondition instead of the one-shot rule. Inside the service the
         * precondition is satisfied and the reuse itself is what fails.
         */
        fun testTokenReuse(context: Context, resultCode: Int, resultData: Intent) {
            val intent = Intent(context, ProjectionService::class.java).apply {
                action = ACTION_TEST_REUSE
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
            }
            // startService, not startForegroundService: this probe must run inside an active
            // session. A mediaProjection FGS cannot be started without an active projection
            // grant (measured: SecurityException from validateForegroundServiceType), so a
            // standalone probe service is not possible.
            context.startService(intent)
        }
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: MediaCodec? = null
    private var encoderThread: HandlerThread? = null
    private var encoderHandler: Handler? = null
    private var imageReader: ImageReader? = null
    private var pixelThread: HandlerThread? = null
    private var pixelHandler: Handler? = null

    private val mainHandler by lazy { Handler(mainLooper) }

    /**
     * Incremented whenever a pipeline is created or destroyed. A drain loop is bound to one
     * generation and exits when it changes, so a rebuild can never leave two loops reading the
     * same (or a released) codec.
     */
    private val generation = AtomicInteger(0)
    private val tearingDown = AtomicBoolean(false)
    private val sessionStartMs = AtomicLong(0)
    private val lastFrameUs = AtomicLong(0)
    private val lastKeyframeUs = AtomicLong(0)

    /** Retained only for the one-shot token-reuse probe; never replayed to start a session. */
    private var consentResultCode: Int = 0
    private var consentResultData: Intent? = null

    private val requestedWidth = 1280
    private val requestedHeight = 720
    private val requestedFrameRate = 30
    private val requestedBitrate = 4_000_000

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // The system, the user, or the screen-sharing chip stopped the session. Everything
            // must be released here, or the next start fails with a SecurityException (R-04).
            Log.i(TAG, "MediaProjection.Callback.onStop fired")
            stopReason.set("system onStop (screen-sharing chip / system stop / lock)")
            lastEvent.set("projection stopped by system")
            teardown(reason = "onStop")
            stopSelf()
        }

        /**
         * API 34+. The platform reports the captured content's new size after a rotation or a
         * selected-app change. A fixed VirtualDisplay would keep encoding a letterboxed, stale
         * aspect ratio, so the pipeline is rebuilt at the new even size.
         */
        override fun onCapturedContentResize(width: Int, height: Int) {
            resizeEvents.incrementAndGet()
            val duplicate = lastResize.get() == "${width}x$height"
            lastResize.set("${width}x$height")
            Log.i(TAG, "onCapturedContentResize ${width}x$height (duplicate=$duplicate)")
            if (mode.get() == MODE_ENCODE && isRunning.get() && !duplicate) {
                // Android 17 rejects a second createVirtualDisplay() on the same MediaProjection
                // instance with:
                //   SecurityException: Don't take multiple captures by invoking
                //   MediaProjection#createVirtualDisplay multiple times on the same instance.
                //
                // So rebuilding the pipeline on rotation is illegal. The only supported change is
                // resize() on the existing VirtualDisplay; the encoder's input surface keeps its
                // original geometry, so the platform scales the new aspect into it (letterboxing
                // is a property of the receiver, not of this encoder). Changing the encoded
                // resolution itself needs a new session and a fresh consent, which is a product
                // decision for Phase 4, not a P01 defect.
                val display = virtualDisplay
                if (display != null) {
                    try {
                        display.resize(width, height, resources.displayMetrics.densityDpi)
                        lastEvent.set("display resized to ${width}x$height; encoder stays ${encodedWidth.get()}x${encodedHeight.get()}")
                        Log.i(TAG, "VirtualDisplay.resize(${width}x$height) accepted; encoder surface unchanged")
                    } catch (e: Exception) {
                        lastEvent.set("resize failed: ${e.javaClass.simpleName}: ${e.message}")
                        Log.w(TAG, "VirtualDisplay.resize failed", e)
                    }
                }
            }
        }

        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
            visibilityChanges.incrementAndGet()
            lastVisibility.set(if (isVisible) "visible" else "not visible")
            Log.i(TAG, "onCapturedContentVisibilityChanged isVisible=$isVisible")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                @Suppress("DEPRECATION")
                val resultData: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)
                val requestedMode = intent.getStringExtra(EXTRA_MODE) ?: MODE_ENCODE
                if (resultData == null) {
                    lastEvent.set("start failed: no consent data")
                    stopSelf()
                    return START_NOT_STICKY
                }
                mode.set(requestedMode)
                tearingDown.set(false)
                sessionCount.incrementAndGet()
                resetSessionMetrics()
                startForegroundWithNotification(requestedMode)
                if (requestedMode == MODE_PIXEL) {
                    beginPixelProbe(resultCode, resultData)
                } else {
                    beginEncodeSession(resultCode, resultData)
                }
            }

            ACTION_TEST_REUSE -> {
                // The probe only means anything inside a live session: that is the only state in
                // which the mediaProjection FGS precondition is satisfied, so the failure that
                // remains to be observed is the one-shot token rule itself.
                val data = consentResultData
                val result = if (!isRunning.get() || projection == null) {
                    "no active projection session: FGS type precondition cannot be met " +
                        "(platform refuses a mediaProjection FGS without an active grant)"
                } else if (data == null) {
                    "no token captured for this session"
                } else {
                    try {
                        val manager = getSystemService(MediaProjectionManager::class.java)
                        val reused = manager.getMediaProjection(consentResultCode, data)
                        if (reused == null) {
                            "getMediaProjection RETURNED null (no exception)"
                        } else {
                            // Measured on the S24 Ultra: replaying the token while the session is
                            // live returns the SAME projection instance instead of throwing the
                            // documented one-shot SecurityException. Calling stop() on it ends the
                            // live session, so this probe must not touch it. The one-shot rule is
                            // enforced by createVirtualDisplay instead (see the rotation
                            // experiment). Product code must never replay a token.
                            "getMediaProjection RETURNED a projection " +
                                "(same_instance=${reused === projection}); " +
                                "one-shot rule is enforced at createVirtualDisplay, not here"
                        }
                    } catch (e: SecurityException) {
                        "SecurityException: ${e.message}"
                    } catch (e: Exception) {
                        "${e.javaClass.simpleName}: ${e.message}"
                    }
                }
                lastTokenReuse.set(result)
                lastEvent.set("token reuse probe: $result")
                Log.i(TAG, "TOKEN_REUSE result=$result")
                // With no active session this is a standalone started service; stop it so it does
                // not linger. With an active session, the service must keep running.
                if (!isRunning.get()) stopSelf()
                return START_NOT_STICKY
            }

            ACTION_STOP -> {
                stopReason.set("user stop")
                lastEvent.set("stop requested by user")
                teardown(reason = "user stop")
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun resetSessionMetrics() {
        framesEncoded.set(0)
        bytesEncoded.set(0)
        keyframesSeen.set(0)
        outputProfile.set(-1)
        outputLevel.set(-1)
        outputBitrateMode.set(-1)
        encodedWidth.set(0)
        encodedHeight.set(0)
        maxFrameGapMs.set(0)
        maxKeyframeGapMs.set(0)
        resizeEvents.set(0)
        lastResize.set("none")
        visibilityChanges.set(0)
        lastVisibility.set("none")
        stopReason.set("active")
        pixelMean.set(0)
        pixelMin.set(255)
        pixelMax.set(0)
        pixelBlackSamples.set(0)
        pixelBrightSamples.set(0)
        pixelSamples.set(0)
        lastFrameUs.set(0)
        lastKeyframeUs.set(0)
        sessionStartMs.set(SystemClock.elapsedRealtime())
    }

    private fun startForegroundWithNotification(requestedMode: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "P01 projection experiment",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val what = if (requestedMode == MODE_PIXEL) {
            "Sampling captured luminance (secure-content test). Nothing is transmitted."
        } else {
            "Capturing and encoding locally. Nothing is transmitted."
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("P01 projection running")
            .setContentText(what)
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

    private fun createProjection(resultCode: Int, resultData: Intent): MediaProjection? {
        val manager = getSystemService(MediaProjectionManager::class.java)
        // One getMediaProjection() per consent intent (R-04). Reusing the token throws.
        val mp = manager.getMediaProjection(resultCode, resultData)
        if (mp == null) {
            lastEvent.set("getMediaProjection returned null")
            return null
        }
        projection = mp
        mp.registerCallback(projectionCallback, mainHandler)
        return mp
    }

    private fun beginEncodeSession(resultCode: Int, resultData: Intent) {
        try {
            consentResultCode = resultCode
            consentResultData = resultData
            val mp = createProjection(resultCode, resultData)
            if (mp == null) {
                stopSelf()
                return
            }

            probeReport.set(EncoderProbe.probe().toReportText())
            // Set before the pipeline starts: the drain thread binds to this flag and would exit
            // on its first iteration if it observed false. The S24 Ultra run caught exactly that
            // race as "session running, zero frames".
            isRunning.set(true)
            startEncoderPipeline(mp, requestedWidth, requestedHeight)
            lastEvent.set("session started ${requestedWidth}x$requestedHeight@$requestedFrameRate")
            Log.i(TAG, "session started; encoder output format after start: ${encoder?.outputFormat}")
            mainHandler.postDelayed(statsRunnable, STATS_INTERVAL_MS)
        } catch (e: Exception) {
            // A SecurityException here is the expected result of reusing a consent token, and it
            // is recorded rather than swallowed (R-04).
            Log.e(TAG, "beginEncodeSession failed", e)
            lastEvent.set("beginEncodeSession failed: ${e.javaClass.simpleName}: ${e.message}")
            teardown(reason = "beginEncodeSession failed")
            stopSelf()
        }
    }

    /**
     * Creates the encoder and the VirtualDisplay. Called once at session start and again when the
     * captured content resizes, so a rotation does not leave the pipeline at a stale aspect ratio.
     */
    private fun startEncoderPipeline(mp: MediaProjection, width: Int, height: Int) {
        val format = buildEncoderFormat(width, height)
        val codec = MediaCodec.createEncoderByType(EncoderProbe.MIME)
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            runCatching { codec.release() }
            throw e
        }
        val inputSurface = codec.createInputSurface()
        codec.start()

        encoder = codec
        encodedWidth.set(width)
        encodedHeight.set(height)
        val myGeneration = generation.incrementAndGet()

        val thread = HandlerThread("p01-encoder-$myGeneration").also { it.start() }
        encoderThread = thread
        val handler = Handler(thread.looper)
        encoderHandler = handler
        handler.post { drainEncoder(codec, myGeneration) }

        virtualDisplay = mp.createVirtualDisplay(
            "p01-capture-$myGeneration",
            width,
            height,
            resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface,
            null,
            null,
        )
        Log.i(
            TAG,
            "pipeline generation=$myGeneration ${width}x$height " +
                "encoder=${codec.name}",
        )
    }

    private fun buildEncoderFormat(width: Int, height: Int): MediaFormat =
        MediaFormat.createVideoFormat(EncoderProbe.MIME, width, height).apply {
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
            // Measured on the OnePlus (Android 12), and re-checked on the S24 Ultra: setting
            // KEY_PROFILE alone makes configure() fail with CodecException 0x80001001
            // (OMX_ErrorUndefined), and the stack names no key. Level 3.1 is correct for
            // 1280x720@30; when the pipeline is rebuilt at 2340x1080 for rotation, 3.1 is below
            // the level the content implies, so level 4 is used instead (measured, see report).
            setInteger(
                MediaFormat.KEY_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
            )
            setInteger(MediaFormat.KEY_LEVEL, CaptureFormat.levelFor(width, height))
        }

    /**
     * Drains the encoder. Every buffer is converted through the shared protocol module, so a
     * framing bug fails here rather than on the Mac.
     */
    private fun drainEncoder(codec: MediaCodec, myGeneration: Int) {
        val info = MediaCodec.BufferInfo()

        while (isRunning.get() && generation.get() == myGeneration) {
            try {
                val index = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val out = codec.outputFormat
                        recordOutputFormat(out)
                        Log.i(TAG, "encoder output format: $out")
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
                                noteFrame(info.presentationTimeUs, accessUnit.isKeyframe)
                                framesEncoded.incrementAndGet()
                                bytesEncoded.addAndGet(accessUnit.payload.size.toLong())
                                if (accessUnit.isKeyframe) keyframesSeen.incrementAndGet()
                            }
                        }
                        codec.releaseOutputBuffer(index, false)
                    }
                }
            } catch (e: IllegalStateException) {
                // Expected during teardown/rebuild; anything else is logged.
                if (isRunning.get() && generation.get() == myGeneration) {
                    Log.w(TAG, "drain loop IllegalStateException", e)
                }
                return
            }
        }
    }

    private fun recordOutputFormat(out: MediaFormat) {
        fun int(key: String): Int =
            if (out.containsKey(key)) runCatching { out.getInteger(key) }.getOrDefault(-1) else -1

        val profile = int(MediaFormat.KEY_PROFILE)
        val level = int(MediaFormat.KEY_LEVEL)
        val bitrateMode = int(MediaFormat.KEY_BITRATE_MODE)
        val width = int(MediaFormat.KEY_WIDTH)
        val height = int(MediaFormat.KEY_HEIGHT)
        if (profile >= 0) outputProfile.set(profile)
        if (level >= 0) outputLevel.set(level)
        if (bitrateMode >= 0) outputBitrateMode.set(bitrateMode)
        if (width > 0) encodedWidth.set(width)
        if (height > 0) encodedHeight.set(height)
        val csd0 = out.getByteBuffer("csd-0")?.remaining() ?: 0
        val csd1 = out.getByteBuffer("csd-1")?.remaining() ?: 0
        lastEvent.set(
            "output format: ${out.getString(MediaFormat.KEY_MIME)} ${width}x$height " +
                "profile=$profile level=$level bitrateMode=$bitrateMode " +
                "sps=$csd0 pps=$csd1",
        )
        Log.i(
            TAG,
            "OUTPUT_FORMAT mime=${out.getString(MediaFormat.KEY_MIME)} ${width}x$height " +
                "profile=$profile level=$level bitrateMode=$bitrateMode csd0=$csd0 csd1=$csd1 " +
                "iFrameInterval=${int(MediaFormat.KEY_I_FRAME_INTERVAL)}",
        )
    }

    private fun noteFrame(presentationTimeUs: Long, isKeyframe: Boolean) {
        if (presentationTimeUs <= 0) return
        val previous = lastFrameUs.getAndSet(presentationTimeUs)
        if (previous > 0 && presentationTimeUs > previous) {
            val gapMs = (presentationTimeUs - previous) / 1000
            if (gapMs > maxFrameGapMs.get()) maxFrameGapMs.set(gapMs)
        }
        if (isKeyframe) {
            val previousKey = lastKeyframeUs.getAndSet(presentationTimeUs)
            if (previousKey > 0 && presentationTimeUs > previousKey) {
                val gapMs = (presentationTimeUs - previousKey) / 1000
                if (gapMs > maxKeyframeGapMs.get()) maxKeyframeGapMs.set(gapMs)
            }
        }
    }

    private val statsRunnable = object : Runnable {
        override fun run() {
            if (!isRunning.get()) return
            val elapsedMs = (SystemClock.elapsedRealtime() - sessionStartMs.get()).coerceAtLeast(1)
            val elapsedSec = elapsedMs / 1000.0
            val frames = framesEncoded.get()
            val bytes = bytesEncoded.get()
            val fps = frames / elapsedSec
            val kbps = bytes * 8 / 1000.0 / elapsedSec
            val statsLine = (
                "STATS mode=${mode.get()} frames=$frames bytes=$bytes keyframes=${keyframesSeen.get()} " +
                    "fps=%.1f kbps=%.0f maxFrameGapMs=${maxFrameGapMs.get()} " +
                    "maxKeyframeGapMs=${maxKeyframeGapMs.get()} resizes=${resizeEvents.get()} " +
                    "lastResize=${lastResize.get()} visibility=${lastVisibility.get()} " +
                    "size=${encodedWidth.get()}x${encodedHeight.get()} " +
                    "profile=${outputProfile.get()} level=${outputLevel.get()} " +
                    "bitrateMode=${outputBitrateMode.get()} stopReason=${stopReason.get()}"
                ).format(fps, kbps)
            Log.i(TAG, statsLine)
            mainHandler.postDelayed(this, STATS_INTERVAL_MS)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Secure-content pixel probe (measurement-only path)
    // ---------------------------------------------------------------------------------------

    private fun beginPixelProbe(resultCode: Int, resultData: Intent) {
        try {
            val mp = createProjection(resultCode, resultData) ?: run {
                stopSelf()
                return
            }
            val reader = ImageReader.newInstance(640, 360, PixelFormat.RGBA_8888, 3)
            imageReader = reader
            virtualDisplay = mp.createVirtualDisplay(
                "p01-pixel-probe",
                640,
                360,
                resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                null,
            )
            isRunning.set(true)
            lastEvent.set("pixel probe started 640x360")
            pixelThread = HandlerThread("p01-pixel").also { it.start() }
            pixelHandler = Handler(pixelThread!!.looper)
            pixelHandler?.post(pixelRunnable)
            Log.i(TAG, "pixel probe started")
        } catch (e: Exception) {
            Log.e(TAG, "beginPixelProbe failed", e)
            lastEvent.set("pixel probe failed: ${e.javaClass.simpleName}: ${e.message}")
            teardown(reason = "pixel probe failed")
            stopSelf()
        }
    }

    private val pixelRunnable = object : Runnable {
        override fun run() {
            if (!isRunning.get() || mode.get() != MODE_PIXEL) return
            try {
                samplePixelOnce()
            } catch (e: Exception) {
                Log.w(TAG, "pixel sample failed", e)
            }
            pixelHandler?.postDelayed(this, PIXEL_SAMPLE_INTERVAL_MS)
        }
    }

    /**
     * Samples the centre 40% of the consumer surface.
     *
     * Luma is computed from RGBA. The point is not image quality: it is to tell a delivered bright
     * frame from a black frame, which is exactly what FLAG_SECURE protection looks like.
     */
    private fun samplePixelOnce() {
        val reader = imageReader ?: return
        val image = reader.acquireLatestImage() ?: return
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val width = image.width
            val height = image.height
            val x0 = width * 3 / 10
            val x1 = width * 7 / 10
            val y0 = height * 3 / 10
            val y1 = height * 7 / 10
            var sum = 0L
            var count = 0
            var y = y0
            while (y < y1) {
                var x = x0
                while (x < x1) {
                    val offset = y * rowStride + x * pixelStride
                    if (offset + 2 < buffer.limit()) {
                        val r = buffer.get(offset).toInt() and 0xff
                        val g = buffer.get(offset + 1).toInt() and 0xff
                        val b = buffer.get(offset + 2).toInt() and 0xff
                        sum += (r * 2126 + g * 7152 + b * 722) / 10_000
                        count++
                    }
                    x += 8
                }
                y += 8
            }
            if (count == 0) return
            val mean = (sum / count).toInt()
            pixelMean.set(mean)
            if (mean < pixelMin.get()) pixelMin.set(mean)
            if (mean > pixelMax.get()) pixelMax.set(mean)
            pixelSamples.incrementAndGet()
            if (mean < 16) pixelBlackSamples.incrementAndGet()
            if (mean > 200) pixelBrightSamples.incrementAndGet()
            Log.i(
                TAG,
                "PIXEL mean=$mean min=${pixelMin.get()} max=${pixelMax.get()} " +
                    "samples=${pixelSamples.get()} black=${pixelBlackSamples.get()} " +
                    "bright=${pixelBrightSamples.get()}",
            )
        } finally {
            runCatching { image.close() }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Teardown
    // ---------------------------------------------------------------------------------------

    private fun stopEncoderPipeline() {
        generation.incrementAndGet()
        val vd = virtualDisplay
        virtualDisplay = null
        runCatching { vd?.release() }
        val codec = encoder
        encoder = null
        if (codec != null) {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
        encoderThread?.quitSafely()
        encoderThread = null
        encoderHandler = null
    }

    private fun stopPixelProbe() {
        pixelHandler?.removeCallbacksAndMessages(null)
        pixelThread?.quitSafely()
        pixelThread = null
        pixelHandler = null
        val reader = imageReader
        imageReader = null
        runCatching { reader?.close() }
        val vd = virtualDisplay
        virtualDisplay = null
        runCatching { vd?.release() }
    }

    private fun teardown(reason: String) {
        if (!tearingDown.compareAndSet(false, true)) return
        isRunning.set(false)
        mainHandler.removeCallbacks(statsRunnable)
        val elapsedMs = (SystemClock.elapsedRealtime() - sessionStartMs.get()).coerceAtLeast(1)
        val bytes = bytesEncoded.get()
        val kbps = bytes * 8 / 1000.0 / (elapsedMs / 1000.0)
        stopReason.set(reason)
        if (mode.get() == MODE_PIXEL) {
            stopPixelProbe()
        } else {
            stopEncoderPipeline()
        }
        runCatching { projection?.unregisterCallback(projectionCallback) }
        runCatching { projection?.stop() }
        projection = null
        val teardownLine = (
            "TEARDOWN reason=$reason mode=${mode.get()} frames=${framesEncoded.get()} " +
                "bytes=$bytes keyframes=${keyframesSeen.get()} kbps=%.0f durationMs=$elapsedMs " +
                "maxFrameGapMs=${maxFrameGapMs.get()} maxKeyframeGapMs=${maxKeyframeGapMs.get()} " +
                "resizes=${resizeEvents.get()} lastResize=${lastResize.get()} " +
                "visibilityChanges=${visibilityChanges.get()} " +
                "pixelSamples=${pixelSamples.get()} pixelBlack=${pixelBlackSamples.get()} " +
                "pixelBright=${pixelBrightSamples.get()}"
            ).format(kbps)
        Log.i(TAG, teardownLine)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        teardown(reason = "onDestroy")
        super.onDestroy()
    }
}
