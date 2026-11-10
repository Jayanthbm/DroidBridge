plugins {
    alias(libs.plugins.android.library)
}

// The transport half of the protocol (ADR-012): one mutually authenticated TLS 1.3 connection
// carrying length-prefixed, replay-checked control frames.
//
// This module was created empty in Phase 0 and filled by P08, which needed the same session the
// P03 experiment had proven. The alternative — a second copy of the trust model next to the
// mirroring code — is exactly the drift ADR-011 exists to prevent, so the proven implementation was
// moved here and `:experiments:session` now consumes it.
android {
    namespace = "com.droidbridge.core.network"
}

dependencies {
    // api, not implementation: a consumer of a session needs the control-message types it sends.
    api(project(":core:protocol"))
    implementation(project(":core:security"))

    testImplementation(libs.junit)
}
