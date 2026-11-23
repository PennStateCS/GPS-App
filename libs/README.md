# geokit

The reusable, Android-free part of SurReal AR: Kotlin/JVM libraries for geodesy, NMEA/GNSS
processing and receiver plug-ins. The app (`app/`) builds on them, and nothing in `libs/` depends on
Android, Hilt, Room or the app.

Group `io.github.pennstatecs.geokit`. All five artifacts share one version, `GEOKIT_VERSION` in
`libs/gradle.properties` (currently `0.1.0-SNAPSHOT`). `libs/` is its own Gradle build (no Android
SDK needed); the app uses it through `includeBuild("libs")`. Publishing isn't set up yet.

| Artifact | Gradle project / directory | Package root | What it is |
|---|---|---|---|
| [`geokit-core`](geokit-core/README.md) | `:geokit-core`, `libs/geokit-core` | `io.github.pennstatecs.geokit` | Earth math: ellipsoids/ECEF, ITRF2014⇄NAD83(2011), TM/LCC/HOM/UTM/State Plane, GEOID18, the coordinate-conversion engine (`convert`: datum + epoch, geographic/UTM/State Plane, ellipsoidal/GEOID18/receiver-MSL heights, units), local ENU, WGS84 geodesics, the 68 % accuracy convention, heading, running statistics, stakeout guidance, lat/lon validation |
| [`geokit-gnss`](geokit-gnss/README.md) | `:geokit-gnss`, `libs/geokit-gnss` | `io.github.pennstatecs.geokit.gnss` | NMEA 0183 parsing and epoch fusion into fixes, satellite inventory, accuracy estimation (DOP × UERE by accuracy class), averaged capture, a TCP NMEA source with automatic reconnect, NMEA replay, antenna geometry, the `ReceiverDevice` contract, `GnssLog` |
| [`geokit-receiver-emlid`](geokit-receiver-emlid/README.md) | `:geokit-receiver-emlid`, `libs/geokit-receiver-emlid` | `io.github.pennstatecs.geokit.receiver.emlid` | Emlid Reach RS2+/RS4/RS4 Pro status (battery, device info, corrections) over HTTP and socket.io, as a `ReceiverDevice`; network scan (`ReachNetworkScan`); antenna phase-centre offsets per model; Emlid NMEA sentences EBP/ETC (`EmlidNmea.parsers`, `EmlidNmeaDiagnostics`) |
| [`geokit-ar`](geokit-ar/README.md) | `:geokit-ar`, `libs/geokit-ar` | `io.github.pennstatecs.geokit.ar` | AR positioning maths without ARCore or Filament: named frame conventions (anchor East-Up-South ⇄ ENU, glTF +Z → north), model bounds, placement and move types, the model transform (compose / inverse), touch-gesture maths, screen projection, AR-vs-GNSS offsets and expected placement error, floor selection |
| [`geokit-models`](geokit-models/README.md) | `:geokit-models`, `libs/geokit-models` | `io.github.pennstatecs.geokit.models` | Portable glTF/GLB processing: one GLB container reader, glTF facts (bounds, units, extensions, textures), resource-URI parsing, external sidecar listing/copying (`GltfSidecars`), embedded-georeference detection and ENU reprojection |

## How they stack

```
geokit-core  ←  geokit-gnss  ←  geokit-receiver-emlid  (← further geokit-receiver-<vendor> modules)
     ↑    ↖
geokit-ar   geokit-models
```

Every arrow is "depends on"; the graph has no cycles. `geokit-ar` and `geokit-models` depend only on
`geokit-core` and not on each other or on GNSS: model files and AR placement meet in the app.

- **Only coordinates and Earth math** (projections, datum shifts, geoid, distances): `geokit-core`.
- **Connecting to a receiver over TCP, parsing its NMEA into fixes with honest accuracy, replaying a
  log, or averaging a point**: `geokit-gnss`. It brings `geokit-core` with it.
- **Reading an Emlid Reach's battery, device and correction status, finding one on the network, or
  its antenna offset**: `geokit-receiver-emlid`. It brings `geokit-gnss` as an API dependency.
- **Placing and moving 3D models in an AR world frame** (transforms, gestures, projection, how far AR
  and GNSS disagree): `geokit-ar`. ARCore session, camera and rendering stay in the host app.
- **Reading, checking or georeferencing glTF/GLB files**: `geokit-models`.

What stays in SurReal AR on purpose: field-tuned decisions (AR anchor gates, AR quality levels and
their wording, altitude policy, visibility rules), persistence formats and rounding, settings,
logging and UI. The libraries return results; the app decides, logs and shows them.

Conventions everywhere: units in names (`latDeg`, `heightM`, `ageS`), `Double` for measurements,
`null` means unknown (never 0). The one exception is `geokit-ar`'s render-side types (matrices,
`Vec3`, placement), which are `Float` because ARCore and Filament consume `float[16]`; its geodetic and
accuracy functions are `Double`. Horizontal accuracy is a **68 % radius**, and vertical accuracy is
**1σ**. Use `Geodesic` for distance and bearing and `LocalEnu` for short-range local work; never a
haversine or a fixed metres-per-degree constant.

## Verified accuracy

