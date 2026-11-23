// geokit: the Android-free libraries as their own Gradle build (no Android Gradle Plugin, no SDK).
// The app includes this build with includeBuild("libs") in the root settings.gradle.kts and depends on
// io.github.pennstatecs.geokit:<module> coordinates, which Gradle substitutes with these projects.
// Stand-alone: `..\gradlew.bat test` from libs/ (or `.\gradlew.bat -p libs test` from the repo root).
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        // Same compiler as the app: keep in step with the kotlin-gradle-plugin pin in the root
        // build.gradle.kts and `kotlin` in gradle/libs.versions.toml.
        id("org.jetbrains.kotlin.jvm") version "2.4.21"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
    // One version catalog for the whole repository.
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "geokit"
include(":geokit-core", ":geokit-gnss", ":geokit-receiver-emlid", ":geokit-ar", ":geokit-models")
