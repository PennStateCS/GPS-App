# geokit

The reusable, Android-free part of SurReal AR: Kotlin/JVM libraries for geodesy, NMEA/GNSS
processing and receiver plug-ins. The app (`app/`) builds on them, and nothing in `libs/` depends on
Android, Hilt, Room or the app.

Group `io.github.pennstatecs.geokit`. All three artifacts share one version, `GEOKIT_VERSION` in
the root `gradle.properties` (currently `0.1.0-SNAPSHOT`). Publishing isn't set up yet.

| Artifact | Gradle project / directory | Package root | What it is |
|---|---|---|---|
| [`geokit-core`](geokit-core/README.md) | `:geokit-core`, `libs/geokit-core` | `io.github.pennstatecs.geokit` | Earth math: ellipsoids/ECEF, ITRF2014⇄NAD83(2011), TM/LCC/HOM/UTM/State Plane, GEOID18, local ENU, WGS84 geodesics, the 68 % accuracy convention, heading, running statistics |
| [`geokit-gnss`](geokit-gnss/README.md) | `:geokit-gnss`, `libs/geokit-gnss` | `io.github.pennstatecs.geokit.gnss` | NMEA 0183 parsing and epoch fusion into fixes, satellite inventory, accuracy estimation (DOP × UERE by accuracy class), averaged capture, the `ReceiverDevice` contract, `GnssLog` |
| [`geokit-receiver-emlid`](geokit-receiver-emlid/README.md) | `:geokit-receiver-emlid`, `libs/geokit-receiver-emlid` | `io.github.pennstatecs.geokit.receiver.emlid` | Emlid Reach RS2+/RS4/RS4 Pro status (battery, device info, corrections) over HTTP and socket.io, as a `ReceiverDevice` |

## How they stack

```
geokit-core  ←  geokit-gnss  ←  geokit-receiver-emlid  (← further geokit-receiver-<vendor> modules)
```

- **Only coordinates and Earth math** (projections, datum shifts, geoid, distances): `geokit-core`.
- **Parsing NMEA from a receiver, turning it into fixes with honest accuracy, or averaging a point**:
  `geokit-gnss`. It brings `geokit-core` with it.
- **Reading an Emlid Reach's battery, device and correction status**: `geokit-receiver-emlid`. It
  brings `geokit-gnss` as an API dependency.

Conventions everywhere: units in names (`latDeg`, `heightM`, `ageS`), `Double` for measurements,
`null` means unknown (never 0). Horizontal accuracy is a **68 % radius**, and vertical accuracy is
**1σ**. Use `Geodesic` for distance and bearing and `LocalEnu` for short-range local work; never a
haversine or a fixed metres-per-degree constant.

## Verified accuracy

`geokit-core` is checked against PROJ 9.8.1 / pyproj 3.8.0, NGS NCAT and the NGS geoid service. Some
results: State Plane < 1 µm, UTM < 1 mm, Helmert ~2 × 10⁻⁹ m, GEOID18 ≤ 0.46 mm on the grid. The
full table, with the tests that check each row, is in the
[geokit-core README](geokit-core/README.md#verified-accuracy).

## Adding a receiver

1. Create `libs/geokit-receiver-<vendor>`, set up like `geokit-receiver-emlid`: plain Kotlin JVM,
   `explicitApi()`, `api(project(":geokit-gnss"))`, package `io.github.pennstatecs.geokit.receiver.<vendor>`.
   Register it in `settings.gradle.kts` with its `projectDir`.
2. Implement `ReceiverDevice` (and `ReceiverDiscovery` if the receiver can be found on the network)
   for the status the receiver offers. Log through `GnssLog`.
3. Write a `SentenceParser` for each proprietary NMEA sentence, and return the parsers from the
   receiver's profile (`extraParsers`).
4. In the app: add an `ExternalReceiverProfile` entry (stable `prefKey`, labels, port, vendor,
   `accuracyClass`, `capabilities`) and map the vendor to your device in `di/ReceiverModule`. The UI
   follows the capabilities, so it needs no change.

See [geokit-receiver-emlid](geokit-receiver-emlid/README.md#adding-another-receiver) for more detail.

## Using geokit from another Gradle project

**Now**: use a composite build or a git submodule.

```kotlin
// settings.gradle.kts of the other project — composite build against a checkout of this repository
includeBuild("../GPS-App") {
    dependencySubstitution {
        substitute(module("io.github.pennstatecs.geokit:geokit-core")).using(project(":geokit-core"))
        substitute(module("io.github.pennstatecs.geokit:geokit-gnss")).using(project(":geokit-gnss"))
        substitute(module("io.github.pennstatecs.geokit:geokit-receiver-emlid")).using(project(":geokit-receiver-emlid"))
    }
}
// build.gradle.kts
dependencies { implementation("io.github.pennstatecs.geokit:geokit-gnss:0.1.0-SNAPSHOT") }
```

A composite build configures the whole repository, including the Android app, so it needs the
Android SDK. To avoid that, add the repository as a git submodule and include only the libraries:

```kotlin
include(":geokit-core", ":geokit-gnss")
project(":geokit-core").projectDir = file("GPS-App/libs/geokit-core")
project(":geokit-gnss").projectDir = file("GPS-App/libs/geokit-gnss")
```

(The modules use the version catalog entries `geographiclib`, `kotlinx-coroutines-core`, `junit`, …;
add the same entries to your catalog.)

**Later**: publish to Maven Central or JitPack under the `io.github.pennstatecs` namespace. That isn't
set up yet.

## Build and test

```
.\gradlew.bat :geokit-core:test :geokit-gnss:test :geokit-receiver-emlid:test
```

## Licence

GPL-3.0, like the rest of the repository (see the repository `LICENSE`).
