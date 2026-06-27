// Module: app
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)   // ✅ apply in this module
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
    // Developer API reference docs (Dokka Gradle Plugin v2). HTML only; not published.
    id("org.jetbrains.dokka") version "2.2.0"
}

kotlin { jvmToolchain(17) }

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
    namespace = "com.example.surveyingapp"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.surveyingapp"
        minSdk = 24
        targetSdk = 35
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
    kotlinOptions { jvmTarget = "17" }

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
            assets.srcDir("$projectDir/schemas")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation("com.google.android.gms:play-services-maps:18.2.0")
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)

    // 3D rendering (Filament)
    implementation("com.google.android.filament:filament-android:1.68.3")
    implementation("com.google.android.filament:filament-utils-android:1.68.3")

    // Room (KSP)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.play.services.location)
    implementation(libs.osmdroid)
    implementation(libs.arcore)
    implementation(libs.androidx.swiperefreshlayout)

    // Hilt (KSP)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // (Optional for tests using Hilt)
    // kspAndroidTest(libs.hilt.compiler)
    // kspTest(libs.hilt.compiler)

    // Coroutines / DataStore / misc
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jmdns:jmdns:3.5.8")

    // Socket.IO client for Reach RS2+ live data (navigation/stream_status broadcasts)
    // RS2+ firmware runs socket.io v2 (EIO=3); client 2.x uses EIO=4 and is incompatible.
    implementation("io.socket:socket.io-client:1.0.1") {
        exclude(group = "org.json", module = "json")
    }

    // Desugaring
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.2")

    // --- Testing ---
    testImplementation(libs.junit)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("org.robolectric:robolectric:4.12.1")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("androidx.test.ext:junit:1.1.5")
    testImplementation("org.mockito:mockito-core:5.2.0")
    debugImplementation("androidx.fragment:fragment-testing:1.6.1")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    // Room migration testing (MigrationTestHelper)
    androidTestImplementation("androidx.room:room-testing:2.7.2")

    // Optional guard for compileSdk 35
    constraints {
        implementation("androidx.core:core:1.15.0") { because("compileSdk 35") }
        implementation("androidx.core:core-ktx:1.15.0") { because("compileSdk 35") }
    }
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
    moduleName.set("Surveying App Developer API")
    dokkaSourceSets.configureEach {
        // Module/package overview shown on the docs landing page.
        includes.from("dokka/module.md")
        // "(source)" links on each declaration point back to GitHub main.
        sourceLink {
            localDirectory.set(file("src/main/java"))
            remoteUrl.set(URI("https://github.com/PennStateWilkes-Barre/GPS-App/tree/main/app/src/main/java"))
            remoteLineSuffix.set("#L")
        }
    }
    // Android exposes one source set per build variant, all pointing at src/main — which Dokka
    // rejects as duplicate source roots (Kotlin/dokka#3701). Document the debug variant only.
    dokkaSourceSets.matching { it.name == "release" }.configureEach {
        suppress.set(true)
    }
}

