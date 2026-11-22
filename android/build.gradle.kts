// Root build file.
//
// Plugin declarations plus the shared Android configuration every module inherits, so SDK levels
// and the lint policy exist in exactly one place and cannot drift between modules. Justification
// for the numbers lives in Docs/TECHNICAL_DECISIONS.md:
//   - compileSdk 37.1 / targetSdk 37 — ADR-021 (Android 17; above Play's API 36 floor). API 37 ships
//     minor versions, and Compose 2026.11 requires the 37.1 AAR metadata, hence compileSdkMinor.
//   - minSdk 31 — Android 12, the OS on the available test device and the level at which the
//     clipboard and foreground-service rules in the specs are the current ones.

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

subprojects {
    // AGP 9 uses the new DSL types; the legacy com.android.build.gradle.* extensions are
    // deprecated and removed in AGP 10.
    plugins.withId("com.android.library") {
        extensions.configure<com.android.build.api.dsl.LibraryExtension>("android") {
            compileSdk = libs.versions.androidCompileSdk.get().toInt()
            // API 37 ships minor versions (37.0, 37.1, ...); Compose 2026.11 requires 37.1+.
            compileSdkMinor = libs.versions.androidCompileSdkMinor.get().toInt()
            defaultConfig {
                minSdk = libs.versions.androidMinSdk.get().toInt()
            }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
            // Lint is a gate, not decoration. A warning nobody reads is worse than no warning.
            lint {
                warningsAsErrors = true
                abortOnError = true
                checkDependencies = true
                lintConfig = rootProject.file("lint.xml")
            }
        }
    }

    plugins.withId("com.android.application") {
        extensions.configure<com.android.build.api.dsl.ApplicationExtension>("android") {
            compileSdk = libs.versions.androidCompileSdk.get().toInt()
            // API 37 ships minor versions (37.0, 37.1, ...); Compose 2026.11 requires 37.1+.
            compileSdkMinor = libs.versions.androidCompileSdkMinor.get().toInt()
            defaultConfig {
                minSdk = libs.versions.androidMinSdk.get().toInt()
                targetSdk = libs.versions.androidTargetSdk.get().toInt()
            }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
            lint {
                warningsAsErrors = true
                abortOnError = true
                checkDependencies = true
                lintConfig = rootProject.file("lint.xml")
            }
        }
    }
}
