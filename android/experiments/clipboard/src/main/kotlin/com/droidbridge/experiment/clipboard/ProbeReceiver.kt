package com.droidbridge.experiment.clipboard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * P04: attempts a clipboard read while the app is *not* the focused app.
 *
 * This exists to measure the OS gate, not to get around it. P04 asks for the row "foreground
 * service only, another app on screen → null", and there is no way to observe that from a button
 * in an activity, because pressing a button requires the activity to be focused. A broadcast
 * reaches the app while another app holds focus, which is exactly the condition to be measured.
 *
 * The receiver is exported only so `adb shell am broadcast` can reach it during the prototype, and
 * it is deliberately not registered in production code. It performs one read and logs the result;
 * it does not retry, does not poll, and stores nothing. If the OS refuses the read, that refusal is
 * the finding.
 */
class ProbeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val case = intent.getStringExtra("case") ?: "background_unfocused"
        Log.i("P04Clipboard", "RECEIVER case=$case (app not focused)")
        val result = ClipboardProbe.read(context, case)
        Log.i("P04Clipboard", "RECEIVER_RESULT ${result.toLine()}")
    }
}
