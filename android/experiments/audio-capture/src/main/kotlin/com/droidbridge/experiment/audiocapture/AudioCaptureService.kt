package com.droidbridge.experiment.audiocapture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.util.Log
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * P12: measures what Android's playback-capture rules actually deliver.
 *
 * The question is not "can we call AudioRecord" — it is which audio arrives. Playback capture is
 * authorised by a MediaProjection consent and then filtered per stream: `USAGE_VOICE_COMMUNICATION`
 * (call audio) is excluded by the platform, and a playing app can refuse with
 * `ALLOW_CAPTURE_BY_NONE`. Those refusals are silent, so the only honest measurement is the signal
 * itself: per-second RMS and frame counts of the PCM the capture API hands us.
 *
 * Everything is measured locally; this experiment has no network permission and transmits nothing.
 */
class AudioCaptureService : Service() {

    companion object {
        private const val TAG = "P12Audio"
        private const val CHANNEL_ID = "p12-audio"
        private const val NOTIFICATION_ID = 1201

        const val ACTION_START = "com.droidbridge.experiment.audiocapture.START"
        const val ACTION_STOP = "com.droidbridge.experiment.audiocapture.STOP"

        /** Propagates MediaProjection.onStop, which announces a revoked consent. */
        const val ACTION_PROJECTION_STOPPED =
            "com.droidbridge.experiment.audiocapture.PROJECTION_STOPPED"

        private const val SAMPLE_RATE = 44_100

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, AudioCaptureService::class.java)
                .setAction(ACTION_START)
                .putExtra("resultCode", resultCode)
                .putExtra("resultData", data)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, AudioCaptureService::class.java).setAction(ACTION_STOP)
            )
        }
    }

    private var mediaProjection: MediaProjection? = null
    private var thread: Thread? = null
    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopCapture("stop requested")
                return START_NOT_STICKY
            }
            ACTION_START -> {
                startForegroundNow()
                val resultCode = intent.getIntExtra("resultCode", 0)
                @Suppress("DEPRECATION")
                val data: Intent? = intent.getParcelableExtra("resultData")
                if (data == null) {
                    Log.e(TAG, "START without projection consent data")
                    stopSelf()
                    return START_NOT_STICKY
                }
                startCapture(resultCode, data)
            }
        }
        return START_STICKY
    }

    private fun startForegroundNow() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "P12 audio capture", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Playback-capture measurement"; setShowBadge(false) }
            )
        }
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("P12 audio capture")
            .setContentText("Measuring playback capture")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
        // minSdk is 31, so the typed overload is always available and no SDK check is needed.
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        Log.i(TAG, "startForeground succeeded for mediaProjection")
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        if (running) {
            Log.i(TAG, "capture already running; ignoring start")
            return
        }
        val manager = getSystemService(MediaProjectionManager::class.java)
        val projection = manager.getMediaProjection(resultCode, data)
        if (projection == null) {
            Log.e(TAG, "getMediaProjection returned null; consent not granted")
            stopSelf()
            return
        }
        projection.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.i(TAG, "projection stopped by the system; ending capture")
                    stopCapture("projection stopped")
                }
            },
            null
        )
        mediaProjection = projection
        running = true
        thread = Thread({ captureLoop(projection) }, "p12-audio-capture").also { it.start() }
    }

    /**
     * Captures every usage that the platform allows, so a stream is only missing because the
     * platform or the playing app withheld it — never because this experiment forgot to ask.
     */
    private fun captureLoop(projection: MediaProjection) {
        try {
            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()

            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = max(minBuffer, SAMPLE_RATE * 4)
            val record = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .setAudioPlaybackCaptureConfig(config)
                .build()

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord not initialised state=${record.state}")
                record.release()
                stopSelf()
                return
            }
            record.startRecording()
            Log.i(TAG, "capture started rate=$SAMPLE_RATE buffer=$bufferSize state=${record.recordingState}")

            val buffer = ShortArray(bufferSize / 2)
            var secondStart = System.currentTimeMillis()
            var framesInSecond = 0L
            var sumSquares = 0.0
            var peak = 0
            var totalFrames = 0L
            var silentSeconds = 0

            while (running) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) {
                    for (i in 0 until read) {
                        val sample = buffer[i].toInt()
                        sumSquares += (sample.toDouble() * sample)
                        val magnitude = abs(sample)
                        if (magnitude > peak) peak = magnitude
                    }
                    framesInSecond += read / 2
                    totalFrames += read / 2
                } else if (read < 0) {
                    Log.e(TAG, "read error=$read")
                }
                val now = System.currentTimeMillis()
                if (now - secondStart >= 1000) {
                    val elapsed = (now - secondStart) / 1000.0
                    val samples = max(1L, framesInSecond * 2)
                    val rms = sqrt(sumSquares / samples)
                    if (rms < 1.0) silentSeconds++ else silentSeconds = 0
                    Log.i(
                        TAG,
                        "t=%.1fs frames=%d total=%d rms=%.1f peak=%d silent=%s".format(
                            elapsed, framesInSecond, totalFrames, rms, peak,
                            if (rms < 1.0) "yes" else "no"
                        )
                    )
                    secondStart = now
                    framesInSecond = 0
                    sumSquares = 0.0
                    peak = 0
                }
            }
            record.stop()
            record.release()
            Log.i(TAG, "capture stopped totalFrames=$totalFrames silentSeconds=$silentSeconds")
        } catch (e: SecurityException) {
            Log.e(TAG, "capture rejected: ${e.javaClass.simpleName}: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "capture failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun stopCapture(reason: String) {
        if (!running) {
            stopSelf()
            return
        }
        running = false
        thread?.join(1500)
        thread = null
        mediaProjection?.stop()
        mediaProjection = null
        Log.i(TAG, "capture ending reason=$reason")
        sendBroadcast(Intent(ACTION_PROJECTION_STOPPED).setPackage(packageName))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }
}
