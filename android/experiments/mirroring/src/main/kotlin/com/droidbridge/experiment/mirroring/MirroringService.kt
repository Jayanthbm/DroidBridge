package com.droidbridge.experiment.mirroring

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.droidbridge.core.network.SecureSession
import com.droidbridge.protocol.media.AccessUnit
import com.droidbridge.protocol.media.H264ParameterSets
import com.droidbridge.protocol.media.MirrorFormat
import com.droidbridge.protocol.media.VideoPayload
import com.droidbridge.protocol.session.ControlMessage
import java.util.concurrent.atomic.AtomicBoolean

/**
 * P08 — capture, encode and send the screen over the authenticated session.
 *
 * The pipeline is the one P01 and ADR-027 proved, with the transport P03 and P13 proved, joined at
 * the only place they meet: an encoded access unit.
 *
 * ```
 * MediaProjection → VirtualDisplay(encoder surface) → MediaCodec H.264
 *       → Annex-B→AVCC, parameter sets removed → mirror.format / VIDEO over TLS 1.3
 * ```
 *
 * ## Start and stop are session-scoped, not connection-scoped
 *
 * Stopping mirroring releases the projection, the virtual display, the encoder and the queued
 * frames, sends `mirror.stop`, and leaves the TLS session open. Starting again needs a fresh
 * consent — Android enforces that with a single-use token (R-04) and the app must not try to work
 * around it — but does not need a new pairing.
 *
 * ## Where frames can be lost
 *
 * Between the encoder and the socket, in [FrameQueue]: bounded, dropping the oldest non-keyframe
 * when the link is slower than the encoder. A backlog would turn "live view" into "recording of two
 * seconds ago", which is worse than a frame the eye never sees. Every drop is counted, not hidden.
 *
 * ## What is deliberately absent
 *
 * No audio (ADR-018), no input path (ADR-015 — this is a viewer), no second connection, and no
 * plaintext fallback. `FLAG_SECURE` regions arrive black because the platform does not deliver those
 * pixels, and nothing here attempts to recover them (ADR-020).
 */
class MirroringService : Service() {

    companion object {
        private const val TAG = "P08Mirroring"

        const val ACTION_START = "com.droidbridge.experiment.mirroring.START"
        const val ACTION_STOP = "com.droidbridge.experiment.mirroring.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"

        /** P08 idle-cost comparison: `true` omits `KEY_REPEAT_PREVIOUS_FRAME_AFTER` for this run. */
        const val EXTRA_OMIT_REPEAT_FRAME = "omitRepeatFrame"

        private const val CHANNEL_ID = "p08_mirroring"
        private const val NOTIFICATION_ID = 8108
        private const val STATS_INTERVAL_MS = 5_000L

        /** ~250 ms of frames at 30 fps: enough to absorb a hiccup, small enough to stay live. */
        private const val QUEUE_CAPACITY = 8

        private const val FRAME_RATE = 30
        private const val BITRATE_BPS = 4_000_000

        val stats = MirrorStats()

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(
            context: Context,
            resultCode: Int,
            resultData: Intent,
            omitRepeatFrame: Boolean = false,
        ) {
            val intent = Intent(context, MirroringService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
                putExtra(EXTRA_OMIT_REPEAT_FRAME, omitRepeatFrame)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, MirroringService::class.java).apply { action = ACTION_STOP },
            )
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private val gate = MirrorPolicy.FrameGate()
    private val queue = FrameQueue(QUEUE_CAPACITY)

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: MirrorEncoder? = null
    private var senderThread: Thread? = null
    private var readerThread: Thread? = null

    private var session: SecureSession? = null

    /** Set once a format message has been sent; the receiver needs one before any frame. */
    private val formatSent = AtomicBoolean(false)

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // The system, the user or the screen-sharing chip ended the projection. Release
            // everything here or the next start fails with a SecurityException (R-04).
            Log.i(TAG, "projection onStop")
            teardown("system onStop (lock / screen-sharing chip / user stop)")
            stopSelf()
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            stats.lastResize.set("${width}x$height")
            stats.contentWidth.set(width)
            stats.contentHeight.set(height)
            Log.i(TAG, "captured content resized to ${width}x$height")
            maybeReEncode(width, height)
        }

        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
            Log.i(TAG, "captured content visible=$isVisible")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> {
                teardown("user stop")
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val resultData: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)

        if (resultData == null) {
            stats.state.set("start failed: no consent data")
            Log.w(TAG, "start refused: no consent data")
            stopSelf()
            return
        }

