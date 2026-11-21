// geokit-gnss: Android-free NMEA 0183 parsing, fusion, satellite inventory, accuracy and capture for
// SurReal AR. GPL-3.0 like the rest of the repository.
//
// No version on the Kotlin plugin: the compiler is pinned through the buildscript classpath in the
// root build.gradle.kts (kotlin-gradle-plugin 2.4.21), the same one the app and geokit-core use.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

group = "io.github.pennstatecs.geokit"
version = providers.gradleProperty("GEOKIT_VERSION").get()

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    // Every public declaration needs explicit visibility and return type (library API discipline).
    explicitApi()
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":geokit-core"))
    // SharedFlow appears in the source contracts, so coroutines are part of the API.
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
