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
import androidx.activity.viewModels
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
import androidx.compose.runtime.DisposableEffect
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
 * The readout exists because the acceptance criteria are measured facts (accepted size, profile,
 * frames, keyframes, resize events), and a prototype that cannot show its own measurements cannot
 * produce evidence.
 *
 * Three controls deserve a word:
 * - **Start pixel probe** measures captured luminance instead of encoding. It is how the
 *   FLAG_SECURE test is answered: the platform either delivers protected pixels or it does not.
 * - **Test consent-token reuse** replays the last consent `Intent` after a session has ended. The
 *   expected result is a `SecurityException`; a returned projection would be a platform surprise.
 * - **Content test (normal / secure)** opens a white screen with or without FLAG_SECURE so the
 *   pixel probe can compare the two.
 */
class ExperimentActivity : ComponentActivity() {

    private var statusText by mutableStateOf("idle")
    private var probeText by mutableStateOf("not probed")

    /** Survives rotation; see ExperimentViewModel for why that matters. */
    private val sessionState: ExperimentViewModel by viewModels()

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            // Fresh consent, used exactly once (R-04). The token is retained only so the reuse
            // test can prove it is refused; it is never used to start a second session.
            sessionState.lastResultCode = result.resultCode
            sessionState.lastConsentData = result.data
            ProjectionService.start(this, result.resultCode, result.data!!, sessionState.pendingMode)
            statusText = "consent granted; starting service (mode=${sessionState.pendingMode})"
        } else {
            statusText = "consent denied by user"
        }
    }

    private val notificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        statusText = if (granted) {
            "notification permission granted; opening capture consent"
        } else {
            "notification permission denied; capture still works, FGS notification hidden"
        }
        // Chain instead of launching both dialogs at once: on the S24 Ultra the permission dialog
        // and the capture dialog overlapped, which makes the consent sequence ambiguous.
        launchProjectionConsent()
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

                    // Poll the service counters. A prototype does not need an architecture, but
                    // it does need to stop the timer when the activity is recreated: a leaked
                    // timer keeps a destroyed activity reachable.
                    DisposableEffect(Unit) {
                        val poller = timer(period = 500) { runOnUiThread { tick++ } }
                        onDispose { poller.cancel() }
                    }

                    ExperimentScreen(
                        modifier = Modifier.padding(inner),
                        status = statusText,
                        liveEvent = ProjectionService.lastEvent.get(),
                        running = ProjectionService.isRunning.get(),
                        mode = ProjectionService.mode.get(),
                        frames = ProjectionService.framesEncoded.get(),
                        bytes = ProjectionService.bytesEncoded.get(),
                        keyframes = ProjectionService.keyframesSeen.get(),
                        size = "${ProjectionService.encodedWidth.get()}x${ProjectionService.encodedHeight.get()}",
                        maxFrameGapMs = ProjectionService.maxFrameGapMs.get(),
                        maxKeyframeGapMs = ProjectionService.maxKeyframeGapMs.get(),
                        resizes = ProjectionService.resizeEvents.get(),
                        lastResize = ProjectionService.lastResize.get(),
                        visibility = ProjectionService.lastVisibility.get(),
                        outputProfile = ProjectionService.outputProfile.get(),
                        outputLevel = ProjectionService.outputLevel.get(),
                        outputBitrateMode = ProjectionService.outputBitrateMode.get(),
                        stopReason = ProjectionService.stopReason.get(),
                        tokenReuse = ProjectionService.lastTokenReuse.get(),
                        pixelMean = ProjectionService.pixelMean.get(),
                        pixelMin = ProjectionService.pixelMin.get(),
                        pixelMax = ProjectionService.pixelMax.get(),
                        pixelBlack = ProjectionService.pixelBlackSamples.get(),
                        pixelBright = ProjectionService.pixelBrightSamples.get(),
                        pixelSamples = ProjectionService.pixelSamples.get(),
                        probe = probeText,
                        onStart = { requestStart(ProjectionService.MODE_ENCODE) },
                        onStartPixel = { requestStart(ProjectionService.MODE_PIXEL) },
                        onStop = { ProjectionService.stop(this); statusText = "stop requested" },
                        onProbe = { probeText = EncoderProbe.probe().toReportText() },
                        onBisect = { probeText = FormatBisect.run() },
                        onReuseToken = ::testTokenReuse,
                        onNormalContent = { openContentTest(secure = false) },
                        onSecureContent = { openContentTest(secure = true) },
                        tick = tick,
                    )
                }
            }
        }
    }

    /**
     * Starts a session. Notification permission is requested first and the capture consent is
     * launched from its result, never in parallel with it.
     */
    private fun requestStart(serviceMode: String) {
        sessionState.pendingMode = serviceMode
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        launchProjectionConsent()
    }

    private fun launchProjectionConsent() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        // Explicit system consent every session. Never cached, never replayed.
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    /**
     * Replays the last consent token after its session has ended.
     *
     * Android documents that a result Intent is single-use. R-04 records the failure mode, so this
     * test asserts the exception rather than discovering it in production.
     */
    private fun testTokenReuse() {
        val data = sessionState.lastConsentData
        if (data == null) {
            ProjectionService.lastTokenReuse.set("no token captured yet")
            statusText = "token reuse: no token captured yet"
            return
        }
        if (!ProjectionService.isRunning.get()) {
            statusText = "token reuse: start a session first; the probe must run inside a " +
                "mediaProjection foreground service"
            return
        }
        // The probe must run inside the active mediaProjection service: getMediaProjection
        // refuses to run at all without an active projection, and the mediaProjection FGS type
        // cannot be started without one either. See ProjectionService.ACTION_TEST_REUSE.
        ProjectionService.testTokenReuse(this, sessionState.lastResultCode, data)
        statusText = "token reuse probe started"
    }

    private fun openContentTest(secure: Boolean) {
        startActivity(
            Intent(this, ContentTestActivity::class.java)
                .putExtra(ContentTestActivity.EXTRA_SECURE, secure),
        )
    }
}

