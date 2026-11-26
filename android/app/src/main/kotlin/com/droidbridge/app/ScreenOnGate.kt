package com.droidbridge.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import com.droidbridge.core.network.RetryGate
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Holds reconnect attempts until the display is on.
 *
 * Nothing in the shipping app keeps this process' network usable while the phone sleeps: there is no
 * `connectedDevice` service yet (that is ADR-023's eventual owner), so with the screen off the socket
 * is reset and every retry fails before it leaves the device. Measured 2026-11-26: `Connection reset
 * by peer` about 30 s after the display went off, then ten attempts that never appeared on a Mac that
 * was listening throughout, ending in a terminal give-up that needed a manual tap.
 *
 * Waiting here costs nothing, because the budget is charged only once an attempt can get out; the
 * phone then reconnects on the first attempt after the screen comes back.
 *
 * The display state, not a connectivity check: the radio still reports an active network during the
 * window in which nothing gets through, so a `NetworkCallback` would never fire for this case.
 */
class ScreenOnGate(context: Context) : RetryGate {

    private val appContext = context.applicationContext
    private val power = appContext.getSystemService(PowerManager::class.java)

    override suspend fun awaitUsable() {
        while (!isUsable()) awaitScreenOn()
    }

    private fun isUsable(): Boolean = power?.isInteractive != false

    private suspend fun awaitScreenOn(): Unit = suspendCancellableCoroutine { continuation ->
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (!isUsable()) return
                runCatching { appContext.unregisterReceiver(this) }
                if (continuation.isActive) continuation.resume(Unit)
            }
        }
        val filter = IntentFilter(Intent.ACTION_SCREEN_ON)
        // A system broadcast, so it is exempt from the exported flag; passing NOT_EXPORTED keeps the
        // intent unambiguous and satisfies the lint check that applies to this targetSdk.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            appContext.registerReceiver(receiver, filter)
        }
        continuation.invokeOnCancellation { runCatching { appContext.unregisterReceiver(receiver) } }
    }
}
