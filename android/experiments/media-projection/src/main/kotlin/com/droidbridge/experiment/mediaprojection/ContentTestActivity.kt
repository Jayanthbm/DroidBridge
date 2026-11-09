package com.droidbridge.experiment.mediaprojection

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * A white test surface for the secure-content comparison.
 *
 * With `secure=true` the window carries FLAG_SECURE, which is the platform's own protection. The
 * experiment then samples what the MediaProjection consumer surface receives: a protected window
 * must arrive as black (or absent), never as its real pixels. Nothing here attempts to bypass the
 * flag; the test asserts that it works.
 *
 * White background is deliberate: a captured bright frame and a captured protected frame are then
 * unambiguous (mean luma near 255 versus near 0), which the darker app UI would not give.
 */
class ContentTestActivity : ComponentActivity() {

    companion object {
        const val EXTRA_SECURE = "secure"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val secure = intent.getBooleanExtra(EXTRA_SECURE, false)
        if (secure) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        android.util.Log.i("P01Projection", "CONTENT_TEST secure=$secure")

        setContent {
            MaterialTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.White)
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        if (secure) "P01 SECURE content test" else "P01 NORMAL content test",
                        color = Color.Black,
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Text(
                        if (secure) {
                            "FLAG_SECURE is set on this window. The capture consumer should see black."
                        } else {
                            "No FLAG_SECURE. The capture consumer should see a bright white frame."
                        },
                        color = Color.Black,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        "Synthetic content only — no private data.",
                        color = Color.Black,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(onClick = { finish() }) { Text("Back to P01") }
                }
            }
        }
    }
}
