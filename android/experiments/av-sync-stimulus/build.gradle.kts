plugins {
    alias(libs.plugins.android.application)
}

// P12 A/V offset support app: one trigger produces both a flash and a 1 kHz beep, in a package
// separate from both the capture app and the product, so the captured signal cannot be caused by
// the capturing app itself.
android {
    namespace = "com.droidbridge.experiment.avsync"

    defaultConfig {
        applicationId = "com.droidbridge.experiment.avsync"
        versionCode = 1
        versionName = "0.1.0"
    }
}
