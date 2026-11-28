package com.jayanth.droidbridge.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jayanth.droidbridge.AppScreen
import com.jayanth.droidbridge.AppViewModel
import com.jayanth.droidbridge.ui.components.AppBackdrop
import com.jayanth.droidbridge.ui.components.AppFooter
import com.jayanth.droidbridge.ui.screens.HomeScreen
import com.jayanth.droidbridge.ui.screens.OnboardingScreen
import com.jayanth.droidbridge.ui.screens.PairingScreen

@Composable
fun DroidBridgeApp(
    initialScreen: String? = null,
    autoPair: Boolean = false,
    onToggleMirroring: () -> Unit = {},
    onOpenBluetoothSettings: () -> Unit = {},
    viewModel: AppViewModel = viewModel(),
) {
    val state = viewModel.state
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(initialScreen, autoPair) {
        viewModel.setAutoPair(autoPair)
        viewModel.applyDebugScreen(initialScreen)
    }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(
            message = message,
            withDismissAction = true,
            duration = SnackbarDuration.Short,
        )
        viewModel.clearMessage()
    }

    BackHandler(enabled = state.screen == AppScreen.Pairing && !state.isPairing) {
        viewModel.back()
    }

    AppBackdrop {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            AnimatedContent(
                targetState = state.screen,
                modifier = Modifier.weight(1f),
                transitionSpec = {
                    val direction = if (targetState.ordinal >= initialState.ordinal) 1 else -1
                    (fadeIn(tween(260)) + slideInHorizontally(tween(340)) { it / 14 * direction }) togetherWith
                        (fadeOut(tween(170)) + slideOutHorizontally(tween(260)) { -it / 18 * direction })
                },
                label = "DroidBridgeScreens",
            ) { screen ->
                when (screen) {
                    AppScreen.Onboarding -> OnboardingScreen(
                        state = state,
                        onTogglePermission = viewModel::togglePermission,
                        onAllowAll = viewModel::allowAllPermissions,
                        onContinue = viewModel::continueFromOnboarding,
                    )
                    AppScreen.Pairing -> PairingScreen(
                        state = state,
                        onBack = viewModel::back,
                        onPair = viewModel::pairWithMac,
                    )
                    AppScreen.Home -> HomeScreen(
                        state = state,
                        onToggleMirroring = onToggleMirroring,
                        onToggleClipboard = viewModel::toggleClipboardSync,
                        onSyncClipboard = viewModel::syncClipboardNow,
                        onDisconnect = viewModel::disconnect,
                        onReconnect = viewModel::reconnect,
                        onPairAnotherMac = viewModel::pairAnotherMac,
                        onOpenBluetoothSettings = onOpenBluetoothSettings,
                    )
                }
            }
            AppFooter()
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 16.dp, vertical = 52.dp),
        ) {
            SnackbarHost(hostState = snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    shape = RoundedCornerShape(18.dp),
                    containerColor = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                )
            }
        }
    }
}
