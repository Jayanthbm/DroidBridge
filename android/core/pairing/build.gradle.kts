plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.droidbridge.core.pairing"
}

dependencies {
    testImplementation(libs.junit)
}
