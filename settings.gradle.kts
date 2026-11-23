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

plugins {
    // Automatic JDK provisioning for toolchains
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

// ❌ Remove the temp-dir block (KAPT workaround no longer needed with KSP)
// gradle.settingsEvaluated {
//     val tmp = file(".gradle-tmp/sqlite").apply { mkdirs() }
//     System.setProperty("java.io.tmpdir", tmp.absolutePath)
//     System.setProperty("org.sqlite.tmpdir", tmp.absolutePath)
// }

rootProject.name = "SurveyingApp"
include(":app")
// geokit: the reusable, Android-free libraries (libs/README.md).
include(":geokit-core", ":geokit-gnss", ":geokit-receiver-emlid", ":geokit-ar", ":geokit-models")
project(":geokit-core").projectDir = file("libs/geokit-core")
project(":geokit-gnss").projectDir = file("libs/geokit-gnss")
project(":geokit-receiver-emlid").projectDir = file("libs/geokit-receiver-emlid")
project(":geokit-ar").projectDir = file("libs/geokit-ar")
project(":geokit-models").projectDir = file("libs/geokit-models")
