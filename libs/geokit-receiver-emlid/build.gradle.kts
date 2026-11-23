// geokit-receiver-emlid: Emlid Reach RS2+ / RS4 / RS4 Pro status (battery, device info, corrections) over the
// receiver's HTTP API and socket.io broadcasts, behind geokit-gnss's ReceiverDevice. GPL-3.0 like the repo.
//
// No version on the Kotlin plugin: the compiler is pinned through the buildscript classpath in the
// root build.gradle.kts (kotlin-gradle-plugin 2.4.21), the same one the app and the other modules use.
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
    // ReceiverDevice and the neutral models are part of this module's API.
    api(project(":geokit-gnss"))
    // Reach live corrections arrive as socket.io broadcasts. The client stays on 1.0.1 (EIO=3): the Reach
    // firmware can't talk to the 2.x (EIO=4) client - see the pin in libs.versions.toml. Android ships
    // org.json on the platform, so the artifact is excluded here exactly as the app always did.
    implementation(libs.socket.io.client) {
        exclude(group = "org.json", module = "json")
    }
    // org.json: compile and test against Android's own org.json API (android-json, packaged as a jar).
    // At runtime a plain JVM consumer gets org.json:json (the version socket.io-client's POM asks for);
    // the app excludes it again so the APK uses the Android platform copy. See README.
    compileOnly(libs.android.json)
    runtimeOnly(libs.org.json)
    testImplementation(libs.android.json)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// Tests keep running against android-json only (same API and behaviour as the device), so the
// runtimeOnly org.json:json is kept off the test runtime classpath to avoid two org.json copies.
configurations.named("testRuntimeClasspath") {
    exclude(group = "org.json", module = "json")
}
