// Copyright (C) 2026 DroidBridge contributors.
// SPDX-License-Identifier: none — internal feasibility experiment, not for distribution.
package com.droidbridge.experiment.backgroundsession

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * P14 control surface.
 *
 * Manual operation is the default: nothing connects until Start is pressed. The launch-extra harness
 * exists because the survival matrix needs runs that begin without a human tap, and because the
 * background-start test (test 2) has to make the start attempt from an already-backgrounded process.
 *
 * Extras understood by the harness:
 *   --es host <ip>        --ei port <n>        --es pin <hex>
 *   --ez autostart true   start the service immediately from this visible activity (test 1)
 *   --ei backgroundProbeMs <n>
 *                          background the app now, then attempt a start after n ms (test 2)
 */
class MainActivity : ComponentActivity() {

    private val uiHandler = Handler(Looper.getMainLooper())
    private var ticker: Runnable? = null
    private var configBound = false

    private var host by mutableStateOf("")
    private var port by mutableStateOf("")
    private var pin by mutableStateOf("")
    private var statusLine by mutableStateOf("idle")
    private var counterLine by mutableStateOf("")
    private var logText by mutableStateOf("")

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            SessionLog.record("POST_NOTIFICATIONS granted=$granted")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        NotificationContext.context = applicationContext
        ServiceNotifications.register(this)
        SessionLog.record("activity created; sdk=${Build.VERSION.SDK_INT}")

        bindConfigFromExtras(savedInstanceState)
        askForNotificationsIfNeeded()
        handleHarnessExtras()

        setContent {
            MaterialTheme {
                Screen(
                    host = host,
                    port = port,
                    pin = pin,
                    status = statusLine,
                    counters = counterLine,
                    log = logText,
                    onHost = { host = it },
                    onPort = { port = it },
                    onPin = { pin = it },
                    onStart = { startSession("activity") },
                    onStop = { stopSession() },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val runnable = object : Runnable {
            override fun run() {
                statusLine = "state=${SessionLog.state} " +
                    "error=${SessionLog.lastError ?: "(none)"}"
                counterLine = SessionLog.summary()
                logText = SessionLog.snapshot().joinToString("\n")
                uiHandler.postDelayed(this, 1_000)
            }
        }
        ticker = runnable
        uiHandler.post(runnable)
    }

    override fun onStop() {
        ticker?.let { uiHandler.removeCallbacks(it) }
        ticker = null
        super.onStop()
    }

    private fun bindConfigFromExtras(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) {
            val extraHost = intent?.getStringExtra("host")
            val extraPort = intent?.getIntExtra("port", 0) ?: 0
            val extraPin = intent?.getStringExtra("pin")
            host = extraHost ?: Prefs.host(this) ?: ""
            port = (if (extraPort != 0) extraPort else Prefs.port(this)).let {
                if (it == 0) "" else it.toString()
            }
            pin = extraPin ?: Prefs.pin(this) ?: ""
        }
        configBound = true
    }

    private fun askForNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            SessionLog.record("POST_NOTIFICATIONS not required on sdk=${Build.VERSION.SDK_INT}")
            return
        }
        val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        SessionLog.record("POST_NOTIFICATIONS currently granted=$granted")
        if (!granted) requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun handleHarnessExtras() {
        val probeMs = intent?.getIntExtra("backgroundProbeMs", 0) ?: 0
        if (probeMs > 0) {
            SessionLog.record("background probe armed: attempt start in ${probeMs}ms after backgrounding")
            moveTaskToBack(true)
            uiHandler.postDelayed({ probeBackgroundStart() }, probeMs.toLong())
            return
        }
        if (intent?.getBooleanExtra("autostart", false) == true) {
            SessionLog.record("autostart requested from visible activity")
            uiHandler.postDelayed({ startSession("activity-autostart") }, 500)
        }
    }

    /**
     * P14 test 2: the same start call, but from a backgrounded process.
     *
     * Expected on API 31+: ForegroundServiceStartNotAllowedException. The exception type is recorded
     * verbatim rather than described, because "it is not allowed" is the claim under test.
     */
    private fun probeBackgroundStart() {
        try {
            startForegroundService(
                Intent(this, SessionService::class.java).setAction(SessionService.ACTION_START),
            )
            SessionLog.record("background start ACCEPTED (no exception)")
        } catch (e: Exception) {
            SessionLog.fail("background start rejected: ${e.javaClass.name}: ${e.message}")
        }
    }

    private fun startSession(origin: String) {
        val resolvedPort = port.toIntOrNull()
        if (host.isBlank() || resolvedPort == null || pin.isBlank()) {
            SessionLog.fail("start refused: host, port and pin are all required")
            return
        }
        Prefs.save(this, host, resolvedPort, pin)
        val start = Intent(this, SessionService::class.java)
            .setAction(SessionService.ACTION_START)
            .putExtra(SessionService.EXTRA_HOST, host)
            .putExtra(SessionService.EXTRA_PORT, resolvedPort)
            .putExtra(SessionService.EXTRA_PIN, pin)
        try {
            startForegroundService(start)
            SessionLog.record("startForegroundService accepted (origin=$origin)")
        } catch (e: Exception) {
            SessionLog.fail("startForegroundService rejected (origin=$origin): " +
                "${e.javaClass.name}: ${e.message}")
        }
    }

    private fun stopSession() {
        Prefs.setEnabled(this, false)
        startService(
            Intent(this, SessionService::class.java).setAction(SessionService.ACTION_STOP),
        )
        SessionLog.record("stop sent from activity")
    }

    @Composable
    private fun Screen(
        host: String,
        port: String,
        pin: String,
        status: String,
        counters: String,
        log: String,
        onHost: (String) -> Unit,
        onPort: (String) -> Unit,
        onPin: (String) -> Unit,
        onStart: () -> Unit,
        onStop: () -> Unit,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("P14 — background session persistence", style = MaterialTheme.typography.titleMedium)
            Text(
                "Foreground service type connectedDevice. No Bluetooth permission, no battery " +
                    "exemption, no wake lock.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = host,
                onValueChange = onHost,
                label = { Text("Mac host") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = port,
                onValueChange = onPort,
                label = { Text("Port") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = pin,
                onValueChange = onPin,
                label = { Text("Mac peer pin (from the harness printout)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onStart) { Text("Start session") }
                OutlinedButton(onClick = onStop) { Text("Stop") }
            }
            Text(status, style = MaterialTheme.typography.bodyMedium)
            Text(counters, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            Text("Log", style = MaterialTheme.typography.titleSmall)
            Text(log, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
    }
}
