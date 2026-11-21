// geo-core: Android-free geodesy for SurReal AR (ellipsoids, datum, projections, geoid, local
// frames, accuracy, heading). GPL-3.0 like the rest of the repository.
//
// No version on the Kotlin plugin: the compiler is pinned through the buildscript classpath in the
// root build.gradle.kts (kotlin-gradle-plugin 2.4.21), the same one the app uses.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    // Warnings while code is moved in unchanged; switched to strict explicitApi() once the public
    // surface is settled.
    explicitApiWarning()
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(libs.junit)
}

tasks.test {
    // GEOID18 test grid lives with the app (app/src/main/assets); not copied into the module.
    systemProperty("geoid18.path", rootProject.file("app/src/main/assets/geoid/geoid18.srgt").absolutePath)
}
