package com.droidbridge.experiment.clipboardreceive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * P05: receive Mac text over a framed TCP socket and write it to the Android clipboard.
 *
 * The transport is deliberately a plain length-prefixed TCP stream. P03 already owns TLS and
 * pairing; P05's question is what Android permits when a **background app** writes the clipboard,
 * so the transport is kept out of the way. The report states that this P05 run is not a TLS test.
 *
 * Wire shape: `[4-byte big-endian length][UTF-8 payload]`. The length is validated before any
 * allocation (100 KiB cap), so a malicious or buggy peer cannot make the app reserve an unbounded
 * buffer.
 *
 * No clipboard content is logged. The evidence is length plus SHA-256 prefix; that is enough to
 * compare payloads and prove a duplicate was suppressed without leaking what was copied.
 */
class ClipboardReceiverService : Service() {

    companion object {
        private const val TAG = "P05Clipboard"
        private const val CHANNEL_ID = "p05_clipboard_receive"
        private const val NOTIFICATION_ID = 5101
        private const val RETRY_DELAY_MS = 2_000L

        const val ACTION_START = "com.droidbridge.experiment.clipboardreceive.START"
        const val ACTION_STOP = "com.droidbridge.experiment.clipboardreceive.STOP"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"

        val state = AtomicReference("idle")
        val received = AtomicInteger()
        val written = AtomicInteger()
        val duplicates = AtomicInteger()
        val rejected = AtomicInteger()
        val lastLength = AtomicInteger()
        val lastHash = AtomicReference("")
        val lastDeliveredAt = AtomicLong(0)
        val events = CopyOnWriteArrayList<String>()

        fun start(context: Context, host: String, port: Int) {
            val intent = Intent(context, ClipboardReceiverService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_HOST, host)
                putExtra(EXTRA_PORT, port)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, ClipboardReceiverService::class.java).apply { action = ACTION_STOP },
            )
        }

        fun record(line: String) {
            events.add(line)
            if (events.size > 80) events.removeAt(0)
            Log.i(TAG, line)
        }

        fun distance(nowMs: Long): Long = nowMs - lastDeliveredAt.get()
    }

    @Volatile
    private var running = false

    @Volatile
    private var socket: Socket? = null

    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val host = intent.getStringExtra(EXTRA_HOST)?.trim().orEmpty()
                val port = intent.getIntExtra(EXTRA_PORT, 47900)
                if (host.isEmpty() || port !in 1..65535) {
                    record("START rejected host='$host' port=$port")
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForegroundWithNotification()
                if (!running) {
                    resetCounters()
                    running = true
                    state.set("connecting")
                    worker = Thread({ connectLoop(host, port) }, "p05-receiver").also {
                        it.isDaemon = true
                        it.start()
                    }
                    record("START host=$host port=$port")
                } else {
                    record("START ignored: already running")
                }
            }

            ACTION_STOP -> {
                record("STOP requested")
                stopReceiver()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun resetCounters() {
        received.set(0)
        written.set(0)
        duplicates.set(0)
        rejected.set(0)
        lastLength.set(0)
        lastHash.set("")
        lastDeliveredAt.set(0)
        events.clear()
        state.set("connecting")
    }

    private fun startForegroundWithNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "P05 clipboard receive experiment",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("P05 clipboard receive running")
            .setContentText("Receiving text from the Mac. No content is stored or logged.")
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun connectLoop(host: String, port: Int) {
        while (running) {
            var connection: Socket? = null
            try {
                val candidate = Socket()
                candidate.tcpNoDelay = true
                candidate.connect(InetSocketAddress(host, port), 5_000)
                candidate.soTimeout = 30_000
                connection = candidate
                socket = candidate
                state.set("connected")
                record("CONNECTED $host:$port")

                val input = DataInputStream(BufferedInputStream(candidate.getInputStream()))
                while (running) {
                    val length = input.readInt()
                    if (ClipboardPayload.tooLarge(length)) {
                        rejected.incrementAndGet()
                        record("REJECT declaredLength=$length cap=${ClipboardPayload.MAX_BYTES}")
                        break
                    }
                    val bytes = ByteArray(length)
                    input.readFully(bytes)
                    deliver(String(bytes, Charsets.UTF_8))
                }
            } catch (e: EOFException) {
                if (running) record("DISCONNECTED eof")
            } catch (e: Exception) {
                if (running) record("DISCONNECTED ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                runCatching { connection?.close() }
                socket = null
            }
            if (running) {
                state.set("retrying")
                try {
                    Thread.sleep(RETRY_DELAY_MS)
                } catch (_: InterruptedException) {
                    running = false
                }
            }
        }
        state.set("stopped")
    }

    /**
     * Writes one received payload, unless it repeats the last content inside the dedupe window.
     *
     * The clock is monotonic so a wall-clock adjustment cannot make duplicate suppression
     * arbitrary. The content is never logged; only its length and hash prefix are.
     */
    private fun deliver(text: String) {
        received.incrementAndGet()
        val hash = ClipboardPayload.sha256Hex(text)
        val now = SystemClock.elapsedRealtime()
        val duplicate = hash == lastHash.get() && distance(now) < ClipboardPayload.DEDUP_WINDOW_MS
        if (duplicate) {
            duplicates.incrementAndGet()
            record(
                "DELIVER duplicate=true ignored=true len=${text.length} " +
                    "hash=${ClipboardPayload.shortHash(hash)}",
            )
            return
        }

        try {
            val manager = getSystemService(ClipboardManager::class.java)
                ?: throw IllegalStateException("ClipboardManager unavailable")
            manager.setPrimaryClip(ClipData.newPlainText("DroidBridge", text))
            written.incrementAndGet()
            lastLength.set(text.length)
            lastHash.set(hash)
            lastDeliveredAt.set(now)
            record(
                "DELIVER wrote=true len=${text.length} " +
                    "hash=${ClipboardPayload.shortHash(hash)} api=${android.os.Build.VERSION.SDK_INT}",
            )
        } catch (e: Exception) {
            record("DELIVER wrote=false error=${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun stopReceiver() {
        running = false
        runCatching { socket?.close() }
        socket = null
        state.set("stopped")
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        stopReceiver()
        super.onDestroy()
    }
}
