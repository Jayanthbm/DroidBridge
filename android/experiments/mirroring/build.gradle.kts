plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// P08 — end-to-end screen mirroring experiment.
//
// Same isolation rule as every other experiment: its own applicationId, never depended on by :app,
// so the media-projection foreground service and the capture permissions it needs cannot reach the
// production manifest until a phase promotes them deliberately.
//
// minSdk 37 is deliberate: the discovery half uses the API 37 picker and ACCESS_LOCAL_NETWORK, both
// of which this experiment measures as part of the real path. A lower minSdk would let lint assume a
// fallback exists and hide the finding.
android {
    namespace = "com.droidbridge.experiment.mirroring"

    defaultConfig {
        applicationId = "com.droidbridge.experiment.mirroring"
        versionCode = 1
        versionName = "0.1.0"
        minSdk = 37
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core:common"))
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