        MirrorEncoder.repeatPreviousFrameAfterUs = if (intent.getBooleanExtra(EXTRA_OMIT_REPEAT_FRAME, false)) {
            null
        } else {
            MirrorEncoder.REPEAT_FRAME_AFTER_US
        }
        Log.i(TAG, "repeat-previous-frame=${MirrorEncoder.repeatPreviousFrameAfterUs ?: "off"}")

        val activeSession = MirrorSessionHolder.session
        if (activeSession == null || !activeSession.isAuthenticated) {
            stats.state.set("start failed: no authenticated session")
            Log.w(TAG, "start refused: no authenticated session")
            stopSelf()
            return
        }

        stats.resetSession()
        running.set(true)
        isRunning = true
        queue.clear()
        gate.requireKeyframe()
        formatSent.set(false)

        startForegroundWithNotification()

        Thread({
            try {
                val manager = getSystemService(MediaProjectionManager::class.java)
                val mp = manager.getMediaProjection(resultCode, resultData)
                if (mp == null) {
                    teardown("getMediaProjection returned null")
                    stopSelf()
                    return@Thread
                }
                projection = mp
                mp.registerCallback(projectionCallback, mainHandler)
                session = activeSession

                activeSession.send(ControlMessage.Type.MIRROR_STARTED)
                startSender(activeSession)
                startReader(activeSession)

                val metrics = resources.displayMetrics
                val contentWidth = metrics.widthPixels
                val contentHeight = metrics.heightPixels
                stats.contentWidth.set(contentWidth)
                stats.contentHeight.set(contentHeight)
                startPipeline(mp, contentWidth, contentHeight)

                mainHandler.postDelayed(statsRunnable, STATS_INTERVAL_MS)
                Log.i(TAG, "mirroring started content=${contentWidth}x${contentHeight}")
            } catch (e: Exception) {
                Log.e(TAG, "start failed", e)
                stats.state.set("start failed: ${e.javaClass.simpleName}: ${e.message}")
                teardown("start failed")
                stopSelf()
            }
        }, "p08-start").start()
    }

    // ---------------------------------------------------------------------------------------
    // Pipeline
    // ---------------------------------------------------------------------------------------

    private fun startPipeline(
        mp: MediaProjection,
        contentWidth: Int,
        contentHeight: Int,
    ) {
        val (encodeWidth, encodeHeight) = MirrorPolicy.fitEncodeSize(contentWidth, contentHeight)
        val newEncoder = buildEncoder(encodeWidth, encodeHeight)
        newEncoder.start()

        try {
            val display = mp.createVirtualDisplay(
                "p08-capture-${encodeWidth}x$encodeHeight",
                encodeWidth,
                encodeHeight,
                resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                newEncoder.inputSurface,
                null,
                null,
            )
            if (display == null) {
                newEncoder.stop()
                throw IllegalStateException("createVirtualDisplay returned null")
            }
            virtualDisplay = display
            encoder = newEncoder
            stats.encodeWidth.set(encodeWidth)
            stats.encodeHeight.set(encodeHeight)
            stats.state.set("mirroring ${encodeWidth}x$encodeHeight")
            Log.i(
                TAG,
                "pipeline ${encodeWidth}x$encodeHeight from content ${contentWidth}x$contentHeight " +
                    "encoder=${newEncoder.codecName}",
            )
        } catch (e: Exception) {
            newEncoder.stop()
            throw e
        }
    }

    private fun buildEncoder(width: Int, height: Int): MirrorEncoder {
        return MirrorEncoder(
            width = width,
            height = height,
            frameRate = FRAME_RATE,
            bitrate = BITRATE_BPS,
            onFormat = { sets -> publishFormat(width, height, sets) },
            onAccessUnit = { unit -> queueFrame(unit) },
            onError = { message ->
                Log.w(TAG, "encoder error: $message")
                stats.state.set("encoder error: $message")
            },
        )
    }

    /**
     * Sends `mirror.format` and re-arms the keyframe gate.
     *
     * A format message is a promise that the next frame is an IDR, which is why the gate is re-armed
     * here rather than at the encoder: the two must happen together or the Mac receives a P-frame
     * with no reference.
     */
    private fun publishFormat(width: Int, height: Int, sets: H264ParameterSets) {
        val activeSession = session ?: return
        try {
            val format = MirrorFormat(
                width = width,
                height = height,
                // The encoder follows the captured content's orientation, so the image is already
                // upright on the wire and no rotation hint is needed.
                rotation = 0,
                parameterSets = sets,
            )
            gate.requireKeyframe()
            activeSession.send(
                ControlMessage.Type.MIRROR_FORMAT,
                format.toJsonPayload().toByteArray(Charsets.UTF_8),
            )
            stats.formatMessages.incrementAndGet()
            formatSent.set(true)
            Log.i(TAG, "mirror.format $format sps=${sets.sps.size} pps=${sets.pps.size}")
        } catch (e: Exception) {
            Log.w(TAG, "mirror.format could not be sent: ${e.javaClass.simpleName}: ${e.message}")
            stats.state.set("format send failed")
        }
    }

    private fun queueFrame(unit: AccessUnit) {
        // Frames before the first format message are undecodable by the Mac; the gate already drops
        // them until an IDR, and the encoder only starts producing after its parameter sets exist.
        if (!formatSent.get()) return
        if (!gate.admit(unit.isKeyframe)) return

        val entry = FrameQueue.Entry(ptsUs = unit.ptsUs, payload = unit.payload, isKeyframe = unit.isKeyframe)
        if (!queue.offer(entry)) {
            stats.framesDropped.set(queue.dropped)
        }
        if (unit.payload.size > stats.largestFrameBytes.get()) {
            stats.largestFrameBytes.set(unit.payload.size)
        }
    }

    /**
     * Re-encodes at a new geometry when the captured content changes orientation or aspect.
     *
     * Android 17 forbids a second `createVirtualDisplay` on one `MediaProjection` (P01 measured the
     * SecurityException), so the only supported way to change geometry is to bind a new surface to
     * the existing display. If the platform refuses `setSurface`, the old encoder stays and the
     * content is letterboxed into the existing size — a visible degradation, never a corrupt stream.
     */
    private fun maybeReEncode(contentWidth: Int, contentHeight: Int) {
        val display = virtualDisplay ?: return
        val oldEncoder = encoder ?: return
        val currentWidth = stats.encodeWidth.get()
        val currentHeight = stats.encodeHeight.get()

        if (!MirrorPolicy.needsReEncode(currentWidth, currentHeight, contentWidth, contentHeight)) {
            return
        }

        val (newWidth, newHeight) = MirrorPolicy.fitEncodeSize(contentWidth, contentHeight)
        Log.i(TAG, "re-encode ${currentWidth}x$currentHeight -> ${newWidth}x$newHeight")

        val replacement = buildEncoder(newWidth, newHeight)
        try {
            replacement.start()
            val surface = replacement.inputSurface
                ?: throw IllegalStateException("replacement encoder produced no surface")

            display.setSurface(surface)
            display.resize(newWidth, newHeight, resources.displayMetrics.densityDpi)
        } catch (e: Exception) {
            Log.w(TAG, "re-encode refused by the platform; keeping the current geometry", e)
            stats.state.set("rotation kept at ${currentWidth}x$currentHeight")
            replacement.stop()
            return
        }

        // Swap only after the display is bound to the new surface, so no frame is drained from a
        // codec the display no longer feeds.
        encoder = replacement
        oldEncoder.stop()
        stats.encodeWidth.set(newWidth)
        stats.encodeHeight.set(newHeight)
        stats.reEncodes.incrementAndGet()
        stats.state.set("mirroring ${newWidth}x$newHeight")
    }

    // ---------------------------------------------------------------------------------------
    // Transport threads
    // ---------------------------------------------------------------------------------------

    private fun startSender(activeSession: SecureSession) {
        val thread = Thread({
            while (running.get()) {
                val entry = queue.take()
                if (entry == null) {
                    // Nothing to send: pause briefly rather than spin.
                    Thread.sleep(2)
                    continue
                }
                try {
                    val payload = VideoPayload.encode(entry.ptsUs, entry.payload)
                    val startedNs = System.nanoTime()
                    activeSession.send(ControlMessage.Type.VIDEO, payload)
                    val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000
                    if (elapsedMs > stats.maxSendMs.get()) stats.maxSendMs.set(elapsedMs)

                    stats.framesSent.incrementAndGet()
                    stats.bytesSent.addAndGet(payload.size.toLong())
                    if (entry.isKeyframe) stats.keyframesSent.incrementAndGet()
                    stats.framesDropped.set(queue.dropped)
                } catch (e: InterruptedException) {
                    return@Thread
                } catch (e: Exception) {
                    // A closed session is the normal way this thread ends after the Mac goes away.
                    if (running.get()) {
                        Log.w(TAG, "send stopped: ${e.javaClass.simpleName}: ${e.message}")
                        stats.state.set("connection lost")
                    }
                    teardown("send failed: ${e.javaClass.simpleName}")
                    stopSelf()
                    return@Thread
                }
            }
        }, "p08-sender")
        thread.isDaemon = true
        senderThread = thread
        thread.start()
    }

    /**
     * Reads control traffic while mirroring.
     *
     * The only message the Mac sends during a session is `mirror.keyframe_request`, after it flushed
     * its decoder. Ignoring it would leave the Mac showing nothing until the next scheduled GOP.
     */
    private fun startReader(activeSession: SecureSession) {
        val thread = Thread({
            while (running.get()) {
                val message = try {
                    activeSession.tryReceive(1_000)
                } catch (e: Exception) {
                    if (running.get()) {
                        Log.w(TAG, "receive stopped: ${e.javaClass.simpleName}: ${e.message}")
                        stats.state.set("connection lost")
                        teardown("receive failed: ${e.javaClass.simpleName}")
                        stopSelf()
                    }
                    return@Thread
                } ?: continue

                when (message.type) {
                    ControlMessage.Type.MIRROR_KEYFRAME_REQUEST -> {
                        stats.keyframeRequests.incrementAndGet()
                        gate.requireKeyframe()
                        encoder?.requestSyncFrame()
                        Log.i(TAG, "keyframe requested by the Mac; requesting an IDR")
                    }

                    ControlMessage.Type.PING -> try {
                        activeSession.send(ControlMessage.Type.PONG, message.payload)
                    } catch (e: Exception) {
                        Log.w(TAG, "pong failed: ${e.javaClass.simpleName}")
                    }

                    ControlMessage.Type.CLOSE -> {
                        teardown("peer sent close")
                        stopSelf()
                        return@Thread
                    }

                    else -> Unit
                }
            }
        }, "p08-reader")
        thread.isDaemon = true
        readerThread = thread
        thread.start()
    }

    // ---------------------------------------------------------------------------------------
    // Notification and teardown
    // ---------------------------------------------------------------------------------------

    private fun startForegroundWithNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Screen mirroring",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MirroringActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, MirroringService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Mirroring your screen")
            .setContentText("View-only stream to the paired Mac. No audio, no remote control.")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .setContentIntent(openApp)
            .addAction(0, "Stop", stopIntent)
            .build()

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )
    }

    private val statsRunnable = object : Runnable {
        override fun run() {
            if (!running.get()) return
            val line = stats.summaryLine()
            Log.i(TAG, line)
            mainHandler.postDelayed(this, STATS_INTERVAL_MS)
        }
    }

    /**
     * Releases the pipeline and tells the Mac the stream ended.
     *
     * Order matters: the virtual display is released before the projection is stopped, the encoder is
     * stopped before its surface is released, and the sender is stopped before the codec so no thread
     * writes to a released buffer. Idempotent, because `onStop`, a send failure and a user stop can
     * all arrive for the same session.
     */
    private fun teardown(reason: String) {
        if (!running.compareAndSet(true, false)) {
            // First caller wins; later ones still ensure the foreground state is gone.
            isRunning = false
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            return
        }
        isRunning = false
        mainHandler.removeCallbacks(statsRunnable)
        stats.lastStopReason.set(reason)
        stats.state.set("stopped: $reason")
        stats.framesDropped.set(queue.dropped)

        Log.i(TAG, stats.summaryLine())

        queue.clear()

        val activeSession = session
        if (activeSession != null && activeSession.isAuthenticated) {
            val sendStop = {
                runCatching {
                    activeSession.send(
                        ControlMessage.Type.MIRROR_STOP,
                        reason.toByteArray(Charsets.UTF_8),
                    )
                }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) {
                Thread({ sendStop() }, "p08-stop-send").start()
            } else {
                sendStop()
            }
        }

        senderThread?.interrupt()
        readerThread?.interrupt()
        senderThread = null
        readerThread = null

        val currentEncoder = encoder
        encoder = null
        virtualDisplay?.let { runCatching { it.release() } }
        virtualDisplay = null
        currentEncoder?.stop()

        runCatching { projection?.unregisterCallback(projectionCallback) }
        runCatching { projection?.stop() }
        projection = null

        formatSent.set(false)
        gate.requireKeyframe()

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        Log.i(TAG, "teardown complete reason=$reason")
    }

    override fun onDestroy() {
        teardown("service destroyed")
        super.onDestroy()
    }
}
