package com.droidbridge.experiment.mediaprojection

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlin.concurrent.timer

/**
 * P01 experiment UI.
 *
 * Two buttons and a live readout. The readout exists because the acceptance criteria are measured
 * facts (accepted size, profile, frames, keyframes), and a prototype that cannot show its own
 * measurements cannot produce evidence.
 */
class ExperimentActivity : ComponentActivity() {

    private var statusText by mutableStateOf("idle")
    private var probeText by mutableStateOf("not probed")

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            // Fresh consent, used exactly once (R-04).
            ProjectionService.start(this, result.resultCode, result.data!!)
            statusText = "consent granted; starting service"
        } else {
            statusText = "consent denied by user"
        }
    }

    private val notificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        statusText = if (granted) "notification permission granted" else "notification permission denied"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Probe the encoder on launch and log the result.
        //
        // This runs here rather than in a separate entry point because MediaCodecList needs no
        // permission and no consent, while starting a non-exported activity over adb is blocked
        // on API 31. Logging it from the launcher means the capability half of P01 can be
        // measured on a locked device; the consent half still cannot.
        runCatching { EncoderProbe.probe() }
            .onSuccess { report ->
                report.toReportText().lineSequence()
                    .filter { it.isNotBlank() }
                    .forEach { android.util.Log.i("P01Probe", "PROBE $it") }
                android.util.Log.i("P01Probe", "PROBE_COMPLETE")
            }
            .onFailure { e ->
                android.util.Log.e("P01Probe", "PROBE_FAILED ${e.javaClass.simpleName}: ${e.message}", e)
            }

        setContent {
            MaterialTheme {
                Scaffold { inner ->
                    var tick by remember { mutableIntStateOf(0) }

                    // Poll the service counters. A prototype does not need an architecture.
                    remember {
                        timer(period = 500) { runOnUiThread { tick++ } }
                    }

                    ExperimentScreen(
                        modifier = Modifier.padding(inner),
                        status = statusText,
                        liveEvent = ProjectionService.lastEvent.get(),
                        running = ProjectionService.isRunning.get(),
                        frames = ProjectionService.framesEncoded.get(),
                        bytes = ProjectionService.bytesEncoded.get(),
                        keyframes = ProjectionService.keyframesSeen.get(),
                        probe = probeText,
                        tick = tick,
                        onStart = ::requestStart,
                        onStop = { ProjectionService.stop(this); statusText = "stop requested" },
                        onProbe = { probeText = EncoderProbe.probe().toReportText() },
                    )
                }
            }
        }
    }

    private fun requestStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val manager = getSystemService(MediaProjectionManager::class.java)
        // Explicit system consent every session. Never cached, never replayed.
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }
}

@Composable
private fun ExperimentScreen(
    modifier: Modifier,
    status: String,
    liveEvent: String,
    running: Boolean,
    frames: Int,
    bytes: Long,
    keyframes: Int,
    probe: String,
    tick: Int,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onProbe: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("P01 — MediaProjection capture", style = MaterialTheme.typography.titleLarge)
        Text(
            "Local capture and H.264 encode only. Nothing is transmitted.",
            style = MaterialTheme.typography.bodySmall,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onStart, enabled = !running) { Text("Start capture") }
            OutlinedButton(onClick = onStop, enabled = running) { Text("Stop") }
        }
        OutlinedButton(onClick = onProbe, modifier = Modifier.fillMaxWidth()) {
            Text("Probe encoder capabilities")
        }

        Text("status: $status", style = MaterialTheme.typography.bodyMedium)
        Text("event: $liveEvent", style = MaterialTheme.typography.bodySmall)
        Text("running: $running", style = MaterialTheme.typography.bodySmall)
        Text("frames encoded: $frames", style = MaterialTheme.typography.bodyMedium)
        Text("bytes encoded: $bytes", style = MaterialTheme.typography.bodyMedium)
        Text("keyframes: $keyframes", style = MaterialTheme.typography.bodyMedium)

        if (probe != "not probed") {
            Text("encoder probe", style = MaterialTheme.typography.titleSmall)
            Text(probe, style = MaterialTheme.typography.bodySmall)
        }
        // Referenced so the compiler keeps the tick state read.
        if (tick < 0) Text("unreachable")
    }
}
