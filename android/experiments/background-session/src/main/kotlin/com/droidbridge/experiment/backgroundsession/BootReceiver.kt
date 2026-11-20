// Copyright (C) 2026 DroidBridge contributors.
// SPDX-License-Identifier: none — internal feasibility experiment, not for distribution.
package com.droidbridge.experiment.backgroundsession

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * P14 test 3: start the `connectedDevice` service from BOOT_COMPLETED.
 *
 * ADR-023 and R-20 claim `connectedDevice` is not blocked from boot, unlike `dataSync` and
 * `mediaProjection`. This receiver only records what actually happened: whether the start was allowed
 * and, if not, the exact exception name. It never starts the service unless background sync was
 * previously enabled by the user, so a reboot after a disable stays disabled.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val enabled = Prefs.enabled(context)
        SessionLog.record("BOOT_COMPLETED received; background sync enabled=$enabled")
        if (!enabled) return

        try {
            val start = Intent(context, SessionService::class.java)
                .setAction(SessionService.ACTION_START)
            context.startForegroundService(start)
            SessionLog.record("BOOT_COMPLETED startForegroundService accepted")
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException would land here; record the exact type so the
            // claim is evidence rather than expectation.
            SessionLog.fail("BOOT_COMPLETED start rejected: ${e.javaClass.name}: ${e.message}")
            Log.e(SessionLog.TAG, "boot start rejected", e)
        }
    }
}
