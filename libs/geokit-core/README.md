# geokit-core

`io.github.pennstatecs.geokit:geokit-core` · version `GEOKIT_VERSION` (0.1.0-SNAPSHOT) · package `io.github.pennstatecs.geokit` · Gradle project `:geokit-core` in `libs/geokit-core`. Part of the [geokit](../README.md) libraries.

Android-free geodesy for SurReal AR: ellipsoids and geocentric conversions, the ITRF2014 ⇄ NAD83(2011)
Helmert transform, State Plane / UTM / transverse Mercator projections, the GEOID18 grid, local ENU
frames, WGS84 geodesics (distance and bearing), the 68 % horizontal-accuracy convention, compass
heading math, running statistics, stakeout guidance and a lat/lon plausibility check. Plain Kotlin/JVM (no Android, ARCore, Room or Hilt), so it can
be reused by other JVM projects and tested on a desktop.

Package root `io.github.pennstatecs.geokit`:

| Package | Contents |
|---|---|
| `.ellipsoid` | `Ellipsoid` (`WGS84`, `GRS80`: `toEcef`, `fromEcef`, meridian / prime-vertical radii), `GeodeticPosition`, `EcefPosition`, `Geodesic` (`distanceM`, `initialBearingDeg`, `northEastDeltaM` on WGS84, via GeographicLib) |
| `.datum` | `Itrf2014ToNad83` (14-parameter time-dependent Helmert, `forward` / `inverse` at a decimal-year epoch, `decimalYear(epochMillis)`) |
| `.projection` | `Tm` (Krüger series, forward + inverse), `Lcc2`, `HomA`, `Utm`, `SpcsZone` / `SpcsZones` (all 118 SPCS83 zones on NAD83(2011)) |
| `.geoid` | `Geoid18Grid` (`fromClasspath()` loads the GEOID18 grid bundled in this jar; or from a `ByteArray` / `InputStream` of a packed SRGT grid) |
| `.local` | `LocalEnu` (east/north/up about an origin, for short-range local work: `toEnu`, and its exact inverse `toGeodetic`) |
| `.accuracy` | `HorizontalAccuracy` (horizontal accuracy is a **68 % radius**; vertical is **1σ**) |
| `.heading` | `HeadingMath` (true heading, display-rotation remap, upright detection), `Compass.point8` |
| `.stats` | `RunningStats` (Welford mean / variance) |
| `.stakeout` | `StakeoutGuidance` (status within tolerance, relative arrow angle, heading source, direction word; takes distance and bearing from `Geodesic`), `StakeoutGuidanceState`, `StakeoutFeedbackGate` (arrival fires once, navigation pulses throttled; clock injected) |
| `.validation` | `LatLonValidation.isValidLatLon` (finite, in range, not the 0,0 "null island" placeholder) |

Conventions: angles in degrees, lengths in metres, `Double` everywhere, units in parameter names
(`latDeg`, `heightM`, `epochYear`), `null` means unknown. No `java.time` types in the public API
(pass epoch milliseconds or decimal years).

## Verified accuracy

Reference: PROJ 9.8.1 (pyproj 3.8.0), NGS NCAT and the NGS geoid service. The tests that check each
row live in `libs/geokit-core/src/test` unless noted.

| What | Result | How it is checked |
|---|---|---|
| SPCS83, all 118 zones (LCC 2SP, TM, HOM A) | worst 1.5 × 10⁻⁷ m (< 1 µm) | `SpcsZonesTest`: 9 points per zone vs PROJ (`spcs83_vectors.csv`) |
| UTM forward / inverse / round trip | 1.8 × 10⁻⁷ / 5.0 × 10⁻⁸ / 1.6 × 10⁻⁷ m (< 1 mm required) | `UtmProjTest`: 1 134 PROJ vectors, 9 zones, both hemispheres, zone edges (`tools/utm/gen_utm_vectors.py`) |
| ITRF2014 → NAD83(2011) Helmert | worst 1.9 × 10⁻⁹ m vs PROJ at 5 US sites (PA, CA, WA, FL, AK), epochs 2020–2030 | app `CoordinateSystemReferenceTest` (PROJ point + round trip) |
| GEOID18 | worst 0.46 mm on the 600 grid vectors (`geoid18_vectors.csv`, 1 mm tolerance); within 1.3 mm of the 4 NGS geoid-service points; 11 NGS points across CONUS, PR and VI within 5 mm | `Geoid18GridTest` |
| ECEF (GRS80 / WGS84) | NCAT XYZ to 1 mm (NCAT's rounding); merged code within 7.2 × 10⁻⁷ m of the previous implementations | app `CoordinateSystemReferenceTest`, `LegacyEllipsoidComparisonTest` |
| Geodesic distance / bearing | GeographicLib (Karney): nanometre-level; stakeout ENU agreement 0.5 mm at 5 km | `GeodesicTest` |

## Using it from another Gradle project

Now: include the geokit build (`libs/` of a checkout or submodule of this repository) as a composite
build. It needs no Android SDK. See [Using geokit from another Gradle project](../README.md#using-geokit-from-another-gradle-project).

```kotlin
// settings.gradle.kts of the other project
includeBuild("GPS-App/libs")
// build.gradle.kts
dependencies { implementation("io.github.pennstatecs.geokit:geokit-core") }
```

Later: publish through JitPack or GitHub Packages (not set up yet).

## Build and test

```
cd libs
..\gradlew.bat :geokit-core:build     # compiles and runs the module's tests, without Android
```

From the repository root: `.\gradlew.bat :libs:geokit-core:test`.

## GEOID18 grid

The packed GEOID18 grid (CONUS + Puerto Rico / Virgin Islands, about 5.2 MB) ships inside this
library as the classpath resource `geoid/geoid18.srgt` (`src/main/resources/geoid/geoid18.srgt`,
`Geoid18Grid.RESOURCE_PATH`). Load it once, off the main thread:

```kotlin
val geoid = Geoid18Grid.fromClasspath()          // throws IllegalStateException if the resource is missing
val n = geoid.undulation(41.3477, -76.0226)      // metres, null outside GEOID18 coverage
```

On Android the resource is packaged into the APK from the library jar (SurReal AR loads it this way
in `CoordinateSystemService`); don't exclude `geoid/**` in packaging options. `Geoid18GridTest`
loads it from the classpath and fails, rather than skipping, if it is missing.

Data generators (SPCS table, GEOID18 packing, test vectors) are in `tools/` with their own
README; they need Python 3 with pyproj.

## Dependencies and licence

- GeographicLib-Java 2.0 (MIT), for geodesics.
- GPL-3.0, like the rest of the repository (see the repository `LICENSE`).
