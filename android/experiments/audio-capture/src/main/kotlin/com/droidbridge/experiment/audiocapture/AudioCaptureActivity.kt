package com.droidbridge.experiment.audiocapture

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity

/**
 * P12 driver.
 *
 * Two controls matter — start the playback capture, and play a controlled tone under a chosen
 * capture policy — and both are also reachable through intent extras so a run can be driven without
 * touching the screen:
 *
 *   adb shell am start -n com.droidbridge.experiment.audiocapture/.AudioCaptureActivity \
 *       --ez startCapture true                  # request RECORD_AUDIO + projection consent, then capture
 *       --es playTone allow                     # ALLOW_CAPTURE_BY_ALL   (expect audio)
 *       --es playTone none                      # ALLOW_CAPTURE_BY_NONE  (expect silence)
 *       --ei toneSeconds 20
 *       --ez stopCapture true
 */
class AudioCaptureActivity : ComponentActivity() {

    companion object {
        private const val TAG = "P12Audio"
    }

    private var status by mutableStateOf("idle")
    private var log by mutableStateOf("")

    private val projectionManager by lazy {
        getSystemService(MediaProjectionManager::class.java)
    }

    /** MediaProjection consent: granted by the user, never synthesised by the app. */
    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val data = result.data!!
            status = "projection consent granted; starting capture"
            note("projection consent granted")
            AudioCaptureService.start(this, result.resultCode, data)
        } else {
            status = "projection consent denied"
            note("projection consent denied (resultCode=${result.resultCode})")
        }
    }

    private val audioLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        note("RECORD_AUDIO granted=$granted")
        if (granted) requestProjection() else status = "RECORD_AUDIO denied"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("P12 — audio playback capture", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Measures which audio Android's playback-capture rules actually deliver.\n" +
                            "Call audio is excluded by the platform (R-15); a playing app can also " +
                            "refuse with ALLOW_CAPTURE_BY_NONE. Both refusals are silent, so the " +
                            "measurement is the PCM itself: per-second RMS and frame counts.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text("status: $status")
                    Button(onClick = { beginCapture() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Start capture (RECORD_AUDIO + projection consent)")
                    }
                    Button(
                        onClick = { AudioCaptureService.stop(this@AudioCaptureActivity); status = "stopping" },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Stop capture") }
                    Button(
                        onClick = { TonePlayer.play(allowed = true); status = "tone: ALLOW_CAPTURE_BY_ALL" },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Play tone — capturable") }
                    Button(
                        onClick = { TonePlayer.play(allowed = false); status = "tone: ALLOW_CAPTURE_BY_NONE" },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Play tone — refused") }
                    Text("log:\n$log", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        handleExtras(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleExtras(intent)
    }

    private fun handleExtras(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra("stopCapture", false)) {
            AudioCaptureService.stop(this)
            status = "stopping"
        }
        val policy = intent.getStringExtra("playTone")
        if (policy != null) {
            val seconds = intent.getIntExtra("toneSeconds", 20)
            val allowed = policy == "allow"
            TonePlayer.play(allowed = allowed, seconds = seconds)
            status = "tone: ${if (allowed) "ALLOW_CAPTURE_BY_ALL" else "ALLOW_CAPTURE_BY_NONE"} (${seconds}s)"
            note("tone requested policy=$policy seconds=$seconds")
        }
        if (intent.getBooleanExtra("startCapture", false)) {
            beginCapture()
        }
    }

    private fun beginCapture() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            requestProjection()
        } else {
            status = "requesting RECORD_AUDIO"
            audioLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    /**
     * Playback capture is authorised by a MediaProjection consent. The consent is the user's; this
     * only asks for it.
     */
    private fun requestProjection() {
        status = "requesting projection consent"
        try {
            projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
        } catch (e: Exception) {
            Log.e(TAG, "projection request failed: ${e.javaClass.simpleName}: ${e.message}")
            status = "projection request failed: ${e.message}"
        }
    }

    private fun note(line: String) {
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        log = (log + "\n$stamp $line").takeLast(2000)
        Log.i(TAG, line)
    }
}
