plugins {
    kotlin("jvm") version "2.1.0"
}

group = "com.droidbridge"
version = "0.1.0"

repositories {
    mavenCentral()
}

kotlin {
    // 17, not 21, and deliberately so.
    //
    // The only consumer of this module is the Android build, which compiles and tests against
    // Java 17 (android/build.gradle.kts). A 21 toolchain here emits class file version 65, which
    // an Android unit-test JVM on 17 refuses to load -- every test fails with
    // UnsupportedClassVersionError rather than anything resembling the real problem. The module
    // is consumed as a composite build, so this is not a shared setting that Gradle reconciles:
    // it has to match by hand, and this comment is the reminder.
    //
    // The macOS app does not consume this module (it is Swift and implements NETWORK_PROTOCOL.md
    // directly), so there is no second consumer to satisfy.
    jvmToolchain(17)
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
    }
}
