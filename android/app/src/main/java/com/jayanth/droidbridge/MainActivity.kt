package com.jayanth.droidbridge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.jayanth.droidbridge.ui.DroidBridgeApp
import com.jayanth.droidbridge.ui.theme.DroidBridgeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val initialScreen = intent.getStringExtra("screen")
        setContent {
            DroidBridgeTheme {
                DroidBridgeApp(initialScreen = initialScreen)
            }
        }
    }
}
