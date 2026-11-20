// Copyright (C) 2026 DroidBridge contributors.
// SPDX-License-Identifier: none — internal feasibility experiment, not for distribution.
package com.droidbridge.experiment.backgroundsession

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.droidbridge.core.network.SecureSession
import com.droidbridge.protocol.session.ControlMessage
import com.droidbridge.core.security.DeviceIdentity
import com.droidbridge.core.security.KeystorePinStore
import java.util.concurrent.atomic.AtomicBoolean

/**
 * `connectedDevice` foreground service that owns the P14 session.
 *
 * Shape is fixed by ADR-023 and `Docs/PROTOTYPE_PLAN.md` P14: started only from app UI or
 * BOOT_COMPLETED, persistent notification with state and a disconnect action, reconnect with
 * exponential backoff on any socket error, and no wake lock, no battery-optimisation exemption and no
 * silent retry loop — every attempt and its outcome is recorded.
 *
 * The heartbeat is a real protocol PING every [HEARTBEAT_INTERVAL_MS]; a PONG is the only proof the
 * session is alive in both directions.
 */
class SessionService : Service() {

    companion object {
        const val ACTION_START = "com.droidbridge.experiment.backgroundsession.START"
        const val ACTION_STOP = "com.droidbridge.experiment.backgroundsession.STOP"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_PIN = "pin"
        const val PEER_ID = "p14-mac"

        private const val HEARTBEAT_INTERVAL_MS = 15_000L
        private const val PONG_TIMEOUT_MS = 14_000
        private const val IDLE_TIMEOUT_MS = 45_000
        private val BACKOFF_SECONDS = longArrayOf(2, 4, 8, 16, 32, 60)
        private const val SUPERVISION_INTERVAL_MS = 5_000L
        private const val STALL_TIMEOUT_MS = 50_000L
        private const val STATE_CONNECTING = "connecting"
        private const val STATE_CONNECTED = "connected"
    }

    private val running = AtomicBoolean(false)
    private var worker: Thread? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var session: SecureSession? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        NotificationContext.context = applicationContext
        ServiceNotifications.register(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                SessionLog.record("stop requested")
                shutdown("stop requested")
                return START_NOT_STICKY
            }

