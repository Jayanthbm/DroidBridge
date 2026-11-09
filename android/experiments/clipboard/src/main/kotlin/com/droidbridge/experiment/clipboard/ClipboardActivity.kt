package com.droidbridge.experiment.clipboard

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * P04 experiment UI.
 *
 * Each button runs one row of the P04 matrix and logs the observation. The matrix is deliberately
 * driven by explicit user action: the whole question is what the OS permits when the app *is*
 * focused, so a background read would measure the wrong thing.
 */
class ClipboardActivity : ComponentActivity() {

    private var results by mutableStateOf(listOf<String>())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                Scaffold { inner ->
                    var tick by remember { androidx.compose.runtime.mutableIntStateOf(0) }
                    // Referenced so the state read is not optimised away.
                    if (tick < 0) Text("unreachable")

                    ClipboardScreen(
                        modifier = Modifier.padding(inner),
                        results = results,
                        onAction = ::run,
                    )
                }
            }
        }
    }

    private fun run(action: String) {
        val line = when (action) {
            "write" -> {
                ClipboardProbe.writeText(this, "droidbridge-p04-sample")
                "wrote sample text"
            }
            "read-focused" -> ClipboardProbe.read(this, "activity_focused").toLine()
            "read-empty" -> {
                ClipboardProbe.clear(this)
                ClipboardProbe.read(this, "clipboard_empty").toLine()
            }
            "read-image" -> {
                ClipboardProbe.writeImageOnly(this)
                ClipboardProbe.read(this, "image_only").toLine()
            }
            "read-sensitive" -> {
                ClipboardProbe.writeSensitiveText(this, "p04-secret-value")
                ClipboardProbe.read(this, "sensitive_flag").toLine()
            }
            "read-long" -> {
                val long = "x".repeat(100 * 1024)
                ClipboardProbe.writeText(this, long, "p04-long")
                ClipboardProbe.read(this, "long_100kb").toLine()
            }
            "read-dup" -> {
                ClipboardProbe.writeText(this, "duplicate-value", "p04-dup")
                val first = ClipboardProbe.read(this, "duplicate_first").toLine()
                val second = ClipboardProbe.read(this, "duplicate_second").toLine()
                "first: $first\nsecond: $second"
            }
            "read-delayed" -> {
                // Measures the unfocused row without a broadcast.
                //
                // Android 12+ defers broadcasts to backgrounded apps, and an exported receiver
                // that any app could poke is a lint error worth not having. So the app schedules
                // its own read for after the user has switched away: the read happens with another
                // app in front, which is exactly the condition P04 asks about.
                val appContext = applicationContext
                Handler(Looper.getMainLooper()).postDelayed({
                    val result = ClipboardProbe.read(appContext, "unfocused_delayed")
                    Log.i("P04Clipboard", "DELAYED ${result.toLine()}")
                }, 6_000L)
                "scheduled a read in 6s; switch to another app now"
            }
            else -> "unknown action"
        }
        Log.i("P04Clipboard", "ACTION $action -> $line")
        results = results + "$action: $line"
    }
}

@Composable
private fun ClipboardScreen(
    modifier: Modifier,
    results: List<String>,
    onAction: (String) -> Unit,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("P04 — clipboard read access", style = MaterialTheme.typography.titleLarge)
        Text(
            "Each button runs one matrix row and logs the observation. No permission is declared.",
            style = MaterialTheme.typography.bodySmall,
        )

        listOf(
            "write" to "Write sample text",
            "read-focused" to "Read (activity focused)",
            "read-empty" to "Clear, then read (empty)",
            "read-image" to "Write image, then read",
            "read-sensitive" to "Write sensitive, then read",
            "read-long" to "Write 100 KB, then read",
            "read-dup" to "Write duplicate, read twice",
        ).forEach { (action, label) ->
            OutlinedButton(onClick = { onAction(action) }, modifier = Modifier.fillMaxWidth()) {
                Text(label)
            }
        }

        OutlinedButton(onClick = { onAction("read-delayed") }, modifier = Modifier.fillMaxWidth()) {
            Text("Read in 6s (switch away now)")
        }

        Button(onClick = { onAction("read-focused") }, modifier = Modifier.fillMaxWidth()) {
            Text("Send clipboard (product action)")
        }

        Text("Observations", style = MaterialTheme.typography.titleSmall)
        if (results.isEmpty()) {
            Text("None yet.", style = MaterialTheme.typography.bodySmall)
        }
        results.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
