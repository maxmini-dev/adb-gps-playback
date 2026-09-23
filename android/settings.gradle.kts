pluginManagement {
    repositories {
        google()
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

rootProject.name = "gps-playback-android"

// Pure-Kotlin logic (geo, GTFS parsing, playback engine). No Android
// dependencies, so its tests run on a plain JVM: ./gradlew :core:test
include(":core")
include(":app")