            else -> {
                val host = intent?.getStringExtra(EXTRA_HOST) ?: Prefs.host(this)
                val port = intent?.getIntExtra(EXTRA_PORT, Prefs.port(this)) ?: Prefs.port(this)
                val pin = intent?.getStringExtra(EXTRA_PIN) ?: Prefs.pin(this)
                if (host == null || pin.isNullOrBlank()) {
                    SessionLog.fail("refused to start: host/pin missing")
                    stopSelf()
                    return START_NOT_STICKY
                }
                Prefs.save(this, host, port, pin)
                start(host, port, pin)
            }
        }
        return START_STICKY
    }

    private fun start(host: String, port: Int, pin: String) {
        // The foreground promotion must happen in the start path or Android throws immediately.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    ServiceNotifications.NOTIFICATION_ID,
                    ServiceNotifications.build(this),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
            } else {
                startForeground(ServiceNotifications.NOTIFICATION_ID, ServiceNotifications.build(this))
            }
            SessionLog.record("startForeground succeeded for connectedDevice")
        } catch (e: Exception) {
            SessionLog.fail("startForeground failed: ${e.javaClass.name}: ${e.message}")
            stopSelf()
            return
        }

        // A second start with different parameters must take effect, otherwise a long survival run
        // silently keeps the stale target (and the harness has no way to see that it did).
        if (running.get()) {
            SessionLog.record("restarting session for new target $host:$port")
            running.set(false)
            runCatching { session?.close("restart for new target") }
            session = null
            worker?.interrupt()
            worker?.join(3_000)
            worker = null
        }
        running.set(true)

        SessionLog.setState(STATE_CONNECTING)
        startWorker(host, port, pin)
        startSupervision()
    }

    private fun startWorker(host: String, port: Int, pin: String) {
        worker = Thread({ runLoop(host, port, pin) }, "p14-session").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Keeps the notification honest and catches a stalled worker.
     *
     * A worker that blocks inside a socket call does not throw, so without this the service would sit
     * there showing "connected" with a dead session — the exact failure ADR-023 says must never be
     * presented as reachable. The watchdog compares the heartbeat age against three intervals.
     */
    private fun startSupervision() {
        val task = object : Runnable {
            override fun run() {
                if (!running.get()) return
                ServiceNotifications.update()
                val age = SessionLog.heartbeatAgeMs()
                // Only a session that claims to be connected can be "stalled". Backoff after a real
                // outage naturally has no heartbeat for longer than the threshold, and treating that
                // as a stall restarted a healthy worker during a Wi-Fi outage.
                val stalled = SessionLog.state == STATE_CONNECTED && age > STALL_TIMEOUT_MS
                if (stalled) {
                    SessionLog.fail("worker stalled: no heartbeat for ${age}ms; restarting session")
                    abandonWorker()
                    val host = Prefs.host(this@SessionService)
                    val port = Prefs.port(this@SessionService)
                    val pin = Prefs.pin(this@SessionService)
                    if (host != null && pin != null) startWorker(host, port, pin)
                }
                mainHandler.postDelayed(this, SUPERVISION_INTERVAL_MS)
            }
        }
        mainHandler.postDelayed(task, SUPERVISION_INTERVAL_MS)
    }

    /** Stops the current loop without ending the service, so a fresh one can take over. */
    private fun abandonWorker() {
        running.set(false)
        runCatching { session?.close("abandoned by watchdog") }
        session = null
        worker?.interrupt()
        worker?.join(3_000)
        worker = null
        running.set(true)
    }

    private fun runLoop(host: String, port: Int, pin: String) {
        val identity = DeviceIdentity.loadOrCreate()
        val store = KeystorePinStore(applicationContext)
        SessionLog.record("identity pin=${identity.pin} target=$host:$port")

        var backoffIndex = 0
        while (running.get()) {
            try {
                SessionLog.setState(STATE_CONNECTING)
                SessionLog.record("connecting to $host:$port")
                val active = SecureSession.connect(
                    host = host,
                    port = port,
                    identity = identity,
                    expectedPin = pin,
                    idleTimeoutMs = IDLE_TIMEOUT_MS,
                )
                session = active
                if (store.pinFor(PEER_ID) != pin) {
                    // The pin came from the Mac's own printout, which is the out-of-band value; storing
                    // it here is what a confirmed first pairing would have done.
                    store.remember(PEER_ID, pin)
                    SessionLog.record("stored peer pin for $PEER_ID")
                }
                SessionLog.setState(STATE_CONNECTED)
                backoffIndex = 0
                heartbeatLoop(active)
            } catch (e: Exception) {
                SessionLog.fail("${e.javaClass.simpleName}: ${e.message}")
            } catch (t: Throwable) {
                // An Error would otherwise kill the thread and leave a "connected" service behind.
                SessionLog.fail("worker aborted: ${t.javaClass.name}: ${t.message}")
            } finally {
                runCatching { session?.close("p14 loop end") }
                session = null
            }

            if (!running.get()) break
            val wait = BACKOFF_SECONDS[backoffIndex.coerceAtMost(BACKOFF_SECONDS.lastIndex)]
            backoffIndex += 1
            SessionLog.countReconnect()
            SessionLog.setState("reconnecting in ${wait}s")
            if (!sleepInterruptibly(wait * 1000)) break
        }
        SessionLog.setState("stopped")
    }

    /** Sends a PING, then requires its PONG before the next one. A miss ends the session. */
    private fun heartbeatLoop(active: SecureSession) {
        var sequence = 0
        while (running.get() && active.state == SecureSession.State.AUTHENTICATED) {
            val startedAt = System.currentTimeMillis()
            val payload = "p14-heartbeat-${++sequence}".toByteArray()
            active.send(ControlMessage.Type.PING, payload)
            SessionLog.countHeartbeat()

            val response = active.tryReceive(PONG_TIMEOUT_MS)
            if (response == null) {
                SessionLog.fail("no PONG within ${PONG_TIMEOUT_MS}ms after heartbeat $sequence")
                return
            }
            if (response.type != ControlMessage.Type.PONG) {
                SessionLog.record("unexpected frame ${response.type} while awaiting PONG")
            } else {
                SessionLog.countPong()
                SessionLog.record("heartbeat $sequence answered")
            }
            // Keep a steady 15 s cadence from the start of one heartbeat to the start of the next.
            val elapsed = System.currentTimeMillis() - startedAt
            if (!sleepInterruptibly((HEARTBEAT_INTERVAL_MS - elapsed).coerceAtLeast(0))) return
        }
    }

    private fun sleepInterruptibly(milliseconds: Long): Boolean {
        val until = System.currentTimeMillis() + milliseconds
        while (running.get() && System.currentTimeMillis() < until) {
            Thread.sleep(50)
        }
        return running.get()
    }

    private fun shutdown(reason: String) {
        running.set(false)
        runCatching { session?.close(reason) }
        session = null
        worker?.interrupt()
        worker = null
        SessionLog.setState("stopped")
        mainHandler.removeCallbacksAndMessages(null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        running.set(false)
        runCatching { session?.close("service destroyed") }
        session = null
        SessionLog.record("service destroyed")
        super.onDestroy()
    }
}

/** Launch configuration, so a reboot can resume the same session without an activity. */
object Prefs {
    private const val FILE = "p14"
    private const val KEY_HOST = "host"
    private const val KEY_PORT = "port"
    private const val KEY_PIN = "pin"
    private const val KEY_ENABLED = "enabled"

    fun save(context: Context, host: String, port: Int, pin: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_HOST, host)
            .putInt(KEY_PORT, port)
            .putString(KEY_PIN, pin)
            .putBoolean(KEY_ENABLED, true)
            .apply()
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun host(context: Context): String? =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_HOST, null)

    fun port(context: Context): Int =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt(KEY_PORT, 0)

    fun pin(context: Context): String? =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_PIN, null)
}
