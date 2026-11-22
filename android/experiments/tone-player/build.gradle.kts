plugins {
    alias(libs.plugins.android.application)
}

// P12 supporting app: a controlled audio source in a *different* package from the capture app.
//
// Cross-app playback is the actual product case — capturing another app's media — so the positive
// result must be produced by a different app, not by the capturing app polling itself. Two policies
// come from one APK: the tone's AudioTrack publishes ALLOW_CAPTURE_BY_ALL or ALLOW_CAPTURE_BY_NONE
// at runtime, so nothing but the policy differs between the two runs.
android {
    namespace = "com.droidbridge.experiment.toneplayer"

    defaultConfig {
        applicationId = "com.droidbridge.experiment.toneplayer"
        versionCode = 1
        versionName = "0.1.0"
    }
}
