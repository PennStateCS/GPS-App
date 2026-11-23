// geokit-jvm-smoke: not a library and never published. Its tests run the geokit libraries the way a
// plain desktop JVM consumer would: each test runtime classpath is only what one library declares at
// runtime (no android-json, no extra org.json), so a missing runtime dependency such as org.json:json
// fails here instead of in someone else's project. Runs with `.\gradlew.bat -p libs test`.
//
// One suite per library, because both libraries bring org.json:json: on a shared classpath one
// library's runtimeOnly line would hide the other's absence.
//   test        -> geokit-receiver-emlid only (src/test)
//   modelsTest  -> geokit-models only        (src/modelsTest); `test` depends on it
//
// No version on the Kotlin plugin: libs/settings.gradle.kts pins it.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Consumed exactly as an outside project would. Do NOT add android-json or org.json to these suites: the
// point is that the libraries' own runtime dependencies are enough.
testing {
    suites {
        named<JvmTestSuite>("test") {
            useJUnit(libs.versions.junit.get())
            dependencies {
                implementation(project(":geokit-receiver-emlid"))
            }
        }
        register<JvmTestSuite>("modelsTest") {
            useJUnit(libs.versions.junit.get())
            dependencies {
                implementation(project(":geokit-models"))
            }
        }
    }
}

tasks.named("test") {
    dependsOn("modelsTest")   // the suite's test task
}
