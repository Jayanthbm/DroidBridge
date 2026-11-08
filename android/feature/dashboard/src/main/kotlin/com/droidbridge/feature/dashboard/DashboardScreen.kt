package com.droidbridge.feature.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.droidbridge.core.common.BuildInfo

/**
 * Phase 0 dashboard.
 *
 * It shows the environment baseline and nothing else. There is no connection affordance, no
 * clipboard control and no mirroring control, because none of those exist yet and a dead button
 * is a lie about the product's state.
 */
@Composable
fun DashboardScreen(buildInfo: BuildInfo) {
    MaterialTheme {
        Scaffold { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "DroidBridge",
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    text = "Phase 0 shell — environment baseline only.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "Android ${buildInfo.androidRelease} (API ${buildInfo.sdkInt})",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = "${buildInfo.manufacturer} ${buildInfo.model} (${buildInfo.device})",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "Build ${buildInfo.buildId}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "${buildInfo.applicationId} ${buildInfo.versionName}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
