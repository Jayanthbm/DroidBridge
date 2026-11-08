plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// P04 — clipboard read-access experiment.
//
// A separate installable app, never depended on by :app. It declares no permission at all: the
// point of P04 is to establish when Android permits a clipboard read, and adding a permission
// would change the thing being measured.
android {
    namespace = "com.droidbridge.experiment.clipboard"

    defaultConfig {
        applicationId = "com.droidbridge.experiment.clipboard"
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core:common"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
