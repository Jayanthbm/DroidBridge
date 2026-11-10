pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "droidbridge-android"

// `shared/protocol` stays a standalone Kotlin/JVM build so it can be tested anywhere a JDK
// exists (including CI and the cloud agent). The Android app consumes it as a composite build
// instead of a copy, so there is exactly one definition of the wire format.
includeBuild("../shared/protocol") {
    dependencySubstitution {
        substitute(module("com.droidbridge:droidbridge-protocol")).using(project(":"))
    }
}

include(":app")

include(":core:common")
include(":core:protocol")
include(":core:network")
include(":core:pairing")
include(":core:security")

include(":feature:dashboard")
include(":feature:clipboard")
include(":feature:mirroring")
include(":feature:settings")

// Isolated experiments. These are separate installable apps with their own applicationId, never
// depended on by :app, so an experiment can never leak a permission or a code path into the
// production manifest (AGENTS.md: keep experimental code out of production paths).
include(":experiments:media-projection")
include(":experiments:clipboard")
include(":experiments:clipboard-receive")
include(":experiments:discovery")
include(":experiments:session")
include(":experiments:mirroring")
