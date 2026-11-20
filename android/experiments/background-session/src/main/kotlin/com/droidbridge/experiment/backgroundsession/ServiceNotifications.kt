// Copyright (C) 2026 DroidBridge contributors.
// SPDX-License-Identifier: none — internal feasibility experiment, not for distribution.
package com.droidbridge.experiment.backgroundsession

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * The single notification P14 shows while background sync is on.
 *
 * ADR-023 requires a persistent notification with connection state and a disconnect action. The
 * notification is also evidence in its own right: P14 test 6 checks that dismissing it does not stop
 * the service and that its text never claims a connection the session does not have.
 */
object ServiceNotifications {

    const val CHANNEL_ID = "p14-session"
    const val NOTIFICATION_ID = 1401

    @Volatile
    private var manager: NotificationManager? = null

    fun register(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        manager = nm
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Background session",
                    // Low: a persistent session notification must be present but must not buzz.
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Shown while the P14 background session is running."
                    setShowBadge(false)
                },
            )
        }
    }

    fun build(context: Context): Notification {
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            context,
            1,
            Intent(context, SessionService::class.java).setAction(SessionService.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val detail = "${SessionLog.state} · ${SessionLog.heartbeats} heartbeats · " +
            "${SessionLog.pongs} pongs · ${SessionLog.reconnects} reconnects"

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("DroidBridge background session")
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$detail\n${SessionLog.summary()}"))
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Disconnect", stop)
            .build()
    }

    fun update() {
        val nm = manager ?: return
        val context = NotificationContext.context ?: return
        runCatching {
            nm.notify(NOTIFICATION_ID, build(context))
        }
    }

    fun requiredType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            0
        }
}

/** Keeps the application context alive for notification refreshes from the log store. */
object NotificationContext {
    @Volatile
    var context: Context? = null
}
