plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.droidbridge.core.protocol"
}

dependencies {
    // The shared, platform-independent wire format. Resolved through the composite build in
    // settings.gradle.kts, so this is the same source the JVM tests run against.
    api(libs.droidbridge.protocol)

    testImplementation(libs.junit)
}
