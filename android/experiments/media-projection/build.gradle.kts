plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// P01 — MediaProjection capture experiment.
//
// A separate installable app with its own applicationId. It is never depended on by :app, so the
// permissions it needs (FOREGROUND_SERVICE_MEDIA_PROJECTION) can never leak into the production
// manifest. This is the isolation rule from AGENTS.md made structural rather than a convention.
android {
    namespace = "com.droidbridge.experiment.mediaprojection"

    defaultConfig {
        applicationId = "com.droidbridge.experiment.mediaprojection"
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:protocol"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
