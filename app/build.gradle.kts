// Module: app
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)   // ✅ apply in this module
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.dokka)
    // Developer API reference docs (Dokka Gradle Plugin v2). HTML, published to GitHub Pages by CI.
}

// ── Git / build metadata ──────────────────────────────────────────────────────
// Uses providers.exec{} — the configuration-cache-safe API for external processes.
// Returns empty string on any failure so the build never breaks without Git.
fun gitCommand(vararg args: String): String {
    val cmd = if (System.getProperty("os.name").lowercase().contains("windows"))
        listOf("cmd", "/c") + args.toList()
    else
        args.toList()
    return try {
        providers.exec {
            commandLine(cmd)
            workingDir = rootProject.projectDir
            isIgnoreExitValue = true
        }.standardOutput.asText.get().trim()
    } catch (_: Exception) { "" }
}

val gitHash    = gitCommand("git", "rev-parse", "--short", "HEAD").ifEmpty { "no-git" }
val gitBranch  = gitCommand("git", "rev-parse", "--abbrev-ref", "HEAD").ifEmpty { "unknown" }
val gitDirty   = gitCommand("git", "status", "--porcelain").isNotEmpty()
val buildTime  = SimpleDateFormat("yyyy-MM-dd HH:mm").format(Date())
// Use git commit count as the local build number; fall back to 1 if git is absent.
val buildNumber = gitCommand("git", "rev-list", "--count", "HEAD").toIntOrNull() ?: 1

// Load local.properties (for Maps key etc.)
val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(localPropertiesFile.inputStream())
}

// Shared DEBUG keystore committed to the repo so every developer signs local debug builds with the
// same SHA-1 (required for the Google Maps API key Android restriction). DEBUG ONLY — never used
// for release signing. See docs/development-setup.md for generation instructions.
val sharedDebugKeystore = rootProject.file("keystores/shared-debug.keystore")

android {
    namespace = "app.surrealar"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.surrealar"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0-dev"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Local build metadata — stamped at compile time from the git working tree.
        // Change versionName above manually for human-readable release tagging.
        buildConfigField("String",  "BUILD_GIT_HASH",   "\"$gitHash\"")
        buildConfigField("String",  "BUILD_GIT_BRANCH", "\"$gitBranch\"")
        buildConfigField("boolean", "BUILD_GIT_DIRTY",  "$gitDirty")
        buildConfigField("String",  "BUILD_TIME",       "\"$buildTime\"")
        buildConfigField("int",     "BUILD_NUMBER",     "$buildNumber")

        manifestPlaceholders["GOOGLE_MAPS_API_KEY"] =
            localProperties.getProperty("GOOGLE_MAPS_API_KEY", "YOUR_API_KEY_HERE")
    }

    packaging {
        jniLibs { useLegacyPackaging = false }
        resources {
            excludes += listOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*"
            )
        }
    }

    signingConfigs {
        // Override the auto-generated per-developer debug key with the shared, committed one so
        // all local debug builds have an identical SHA-1. storeFile is read lazily by AGP, so a
        // missing file does not break configuration — the verifyDebugKeystore task below fails
        // first with a clear message. RELEASE signing is intentionally NOT configured here.
        getByName("debug") {
            storeFile = sharedDebugKeystore
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            // Release uses the default/none signing config — NOT the shared debug keystore.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            ndk { debugSymbolLevel = "symbol_table" }
        }
        debug {
            signingConfig = signingConfigs.getByName("debug")
            ndk { debugSymbolLevel = "symbol_table" }
            // First-party (JaCoCo-backed) unit-test coverage. Generates the
            // `createDebugUnitTestCoverageReport` task with HTML/XML output. No extra plugin.
            enableUnitTestCoverage = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures { viewBinding = true; buildConfig = true }

    bundle {
        language { enableSplit = true }
        density { enableSplit = true }
        abi { enableSplit = true }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Pure-JVM unit tests touch production code that logs via android.util.Log. Return defaults
        // (Log.w -> 0, etc.) for unmocked framework calls instead of throwing "not mocked".
        unitTests.isReturnDefaultValues = true
    }

    // Expose the exported Room schemas to instrumented tests so MigrationTestHelper can load them.
    sourceSets {
        getByName("androidTest") {
            assets.directories.add("$projectDir/schemas")
        }
        // The NMEA fixtures live with geokit-gnss; ReplayAdapterTest and AndroidGnssLoggerTest replay them too.
        getByName("test") {
            resources.directories.add("$rootDir/libs/geokit-gnss/src/test/resources")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.play.services.maps)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)

    // 3D rendering (Filament)
    implementation(libs.filament.android)
    implementation(libs.filament.utils.android)

    // Room (KSP)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.play.services.location)
    implementation(libs.arcore)
    implementation(libs.androidx.swiperefreshlayout)

    // Hilt (KSP)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // (Optional for tests using Hilt)
    // kspAndroidTest(libs.hilt.compiler)
    // kspTest(libs.hilt.compiler)

    // Coroutines / DataStore / misc
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.jmdns)

    // Socket.IO client for Reach RS2+ live data (navigation/stream_status broadcasts) now comes in
    // through :geokit-receiver-emlid (CVE-fixed 2.x line, org.json excluded there).

    // Android-free geodesy (ellipsoids, datum, projections, geoid, local frames, accuracy, heading)
    implementation(project(":geokit-core"))
    implementation(project(":geokit-gnss"))
    // Emlid Reach receiver status (HTTP API + socket.io) behind ReceiverDevice.
    implementation(project(":geokit-receiver-emlid"))

    // Desugaring
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // --- Testing ---
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.junit)
    testImplementation(libs.mockito.core)
    debugImplementation(libs.androidx.fragment.testing)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    // Room migration testing (MigrationTestHelper)
    androidTestImplementation(libs.androidx.room.testing)

}

