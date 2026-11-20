plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// P14 — background session persistence.
//
// A separate installable app, like every other experiment, so the foreground-service permissions it
// declares can never reach the production manifest. It reuses the real TLS session from :core:network
// and the real pin store from :core:security rather than a stand-in, because the question P14 answers
// is whether *this* session survives Doze, not whether a toy socket does.
android {
    namespace = "com.droidbridge.experiment.backgroundsession"

    defaultConfig {
        applicationId = "com.droidbridge.experiment.backgroundsession"
        versionCode = 1
        versionName = "0.1.0"
        // No minSdk override: the root default (31) is deliberate. P14 must run on the Android 12
        // handset that is actually available, unlike the P03 discovery experiment (37-only).
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core:protocol"))
    implementation(project(":core:security"))
    implementation(project(":core:network"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
