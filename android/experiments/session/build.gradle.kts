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
