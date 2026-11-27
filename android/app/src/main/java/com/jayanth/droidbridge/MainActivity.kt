package com.jayanth.droidbridge

import android.Manifest
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.jayanth.droidbridge.mirror.MirrorService
import com.jayanth.droidbridge.ui.DroidBridgeApp
import com.jayanth.droidbridge.ui.theme.DroidBridgeTheme

class MainActivity : ComponentActivity() {
    private val projectionManager by lazy {
        getSystemService(MediaProjectionManager::class.java)
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            MirrorService.start(this, result.resultCode, data)
        }
    }

    private val notificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        launchProjectionConsent()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val initialScreen = intent.getStringExtra("screen")
        val autoPair = intent.getBooleanExtra("autoPair", false)
        setContent {
            DroidBridgeTheme {
                DroidBridgeApp(
                    initialScreen = initialScreen,
                    autoPair = autoPair,
                    onToggleMirroring = ::toggleMirroring,
                )
            }
        }
        if (intent.getBooleanExtra("autoMirror", false)) {
            window.decorView.post { toggleMirroring() }
        }
    }

    private fun toggleMirroring() {
        if (MirrorService.isRunning) {
            MirrorService.stop(this)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            launchProjectionConsent()
        }
    }

    private fun launchProjectionConsent() {
        projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }
}
