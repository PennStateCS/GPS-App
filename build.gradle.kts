// Top-level build file where you can add configuration options common to all sub-projects/modules.
// AGP 9 built-in Kotlin defaults to KGP 2.2.x. Newer libraries (Filament 1.81, kotlin-stdlib 2.4)
// ship Kotlin 2.4 metadata, so raise the compiler here. Keep in step with `kotlin` in libs.versions.toml.
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.hilt.android) apply false
    alias(libs.plugins.ksp) apply false
}
