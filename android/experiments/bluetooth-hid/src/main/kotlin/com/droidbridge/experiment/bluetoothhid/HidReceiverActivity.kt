package com.droidbridge.experiment.bluetoothhid

import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp

/**
 * P16 target-device receiver. It observes only hardware key events delivered by Android's normal
 * input dispatcher. It cannot generate input, inspect another app, or read clipboard/screen data.
 */
class HidReceiverActivity : ComponentActivity() {
    private var upCount by mutableIntStateOf(0)
    private var downCount by mutableIntStateOf(0)
    private var leftCount by mutableIntStateOf(0)
    private var rightCount by mutableIntStateOf(0)
    private val observations = mutableStateListOf<String>()
    private var typedText by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        record("P16 receiver ready; focus this screen before sending a test key")
        setContent {
            MaterialTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp)
                        // Preview runs from the root towards the focused view, so arrows are counted
                        // before the text field consumes them for cursor movement. Returning false
                        // leaves the field's normal behaviour untouched.
                        .onPreviewKeyEvent { event ->
                            val native = event.nativeKeyEvent
                            when (event.type) {
                                KeyEventType.KeyDown -> arrowLabel(native.keyCode)?.let { label ->
                                    if (native.repeatCount == 0) {
                                        bump(label)
                                        record("$label down")
                                    }
                                }

                                KeyEventType.KeyUp -> arrowLabel(native.keyCode)?.let { record("$it up") }
                                else -> Unit
                            }
                            false
                        },
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Arrow-key receiver — P16 BLE / P17 Classic", style = MaterialTheme.typography.headlineSmall)
                    Text("This test screen records only arrow-key down/up events.")
                    Text("Keep this screen visible and focused while testing.")
                    Text("UP: $upCount    DOWN: $downCount")
                    Text("LEFT: $leftCount    RIGHT: $rightCount")
                    Text("Text entry receiver — tap to focus, then type from the Mac")
                    TextField(
                        value = typedText,
                        onValueChange = { typedText = it },
                        label = { Text("Typed characters") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Test input arrives only over the Bluetooth HID link under test. " +
                            "ADB is used for installation and diagnostics, never to deliver input, " +
                            "and acceptance runs are performed with USB disconnected and debugging off.",
                    )
                    Text("Events:")
                    observations.takeLast(12).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }

    /**
     * Counts one arrow press. The counters used to be bumped in `onKeyDown`, which never ran while
     * the text-entry field held focus: the field consumes arrow keys for cursor movement. Counting
     * in the root's `onPreviewKeyEvent` keeps the counters honest whatever holds focus.
     */
    private fun bump(label: String) {
        when (label) {
            "UP" -> upCount += 1
            "DOWN" -> downCount += 1
            "LEFT" -> leftCount += 1
            "RIGHT" -> rightCount += 1
        }
    }

    private fun arrowLabel(keyCode: Int): String? = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> "UP"
        KeyEvent.KEYCODE_DPAD_DOWN -> "DOWN"
        KeyEvent.KEYCODE_DPAD_LEFT -> "LEFT"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "RIGHT"
        else -> null
    }

    private fun record(message: String) {
        // Values are fixed key names and counts only; no user text/content is read or logged.
        Log.i(TAG, message)
        observations.add(message)
        while (observations.size > 32) observations.removeAt(0)
    }

    private companion object {
        const val TAG = "P16HidReceiver"
    }
}
