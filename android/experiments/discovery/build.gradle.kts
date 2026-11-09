plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// P02 — LAN discovery experiment (Android side).
//
// A separate installable app with its own applicationId, never depended on by :app. The permission
// this prototype needs (ACCESS_LOCAL_NETWORK) therefore cannot leak into the production manifest,
// which is the AGENTS.md isolation rule made structural.
//
// compileSdk is 37 because DiscoveryRequest and AdvertisingRequest only exist from API 37. The
// experiment is deliberately not minSdk-gated with fallbacks: its whole purpose is to measure the
// API 37 picker path, and a fallback would hide the result.
android {
    namespace = "com.droidbridge.experiment.discovery"

    defaultConfig {
        applicationId = "com.droidbridge.experiment.discovery"
        versionCode = 1
        versionName = "0.1.0"
        // API 37 only, deliberately. DiscoveryRequest, AdvertisingRequest and
        // ACCESS_LOCAL_NETWORK exist only from 37; this prototype measures that path and a
        // fallback would hide the result. Declaring it here is what makes the API guards real.
        minSdk = 37
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
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
