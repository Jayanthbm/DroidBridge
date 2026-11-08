package com.droidbridge.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.droidbridge.core.common.BuildInfo
import com.droidbridge.feature.dashboard.DashboardScreen

/**
 * The single activity in the shell.
 *
 * Phase 0 scope: prove the module graph, Compose and the shared protocol dependency all link and
 * run on a device. Navigation, services and feature behaviour arrive in their own phases.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DashboardScreen(buildInfo = BuildInfo.describe())
        }
    }
}
