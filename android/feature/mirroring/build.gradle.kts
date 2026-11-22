plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

// Phase 4 — screen capture and streaming.
//
// Promoted from the P08 experiment once P01/P07/P08/P10 had measured the parts: MediaProjection
// lifecycle, a foreground service with the mediaProjection type, surface-fed H.264 (ADR-027),
// Annex-B → AVCC with SPS/PPS in `mirror.format`, and keyframe recovery. The pipeline is the same
// code that produced that evidence; what changes here is who owns the transport.
//
// The service does NOT own a socket. It reads frames and calls `MirrorTransport.send`, which the app
// binds to the authenticated session it already has. That is what stops the product from needing a
// second connection, a second pin, and a second set of negotiated codes just to show the screen.
android {
    namespace = "com.droidbridge.feature.mirroring"

    defaultConfig {
        minSdk = 31
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
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)

    testImplementation(libs.junit)
}
