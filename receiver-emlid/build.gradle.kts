// receiver-emlid: Emlid Reach RS2+ / RS4 / RS4 Pro status (battery, device info, corrections) over the
// receiver's HTTP API and socket.io broadcasts, behind gnss-core's ReceiverDevice. GPL-3.0 like the repo.
//
// No version on the Kotlin plugin: the compiler is pinned through the buildscript classpath in the
// root build.gradle.kts (kotlin-gradle-plugin 2.4.21), the same one the app and the other modules use.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

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
    // ReceiverDevice and the neutral models are part of this module's API.
    api(project(":gnss-core"))
    // Reach live corrections arrive as socket.io broadcasts. Android ships org.json on the platform,
    // so the artifact is excluded here exactly as the app always did.
    implementation(libs.socket.io.client) {
        exclude(group = "org.json", module = "json")
    }
    // org.json: compile and test against the artifact, but don't ship it — the APK uses Android's
    // platform copy (app/build.gradle.kts also excludes org.json:json). See README.
    compileOnly(libs.org.json)
    testImplementation(libs.org.json)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}