`geokit-core` is checked against PROJ 9.8.1 / pyproj 3.8.0, NGS NCAT and the NGS geoid service. Some
results: State Plane < 1 µm, UTM < 1 mm, Helmert ~2 × 10⁻⁹ m, GEOID18 ≤ 0.46 mm on the grid. The
GEOID18 grid ships inside `geokit-core` (`Geoid18Grid.fromClasspath()`), so its tests always run and
fail rather than skip if it is missing. The full table, with the tests that check each row, is in
the [geokit-core README](geokit-core/README.md#verified-accuracy).

## Architecture rules

The module structure is frozen. [ARCHITECTURE.md](ARCHITECTURE.md) has what each module owns, the allowed
and forbidden dependencies, how the app maps its settings onto library types, where new work goes, the
tests that pin numerical behaviour, and the design note for future control-point calibration (not
implemented). New features go into the module that owns them; a sixth module (other than a new
`geokit-receiver-<vendor>`) or a changed dependency direction needs an explicit review.

## Adding a receiver

1. Create `libs/geokit-receiver-<vendor>`, set up like `geokit-receiver-emlid`: plain Kotlin JVM,
   `explicitApi()`, `api(project(":geokit-gnss"))`, package `io.github.pennstatecs.geokit.receiver.<vendor>`.
   Register it in `settings.gradle.kts` with its `projectDir`.
2. Implement `ReceiverDevice` for the status the receiver offers, and a JVM `ReceiverDiscovery` if it
   can be found on the network (Android-only parts such as mDNS stay in the app). List each model's
   antenna phase-centre offset (null if unknown). Log through `GnssLog`.
3. Write a `SentenceParser` for each proprietary NMEA sentence (use `NmeaFields` to read lat/lon and
   numbers like the standard parsers) and export them as a map, like `EmlidNmea.parsers`. The app passes
   them to `DefaultNmeaRegistry.create(..., extraParsers)` for live data and to `ReplayAdapter(...,
   extraParsers)` for replay, through the receiver's profile (`extraParsers`). `NmeaFuser` records every
   sentence it does not use for fixes in `extraSentenceStats` (count, latest sentence and sticky flags per
   tag; implement `FlaggedNmeaSentence` for flags); give the app a typed view over it, like
   `EmlidNmeaDiagnostics.from(stats)`. The NMEA connection itself needs no new code: geokit-gnss's
   `TcpNmeaSource` and `ReconnectingNmeaAdapter` handle any NMEA-over-TCP receiver.
4. In the app: add an `ExternalReceiverProfile` entry (stable `prefKey`, labels, port, vendor,
   `accuracyClass`, `phaseCenterOffsetM` from your module, `capabilities`) and map the vendor to your
   device in `di/ReceiverModule`. The UI follows the capabilities, so it needs no change.

See [geokit-receiver-emlid](geokit-receiver-emlid/README.md#adding-another-receiver) for more detail.

## Using geokit from another Gradle project

**Now**: include `libs/` as a composite build. `libs/` is its own Gradle build (`libs/settings.gradle.kts`,
Kotlin JVM plugin 2.4.21, no Android Gradle Plugin), so this needs neither the Android SDK nor the app.
This is exactly how the app itself uses geokit.

Check out the whole repository (a clone or a git submodule), not just `libs/`: the build reads the
repository's version catalog, `gradle/libs.versions.toml`.

```kotlin
// settings.gradle.kts of the other project (here the repository is a submodule in GPS-App/)
includeBuild("GPS-App/libs")

// build.gradle.kts: Gradle substitutes the included projects by group:name, so no version is needed
dependencies {
    implementation("io.github.pennstatecs.geokit:geokit-gnss")
    implementation("io.github.pennstatecs.geokit:geokit-receiver-emlid")
}
```

The artifacts are `geokit-core`, `geokit-gnss`, `geokit-receiver-emlid`, `geokit-ar` and
`geokit-models` (version `GEOKIT_VERSION` in `libs/gradle.properties`). Dependencies between them come
along transitively.

**Later**: publish to Maven Central or JitPack under the `io.github.pennstatecs` namespace. That isn't
set up yet.

## Build and test

The libraries build and test on their own, without Android (Windows commands; use `./gradlew` elsewhere):

```
cd libs
..\gradlew.bat test                      # all five libraries, plus the JVM smoke tests
..\gradlew.bat :geokit-core:test         # one library
```

or, from the repository root, `.\gradlew.bat -p libs test`. This uses the repository's Gradle wrapper and
never configures the app.

`geokit-jvm-smoke` (`libs/geokit-jvm-smoke`, not published) uses the libraries as a plain desktop JVM
project would: its test suites depend on one library each (`test` → `geokit-receiver-emlid`,
`modelsTest` → `geokit-models`) and nothing else, so they run with only the runtime dependencies those
libraries declare. They go through org.json code paths (GLB facts, detection and reprojection; a Reach
`/battery` JSON read from a local HTTP server) and fail with `NoClassDefFoundError` if a library stops
shipping `org.json:json` at runtime. Both libraries compile and test against android-json and ship
`org.json:json` 20240303 as `runtimeOnly`; the app excludes it and uses Android's own copy.

The root build includes `libs/` with `includeBuild("libs")`, so `.\gradlew.bat :app:assembleDebug`
builds the libraries it needs. From the root, library tasks are addressed through the included build
name `libs`:

```
.\gradlew.bat :libs:geokit-core:test :libs:geokit-gnss:test :libs:geokit-receiver-emlid:test :libs:geokit-ar:test :libs:geokit-models:test
```

The libraries' Kotlin version (2.4.21, in `libs/settings.gradle.kts`) must stay equal to the root
`kotlin-gradle-plugin` pin in `build.gradle.kts`.

## Licence

GPL-3.0, like the rest of the repository (see the repository `LICENSE`).
