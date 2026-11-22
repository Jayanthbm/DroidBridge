plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// P12 — audio playback capture experiment.
//
// Isolated like every other experiment: its own applicationId, never depended on by :app, so
// RECORD_AUDIO and the mediaProjection foreground service type cannot leak into the production
// manifest. The permission is approved for this experiment only (owner-approved 2026-11-22) and is
// recorded as experiment-scoped in Docs/PERMISSIONS_MATRIX.md.
android {
    namespace = "com.droidbridge.experiment.audiocapture"

    defaultConfig {
        applicationId = "com.droidbridge.experiment.audiocapture"
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core:common"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)

    testImplementation(libs.junit)
}
