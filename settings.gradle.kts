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
