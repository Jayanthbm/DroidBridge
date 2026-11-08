plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.droidbridge.core.network"
}

dependencies {
    testImplementation(libs.junit)
}
