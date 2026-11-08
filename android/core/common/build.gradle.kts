plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.droidbridge.core.common"
}

dependencies {
    testImplementation(libs.junit)
}
