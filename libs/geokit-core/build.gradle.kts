// geokit-core: Android-free geodesy for SurReal AR (ellipsoids, datum, projections, geoid, local
// frames, accuracy, heading). GPL-3.0 like the rest of the repository.
//
// No version on the Kotlin plugin: libs/settings.gradle.kts pins it (2.4.21, the same compiler the app
// uses through the root build.gradle.kts).
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
    // Geodesics on the ellipsoid (Karney). MIT-licensed, compatible with GPL-3.0.
    implementation(libs.geographiclib)
    testImplementation(libs.junit)
}