// KSP args
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
    arg("room.incremental", "true")
}

// Fail debug builds with a clear, actionable message when the shared debug keystore is missing,
// instead of a confusing keystore-not-found stack trace at signing time. Wired only to debug
// builds (preDebugBuild), so release builds are unaffected. Config-cache safe.
val verifyDebugKeystore = tasks.register("verifyDebugKeystore") {
    val keystore = sharedDebugKeystore
    val relativePath = keystore.relativeTo(rootProject.projectDir).path.replace('\\', '/')
    doFirst {
        if (!keystore.exists()) {
            throw GradleException(
                "Missing $relativePath. Generate it with the keytool command in " +
                    "docs/development-setup.md (\"Shared Debug Keystore for Google Maps\")."
            )
        }
    }
}

// Wire to the debug signing-validation task so the friendly error fires right before AGP would
// otherwise throw a confusing keystore-not-found error — and only when actually producing a signed
// debug build (assemble/install/Run). Compile-only and unit-test tasks don't require the keystore.
tasks.matching { it.name == "validateSigningDebug" }.configureEach {
    dependsOn(verifyDebugKeystore)
}

// ── Developer API documentation (Dokka v2) ─────────────────────────────────────
// Generates HTML developer reference for the `main` source set only (tests/generated code are
// excluded by default). Output: app/build/dokka/html. Run with `:app:dokkaGenerate`.
dokka {
    moduleName.set("SurReal AR Developer API")
    dokkaSourceSets.configureEach {
        // Module/package overview shown on the docs landing page.
        includes.from("dokka/module.md")
        // "(source)" links on each declaration point back to GitHub main.
        sourceLink {
            localDirectory.set(file("src/main/java"))
            remoteUrl.set(URI("https://github.com/PennStateCS/GPS-App/tree/main/app/src/main/java"))
            remoteLineSuffix.set("#L")
        }
    }
    // Android exposes one source set per build variant, all pointing at src/main — which Dokka
    // rejects as duplicate source roots (Kotlin/dokka#3701). Document the debug variant only.
    dokkaSourceSets.matching { it.name == "release" }.configureEach {
        suppress.set(true)
    }
    // Footer on every generated page. Provided by the built-in HTML output (dokka-base) — no extra
    // plugin required.
    pluginsConfiguration.html {
        footerMessage.set("Generated from the main branch. Developer reference only.")
    }
}
