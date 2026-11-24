# geokit-core

`io.github.pennstatecs.geokit:geokit-core` · version `GEOKIT_VERSION` (0.1.0-SNAPSHOT) · package `io.github.pennstatecs.geokit` · Gradle project `:geokit-core` in `libs/geokit-core`. Part of the [geokit](../README.md) libraries.

Android-free geodesy for SurReal AR: ellipsoids and geocentric conversions, the ITRF2014 ⇄ NAD83(2011)
Helmert transform, State Plane / UTM / transverse Mercator projections, the GEOID18 grid, local ENU
frames, WGS84 geodesics (distance and bearing), the 68 % horizontal-accuracy convention, compass
heading math, running statistics, stakeout guidance, a lat/lon plausibility check and a coordinate
conversion engine that combines them (datum, grid system, unit and height reference in one call). Plain Kotlin/JVM (no Android, ARCore, Room or Hilt), so it can
be reused by other JVM projects and tested on a desktop.

Package root `io.github.pennstatecs.geokit`:

| Package | Contents |
|---|---|
| `.ellipsoid` | `Ellipsoid` (`WGS84`, `GRS80`: `toEcef`, `fromEcef`, meridian / prime-vertical radii), `GeodeticPosition`, `EcefPosition`, `Geodesic` (`distanceM`, `initialBearingDeg`, `northEastDeltaM` on WGS84, via GeographicLib) |
| `.datum` | `Itrf2014ToNad83` (14-parameter time-dependent Helmert, `forward` / `inverse` at a decimal-year epoch, `decimalYear(epochMillis)`) |
| `.projection` | `Tm` (Krüger series, forward + inverse), `Lcc2`, `HomA`, `Utm`, `SpcsZone` / `SpcsZones` (all 118 SPCS83 zones on NAD83(2011)), `SpcsZoneLocator` / `SpcsZoneSet` / `SpcsZoneSuggestion` (zone suggestion from county-defined zone extents) |
| `.geoid` | `Geoid18Grid` (`fromClasspath()` loads the GEOID18 grid bundled in this jar; or from a `ByteArray` / `InputStream` of a packed SRGT grid); the geoid-model layer `GeoidModel` / `GeoidResult` / `GeoidModelInfo` / `GeoidModelId` with `Geoid18Model`, `GlobalGeoidModels` (`egm2008()`, `egm96()`, `geoid18()`), `TiledGeoidModel`, `GeoidTileSource` / `GeoidTilePack` / `CompositeGeoidTileSource`, `GeoidTileCacheLimits` (see [Geoid models](#geoid-models)) |
| `.local` | `LocalEnu` (east/north/up about an origin, for short-range local work: `toEnu`, and its exact inverse `toGeodetic`) |
| `.accuracy` | `HorizontalAccuracy` (horizontal accuracy is a **68 % radius**; vertical is **1σ**) |
| `.heading` | `HeadingMath` (true heading, display-rotation remap, upright detection), `Compass.point8` |
| `.stats` | `RunningStats` (Welford mean / variance) |
| `.stakeout` | `StakeoutGuidance` (status within tolerance, relative arrow angle, heading source, direction word; takes distance and bearing from `Geodesic`), `StakeoutGuidanceState`, `StakeoutFeedbackGate` (arrival fires once, navigation pulses throttled; clock injected) |
| `.validation` | `LatLonValidation.isValidLatLon` (finite, in range, not the 0,0 "null island" placeholder) |
| `.convert` | `CoordinateConverter` (source position → output system), `SourcePosition`, `OutputSystem`, `TargetSystem` (`Geographic`, `UtmAuto`, `StatePlane(zoneEpsg)`), `GeodeticDatum`, `VerticalReference`, `LengthUnit`, `ConvertedCoordinate`, `HeightUnavailableReason`, `GeoidLookup`, `Epsg` |

Conventions: angles in degrees, lengths in metres, `Double` everywhere, units in parameter names
(`latDeg`, `heightM`, `epochYear`), `null` means unknown. No `java.time` types in the public API
(pass epoch milliseconds or decimal years).

## Coordinate conversion (`.convert`)

`CoordinateConverter` expresses a measured position in a chosen coordinate system. It only wires the
pieces above together (`Itrf2014ToNad83`, `Utm`, `SpcsZones`, `Geoid18Grid`); there is no second
copy of any algorithm.

```kotlin
val converter = CoordinateConverter(Geoid18Grid.fromClasspath())   // or CoordinateConverter(GeoidLookup { lat, lon -> … })
val out = converter.convert(
    SourcePosition(latDeg = 41.3477, lonDeg = -76.0226, ellipsoidalHeightM = 390.0, receiverMslHeightM = null,
        datum = GeodeticDatum.WGS84_ITRF2014, epochYear = Itrf2014ToNad83.decimalYear(epochMillis)),
    OutputSystem(TargetSystem.StatePlane(6562), unit = LengthUnit.US_SURVEY_FOOT,
        vertical = VerticalReference.GEOID18_ORTHOMETRIC),
)
out.easting; out.northing; out.height          // in US survey feet (eastingM / northingM / heightM in metres)
out.zoneLabel                                  // "SPCS 3701"
out.verticalDatumName                          // "NAVD88"
```

| Input | Options |
|---|---|
| Source datum (`GeodeticDatum`) | `WGS84_ITRF2014` (EPSG 4979/4326) or `NAD83_2011` (6319/6318), plus the observation epoch as a decimal year |
| Horizontal (`TargetSystem`) | `Geographic`, `UtmAuto` (zone from longitude), `StatePlane(zoneEpsg)` (any of the 118 SPCS83 zones; unknown EPSG throws) |
| Output datum | `WGS84_ITRF2014` or `NAD83_2011` for geographic and UTM; State Plane is always NAD83(2011) (`OutputSystem.effectiveDatum`) |
| Unit (`LengthUnit`) | `METRE`, `US_SURVEY_FOOT` (1200/3937 m), `INTERNATIONAL_FOOT` (0.3048 m) |
| Height (`VerticalReference`) | `ELLIPSOIDAL` (output datum), `GEOID18_ORTHOMETRIC` (NAD83(2011) h − N; NAVD88 / PRVD02 / VIVD09), `RECEIVER_MSL` (passed through) |

Rules:

- The GEOID18 undulation is always looked up at the **NAD83(2011)** position, and the orthometric
  height is NAD83(2011) h − N, whatever the output datum.
- A source already in the needed datum is never shifted again.
- `null` heights stay `null`: an unknown ellipsoidal height gives no ellipsoidal or GEOID18 height
  (`HeightUnavailableReason.NO_ELLIPSOIDAL_HEIGHT`), never 0; lat/lon are then shifted at h = 0 m
  (sub-millimetre effect). `0.0` is a real height. A missing geoid value (outside coverage, or the grid
  not loaded yet) gives `OUTSIDE_GEOID18_COVERAGE`; a missing receiver MSL gives `NO_RECEIVER_MSL`.
- `outsideZone` is true when a State Plane position lies outside the zone's EPSG area of use.
- `verticalDatumName` names the GEOID18 datum even when the height itself is unavailable; it is
  `null` for the other references (the caller chooses its own labels).

SurReal AR keeps its persisted settings enums and display formatting, and maps them onto these types
(`app/.../coords/CoordinateSettingsMapping.kt`, `CoordinateConverter` there is a thin adapter). Its
golden test (`CoordinateConverterGoldenTest`) pins the engine's output bit for bit across CONUS, AK,
PR, VI, HI, zone edges, both hemispheres, every system, datum, unit and height reference.

## State Plane zone suggestion (`SpcsZoneLocator`)

SPCS83 zones are defined in state law mostly by county lines. `SpcsZoneLocator.SPCS83.locate(lat, lon)`
returns a `SpcsZoneSuggestion` (`suggested`, `candidates`, `basis`):

- `COUNTY_BOUNDARY`: the point lies in the zone's extent, built from the counties EPSG lists for the zone
  (Census 1:500,000 cartographic county lines, dissolved per zone and simplified to ~50 m; resource
  `spcs/spcs83_zones.bin`, 356 kB, generated by `tools/coords/gen_spcs_boundaries.py`). 108 of the 118
  zones; the District of Columbia is included in Maryland (NGS convention).
- `AREA_OF_USE_BOX`: no extent contains the point (Alaska zones 1–10, which are defined by meridians and
  parallels; offshore; just outside the modelled coastline; the US Virgin Islands, which are not in the
  Census county file), so the EPSG area-of-use boxes decide — they overlap, so this is a guess.
- `NONE`: no zone covers the point.

Several matches are all returned (`candidates`, smallest box first), e.g. Kentucky North/South and Kentucky
Single Zone, which are both legal. Within `BOUNDARY_MARGIN_M` (250 m) of a zone line the neighbouring
zone is added to `candidates` and `nearBoundary` is set. It is always a suggestion; the user picks the zone.
WGS84 input is fine (the datum difference is far below the line accuracy). `SpcsZone.suggestFor`
returns `locate(...).suggested`.

`SpcsZoneLocator.check(zone, lat, lon)` answers "is this position in the chosen zone?" with `INSIDE`,
`OUTSIDE` (outside the zone's area-of-use box, or clearly inside another zone's extent) or `UNCERTAIN`
(near a zone line, offshore, or Alaska where only the box is known — never a claim). The converter's
`outsideZone` flag and the Settings warnings use it, so they agree with the suggestion. A missing or
unreadable resource falls back to boxes; nothing throws.

Details of the county mapping (independent cities, Broomfield CO, Washington's Grant County split, spelling
aliases) are in the generator's docstring; its county → zone table is `tools/coords/spcs83_county_zones.csv`.
SPCS2022 (new, layered zones) is meant to plug in as a second `SpcsZoneSet` with its own zone table and
boundary resource in the same format; see the `SpcsZoneSet` KDoc.

## Verified accuracy

Reference: PROJ 9.8.1 (pyproj 3.8.0), NGS NCAT and the NGS geoid service. The tests that check each
row live in `libs/geokit-core/src/test` unless noted.

| What | Result | How it is checked |
|---|---|---|
| SPCS83, all 118 zones (LCC 2SP, TM, HOM A) | worst 1.5 × 10⁻⁷ m (< 1 µm) | `SpcsZonesTest`: 9 points per zone vs PROJ (`spcs83_vectors.csv`) |
| UTM forward / inverse / round trip | 1.8 × 10⁻⁷ / 5.0 × 10⁻⁸ / 1.6 × 10⁻⁷ m (< 1 mm required) | `UtmProjTest`: 1 134 PROJ vectors, 9 zones, both hemispheres, zone edges (`tools/utm/gen_utm_vectors.py`) |
| ITRF2014 → NAD83(2011) Helmert | worst 1.9 × 10⁻⁹ m vs PROJ at 5 US sites (PA, CA, WA, FL, AK), epochs 2020–2030 | `convert/ReferencePointsTest` (PROJ point + round trip) |
| GEOID18 | worst 0.46 mm on the 600 grid vectors (`geoid18_vectors.csv`, 1 mm tolerance); within 1.3 mm of the 4 NGS geoid-service points; 11 NGS points across CONUS, PR and VI within 5 mm | `Geoid18GridTest` |
| EGM2008 2.5′ / EGM96 15′ | worst 0.50 mm (EGM2008, 267 points with a tile) and 0.49 mm (EGM96, 309 points) vs PROJ `vgridshift` on the PROJ-data grids — the 0.5 mm integer-mm storage rounding; 1 mm tolerance. Points include tile corners/edges/nodes, the antimeridian, ±90°, AK, Aleutians, HI, PR/VI, Guam | `geoid/GlobalGeoidModelTest` (`global_geoid_vectors.csv`, `tools/geoid/geoid_reference_vectors.py`) |
| ECEF (GRS80 / WGS84) | NCAT XYZ to 1 mm (NCAT's rounding); merged code within 7.2 × 10⁻⁷ m of the previous implementations | `convert/ReferencePointsTest`, `LegacyEllipsoidComparisonTest` |
| Conversion engine | SPCS (all PROJ vectors) and UTM vectors to 1 µm through the engine; PROJ datum shift to 2 × 10⁻⁹ °; NCAT PA North / UTM 18N to 1–2 mm; NGS GEOID18 heights to 1 cm | `convert/CoordinateConverterTest` |
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

## Geoid models

`GeoidModel` is the common interface: `undulation(lat, lon)` returns `GeoidResult.Value` (N in metres, the
model that produced it, the vertical datum name) or `GeoidResult.Unavailable` with a reason
(`INVALID_POSITION`, `OUTSIDE_COVERAGE`, `TILE_NOT_AVAILABLE`, `LOAD_FAILED`). H = h − N. A model never
substitutes another one: falling back from EGM2008 to EGM96 is the caller's explicit choice, and the height is
then labelled with the model that produced it.

| Model | `GeoidModelId.token` / label | Horizontal frame | Vertical datum | Data |
|---|---|---|---|---|
| `Geoid18Model` (`GlobalGeoidModels.geoid18()`) | `GEOID18` | NAD83(2011) | NAVD88 / PRVD02 / VIVD09 (by location) | the existing `geoid18.srgt`; numbers identical to `Geoid18Grid` |
| `GlobalGeoidModels.egm2008()` | `EGM2008` | WGS84 | EGM2008 (EPSG:3855) | 2.5′, 1° tiles; bundled US packs (CONUS, Alaska incl. Aleutians across 180°, Hawaii main islands, PR/USVI, Guam/CNMI), ≈ 1.7 MB; other tiles `TILE_NOT_AVAILABLE` unless supplied via `extraSources` |
| `GlobalGeoidModels.egm96()` | `EGM96` (`isFallback = true`) | WGS84 | EGM96 (EPSG:5773) | 15′, whole globe in 5° tiles, ≈ 1.7 MB |

```kotlin
val egm2008 = GlobalGeoidModels.egm2008()                  // one per process; first lookup loads (off the UI thread)
when (val r = egm2008.undulation(40.79, -77.86)) {
    is GeoidResult.Value -> r.orthometricHeightM(h)          // label "H (${egm2008.info.label})"
    is GeoidResult.Unavailable -> r.reason                    // e.g. TILE_NOT_AVAILABLE
}
```

Tiles are decoded lazily into a bounded, thread-safe LRU (`GeoidTileCacheLimits`, default 128 tiles / 1 MiB).
Installed tiles: write SRGP packs (same generator) to app storage with a `sha256sum`-style manifest and pass
`GeoidTilePack.fromManifest(dir, manifest)` as `extraSources`; packs are SHA-256 verified before use and tile
data is validated as untrusted. No network code lives here. Bundled resources are pinned by SHA-256
(`GlobalGeoidModels.EGM2008_BUNDLED_PACKS`, `EGM96_SHA256`). Sources, format and generators:
`tools/geoid/README.md`.

## Dependencies and licence

- GeographicLib-Java 2.0 (MIT), for geodesics.
- GPL-3.0, like the rest of the repository (see the repository `LICENSE`).
