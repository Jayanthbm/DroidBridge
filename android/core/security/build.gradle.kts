plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.droidbridge.core.security"
}

dependencies {
    testImplementation(libs.junit)
}