@Composable
private fun ExperimentScreen(
    modifier: Modifier,
    status: String,
    liveEvent: String,
    running: Boolean,
    mode: String,
    frames: Int,
    bytes: Long,
    keyframes: Int,
    size: String,
    maxFrameGapMs: Long,
    maxKeyframeGapMs: Long,
    resizes: Int,
    lastResize: String,
    visibility: String,
    outputProfile: Int,
    outputLevel: Int,
    outputBitrateMode: Int,
    stopReason: String,
    tokenReuse: String,
    pixelMean: Int,
    pixelMin: Int,
    pixelMax: Int,
    pixelBlack: Int,
    pixelBright: Int,
    pixelSamples: Int,
    probe: String,
    onStart: () -> Unit,
    onStartPixel: () -> Unit,
    onStop: () -> Unit,
    onProbe: () -> Unit,
    onBisect: () -> Unit,
    onReuseToken: () -> Unit,
    onNormalContent: () -> Unit,
    onSecureContent: () -> Unit,
    tick: Int,
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
        OutlinedButton(
            onClick = onStartPixel,
            enabled = !running,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Start pixel probe (secure-content test)") }

        OutlinedButton(onClick = onProbe, modifier = Modifier.fillMaxWidth()) {
            Text("Probe encoder capabilities")
        }
        OutlinedButton(onClick = onBisect, modifier = Modifier.fillMaxWidth()) {
            Text("Bisect format keys")
        }
        OutlinedButton(onClick = onReuseToken, modifier = Modifier.fillMaxWidth()) {
            Text("Test consent-token reuse")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onNormalContent, modifier = Modifier.weight(1f)) {
                Text("Normal content test")
            }
            OutlinedButton(onClick = onSecureContent, modifier = Modifier.weight(1f)) {
                Text("Secure content test")
            }
        }

        Text("status: $status", style = MaterialTheme.typography.bodyMedium)
        Text("event: $liveEvent", style = MaterialTheme.typography.bodySmall)
        Text("mode: $mode  running: $running", style = MaterialTheme.typography.bodySmall)
        Text("frames encoded: $frames", style = MaterialTheme.typography.bodyMedium)
        Text("bytes encoded: $bytes", style = MaterialTheme.typography.bodyMedium)
        Text("keyframes: $keyframes  size: $size", style = MaterialTheme.typography.bodyMedium)
        Text(
            "max frame gap: ${maxFrameGapMs}ms  max keyframe gap: ${maxKeyframeGapMs}ms",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "output profile=$outputProfile level=$outputLevel bitrateMode=$outputBitrateMode",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "resizes: $resizes  last: $lastResize  visibility: $visibility",
            style = MaterialTheme.typography.bodySmall,
        )
        Text("stop reason: $stopReason", style = MaterialTheme.typography.bodySmall)
        Text("token reuse: $tokenReuse", style = MaterialTheme.typography.bodySmall)
        Text(
            "pixel mean=$pixelMean min=$pixelMin max=$pixelMax " +
                "black=$pixelBlack bright=$pixelBright samples=$pixelSamples",
            style = MaterialTheme.typography.bodySmall,
        )

        // Reading this state is what makes the poller recompose the screen. Without it the
        // counters stayed at their first values while the service kept encoding.
        if (tick < 0) Text("tick=$tick")

        if (probe != "not probed") {
            Text("encoder probe", style = MaterialTheme.typography.titleSmall)
            Text(probe, style = MaterialTheme.typography.bodySmall)
        }
    }
}
