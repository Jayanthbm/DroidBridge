plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// P05 — clipboard Mac → Android.
//
// A separate installable app, never depended on by :app. The transport here is a deliberately
// simple framed TCP socket: P03 already owns TLS/pairing, and P05 measures the Android clipboard
// write behaviour rather than re-testing transport. The report states this explicitly.
android {
    namespace = "com.droidbridge.experiment.clipboardreceive"

    defaultConfig {
        applicationId = "com.droidbridge.experiment.clipboardreceive"
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
