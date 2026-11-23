# GeoKit architecture

This file fixes the module structure of the GeoKit libraries and the boundary with the SurReal AR
app. The structure is **frozen**: new features go into the module that owns the responsibility.
Adding a sixth module, changing a dependency direction, or changing a public API in an incompatible
way needs an explicit architectural review (a short written justification in the pull request).

The libraries stay at `0.1.0-SNAPSHOT`: the boundaries are settled, but the APIs are not promised to
be stable (1.0) while the app and its field workflows mature.

## Dependency graph

```
                 geokit-core
               ↗     ↑      ↖
     geokit-ar   geokit-gnss   geokit-models
                     ↑
           geokit-receiver-emlid   (← future geokit-receiver-<vendor>)

   app (SurReal AR)  →  all five          geokit-jvm-smoke (tests only) → emlid, models
```

Arrows point at what a module depends on. Rules:

- The graph is acyclic. `geokit-core` depends on nothing in GeoKit.
- `geokit-gnss`, `geokit-ar` and `geokit-models` depend only on `geokit-core`, never on each other.
- A receiver module depends on `geokit-gnss` (as `api`). Nothing in GeoKit depends on a receiver module.
- No library depends on Android, AndroidX, ARCore, Filament, Hilt/javax.inject, Room, DataStore or any
  `app.surrealar.*` type. All libraries are plain Kotlin/JVM with `explicitApi()`.
- Libraries never log through Android. `geokit-gnss` (and modules built on it) log through
  `GnssLog`/`LinkLog`, installed by the host; `geokit-core`, `geokit-ar` and `geokit-models` don't log
  at all — they return results and the app logs.

## What each module owns

