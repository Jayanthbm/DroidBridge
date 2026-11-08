package com.droidbridge.core.common

import android.os.Build

/**
 * The facts a user or a test report needs to identify exactly what they are running.
 *
 * Deliberately small and dependency-free: it is the one place the app reads `Build`, so a test
 * report can quote a single source instead of scattering `Build.VERSION` reads through the UI.
 */
data class BuildInfo(
    val applicationId: String,
    val versionName: String,
    val androidRelease: String,
    val sdkInt: Int,
    val manufacturer: String,
    val model: String,
    val device: String,
    val buildId: String,
) {
    companion object {
        /**
         * Reads the running platform. Kept as a function rather than a constant so it reflects
         * the device it is actually executing on, which is the whole point of a baseline report.
         */
        fun describe(
            applicationId: String = "com.droidbridge.app",
            versionName: String = "0.1.0",
        ): BuildInfo = BuildInfo(
            applicationId = applicationId,
            versionName = versionName,
            androidRelease = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            device = Build.DEVICE,
            buildId = Build.DISPLAY,
        )
    }
}
