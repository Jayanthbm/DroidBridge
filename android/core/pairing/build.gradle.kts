plugins {
    alias(libs.plugins.android.library)
}

// The pairing rules (Phase 2). Deliberately free of Android and socket types: NETWORK_PROTOCOL.md
// requires the "peer is trusted only after the code is compared" rule to hold, and a pure state
// machine makes that rule testable on the JVM instead of only on a device.
android {
    namespace = "com.droidbridge.core.pairing"
}

dependencies {
    testImplementation(libs.junit)
}