| Module | Owns | Does not own |
|---|---|---|
| `geokit-core` | Ellipsoids/ECEF, datum transformations (ITRF2014 ⇄ NAD83(2011)), projections (TM/LCC/HOM, UTM, State Plane), GEOID18, the coordinate-conversion engine (`convert`), local ENU, geodesics, accuracy conventions, statistics, heading, stakeout guidance, lat/lon validation | Settings, display formatting, persistence |
| `geokit-gnss` | NMEA parsing and its extension point for vendor sentences, epoch fusion, fix and satellite models, accuracy estimation, averaged capture, the TCP source and reconnect loop, replay, receiver-neutral contracts (`ReceiverDevice`, profiles' accuracy classes), antenna geometry, fix datum normalisation | Anything vendor-specific |
| `geokit-receiver-emlid` | Emlid Reach: HTTP/socket.io status, discovery, device models and antenna offsets, Emlid NMEA sentences (EBP/ETC) and their diagnostics view | Generic NMEA, UI |
| `geokit-ar` | AR ⇄ geodetic frame conventions, rigid transforms and model placement matrices, AR movement maths, screen projection, AR-vs-GNSS offsets and expected error, floor selection; future calibration maths | ARCore sessions/anchors, camera, Filament, lifecycle, quality *policies* |
| `geokit-models` | GLB/glTF container parsing and validation, resource URIs and sidecar files, model facts and bounds, georeference detection and reprojection | Filament, import UI, readiness *policy* |
| app | Android lifecycle and permissions, UI/navigation, ARCore sessions and anchors, Filament rendering and native resource ownership, Room and migrations, DataStore settings, app policies (AR gates, quality levels, altitude policy, visibility, save rules), model-readiness decisions tied to Filament, diagnostics/logging, persistence of points and model adjustments | Reusable maths or protocols |

Pure Kotlin code may stay in the app when it is app policy (thresholds tuned in the field, wording,
storage rounding). "It has no Android imports" is not a reason to move code into a library.

## How the app plugs into the libraries

- **Settings → neutral types.** App settings enums are mapped to library types in thin adapters, e.g.
  `app/.../coords/CoordinateSettingsMapping.kt` (`LinearUnit → LengthUnit`, `OutputDatum →
  GeodeticDatum`, `VerticalReference → convert.VerticalReference`, settings → `OutputSystem`) and
  `coords/FeedDatumMapping.kt` (`ReceiverDatum → FixDatum`). The libraries never see a settings class.
- **App types stay in the app.** `ConvertedPosition`, `Coordinate`, Room entities and `ModelPlacement.resolve`
  (an extension over entities) are app-side; adapters translate at the edge.
- **Receiver-specific extensions.** `geokit-gnss` parses standard NMEA (`DefaultNmeaRegistry.standardParsers()`)
  and accepts extra sentence parsers (a map of sentence tag → `SentenceParser`) in
  `DefaultNmeaRegistry.create(..., extraParsers)` for live data and `ReplayAdapter(..., extraParsers)` for
  replay. `NmeaFuser.extraSentenceStats` records every parsed sentence that does not feed a fix: count,
  latest sentence and sticky flags (`FlaggedNmeaSentence`) per tag. It never changes fixes. A receiver
  module ships its parsers and a typed view of those stats (Emlid: `EmlidNmea.parsers`,
  `EmlidNmeaDiagnostics.from`); the app registers them through the receiver profile
  (`ExternalReceiverProfile.extraParsers`) and its diagnostics read the vendor view. `geokit-gnss` never
  names a vendor sentence.
- **Logging.** The app installs `AndroidGnssLogger` and passes `DiagnosticsLinkLog`; libraries that don't
  log return results (`Result`/sealed types with reasons) and the app writes the log lines.

## Where new work goes

| New work | Module |
|---|---|
| Earth maths, a new projection or datum, coordinate conversion features | `geokit-core` |
| A new standard NMEA sentence, fusion or accuracy logic, a new transport | `geokit-gnss` |
| A new receiver brand | new `geokit-receiver-<vendor>` (the one sanctioned way to add a module; see libs/README.md) |
| AR placement or alignment maths | `geokit-ar` |
| Model file parsing/validation/georeferencing | `geokit-models` |
| UI, settings, persistence, ARCore/Filament, field-tuned policy | app |

## Numerical compatibility

Behaviour is pinned by golden and reference tests; a change that alters them needs a stated reason.

- Coordinates: `app CoordinateConverterGoldenTest` (bit-exact output over datums × systems × units ×
  heights), geokit-core `CoordinateConverterTest` / `ReferencePointsTest` (PROJ, NCAT, NGS GEOID18),
  `SpcsZonesTest`, `UtmProjTest`, `Geoid18GridTest` (fails, never skips, if the grid is missing).
- GNSS: NMEA fixture replays and fuser tests in geokit-gnss (and the Emlid capture tests in
  geokit-receiver-emlid).
- AR: `app ModelTransformGoldenTest` (library transform = old `android.opengl.Matrix` sequence, bit-exact).
- Models: `app GlbGeoreferenceGoldenTest` (exact reprojected bytes on Android's org.json) and the
  key-order-independent digest test in geokit-models.
- Conventions that never change silently: horizontal accuracy is a 68 % radius, vertical 1σ; `Double`
  for geodetic values (AR render types are `Float`); units in names; `null` = unknown, never 0 (0.0 is a
  real height); the GEOID18 lookup is at the NAD83(2011) position; stored enum tokens
  (`ModelPlacementOrigin`) and the reprojection marker `wgs84-enu-north+z-v2` keep their spelling.

## Testing

- Libraries alone (no Android SDK): `./gradlew -p libs test` — includes `geokit-jvm-smoke`, which runs on
  the libraries' runtime classpath only (catches a missing `runtimeOnly` such as org.json).
- From the root: `./gradlew :libs:geokit-core:test` etc.; app: `./gradlew :app:testDebugUnitTest
  :app:assembleDebug :app:compileDebugAndroidTestKotlin`.
- CI (`.github/workflows/ci.yml`) runs the library job without an Android SDK and the app job separately.

## Future: control-point calibration (design note, not implemented)

Goal: align ARCore's world frame to a surveyed local ENU frame using three or more surveyed control
points observed in AR, with residuals, independent checkpoints, outlier handling and uncertainty, and the
option to recalibrate during a session.

- **geokit-core** converts the surveyed points to a local ENU frame around a site origin (`LocalEnu`,
  using the `convert` engine for datum/epoch handling) and supplies `RunningStats` for residual statistics.
- **geokit-ar** will own the solver: given pairs (AR anchor-frame point, surveyed ENU point) it estimates a
  rigid transform — yaw + 2D translation with a separate vertical offset (the robust default for ARCore,
  whose gravity axis is reliable), optionally full 3D rotation and scale — by least squares, then reports
  per-point residuals, RMS, checkpoint residuals for points not used in the fit, outlier flags (e.g.
  residual > k·RMS or leave-one-out), and parameter uncertainty. It builds on the existing single
  East-Up-South ⇄ ENU conversion in `ArFrames`, so no other seam is needed now.
- **The app** owns choosing control points and checkpoints, capturing AR observations from ARCore,
  session state, UI, persistence of calibrations, and applying the transform to anchors/rendering.

No calibration API exists yet on purpose; adding the solver later is a new file in `geokit-ar` plus app
work, not a restructuring.
