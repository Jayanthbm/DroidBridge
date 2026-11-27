package com.jayanth.droidbridge.mirror

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.jayanth.droidbridge.MainActivity
import com.jayanth.droidbridge.R
import com.jayanth.droidbridge.network.BridgeLink

class MirrorService : Service() {
    companion object {
        private const val ACTION_START = "com.jayanth.droidbridge.mirror.START"
        private const val ACTION_STOP = "com.jayanth.droidbridge.mirror.STOP"
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_RESULT_DATA = "resultData"
        private const val CHANNEL_ID = "droidbridge.mirroring"
        private const val NOTIFICATION_ID = 4201

        @Volatile
        var isRunning = false
            private set

        @Volatile
        var onRunningChanged: ((Boolean) -> Unit)? = null

        @Volatile
        private var instance: MirrorService? = null

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, MirrorService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MirrorService::class.java))
        }

        fun requestKeyFrame() {
            instance?.encoder?.requestKeyFrame()
        }

        private fun setRunning(value: Boolean) {
            isRunning = value
            Handler(Looper.getMainLooper()).post { onRunningChanged?.invoke(value) }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var encoder: ScreenEncoder? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var stopping = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopMirroring()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                stopping = false
                startForegroundNotification()
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = resultData(intent)
                if (data == null || resultCode == 0) {
                    stopMirroring()
                    stopSelf()
                    return START_NOT_STICKY
                }
                startProjection(resultCode, data)
                return START_NOT_STICKY
            }

            else -> return START_NOT_STICKY
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopMirroring()
        instance = null
        super.onDestroy()
    }

    private fun startProjection(resultCode: Int, data: Intent) {
        if (projection != null) return
        try {
            val manager = getSystemService(MediaProjectionManager::class.java)
            val created = manager.getMediaProjection(resultCode, data)
                ?: error("The screen capture permission was not granted.")
            projection = created
            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    mainHandler.post {
                        if (!stopping) {
                            stopMirroring()
                            stopSelf()
                        }
                    }
                }
            }
            projectionCallback = callback
            created.registerCallback(callback, mainHandler)

            val screenEncoder = ScreenEncoder(
                context = this,
                projection = created,
                onFrame = { frame ->
                    BridgeLink.active?.sendVideo(
                        width = frame.width,
                        height = frame.height,
                        rotation = frame.rotation,
                        keyframe = frame.keyframe,
                        presentationTimeUs = frame.presentationTimeUs,
                        accessUnit = frame.data,
                    )
                },
                onError = { message ->
                    android.util.Log.w("DroidBridge", "Screen encoder: $message")
                    mainHandler.post {
                        if (!stopping) {
                            stopMirroring()
                            stopSelf()
                        }
                    }
                },
            )
            encoder = screenEncoder
            setRunning(true)
            screenEncoder.start()
        } catch (error: Exception) {
            android.util.Log.w("DroidBridge", "Could not start mirroring", error)
            stopMirroring()
            stopSelf()
        }
    }

    private fun stopMirroring() {
        if (stopping) return
        stopping = true
        BridgeLink.active?.sendMirrorStopped()
        encoder?.stop()
        encoder = null
        projectionCallback?.let { callback ->
            runCatching { projection?.unregisterCallback(callback) }
        }
        projectionCallback = null
        runCatching { projection?.stop() }
        projection = null
        setRunning(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun startForegroundNotification() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, MirrorService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Mirroring your screen")
            .setContentText("DroidBridge is streaming this display over Wi-Fi.")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Stop",
                    stopIntent,
                ).build(),
            )
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Screen mirroring",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shown while DroidBridge is mirroring your screen."
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    @Suppress("DEPRECATION")
    private fun resultData(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
}
