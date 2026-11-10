plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// P03 — secure session experiment.
//
// A separate installable app with its own applicationId, never depended on by :app, so nothing it
// declares can reach the production manifest. This is the AGENTS.md isolation rule made structural.
//
// compileSdk 37 because the discovery half uses the API 37 picker (P02). The TLS half works on any
// supported level, but the prototype is the integrated path, so it targets the device it runs on.
android {
    namespace = "com.droidbridge.experiment.session"

    defaultConfig {
        applicationId = "com.droidbridge.experiment.session"
        versionCode = 1
        versionName = "0.1.0"
        // API 37 only, deliberately, exactly as the P02 discovery experiment does. The integrated
        // path this experiment exists to measure uses DiscoveryRequest (37-only) and
        // ACCESS_LOCAL_NETWORK (37-only); a lower minSdk would let lint believe those calls can run
        // on API 31 and would invite a fallback that hides the result. Session lint is now part of
        // verify-all.sh, which is how these findings surfaced at all.
        minSdk = 37
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:protocol"))
    // The identity and session now live in their production modules (P08 move), so this
    // experiment proves the same code the mirroring path uses rather than its own copy.
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
