plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// P16 — isolated receiver screen for standard BLE HID keyboard feasibility.
// No Android Bluetooth permission or Accessibility service is needed for OS-delivered keys.
android {
    namespace = "com.droidbridge.experiment.bluetoothhid"

    defaultConfig {
        applicationId = "com.droidbridge.experiment.bluetoothhid"
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
