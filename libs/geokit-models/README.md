# geokit-models

`io.github.pennstatecs.geokit:geokit-models` · version `GEOKIT_VERSION` (0.1.0-SNAPSHOT) · package `io.github.pennstatecs.geokit.models` · Gradle project `:geokit-models` in `libs/geokit-models`. Part of the [geokit](../README.md) libraries.

glTF 2.0 / GLB processing for SurReal AR's model import, without rendering: the GLB container, facts
about a model file (bounds, triangles, textures, extensions, missing resources), the external files a
`.gltf` references, and models with WGS84 coordinates baked into their vertices (detect, reproject).
Plain Kotlin/JVM: no Android, Filament, Hilt or app types. Depends on [`:geokit-core`](../geokit-core/README.md)
(`LocalEnu`, `Ellipsoid`) only.

## Contents

| Package | Type | What it does |
|---|---|---|
| `.glb` | `GlbContainer` | The one GLB parser: header + chunks in memory (`parse`), rebuild with a new JSON chunk (`rewriteJson`), and a streaming header read (`readHead`) for callers that only need the first chunk. Constants for the magic, version and chunk types. |
| `.gltf` | `GltfFactsReader`, `GltfFacts`, `GltfBounds` | Facts from a `.glb`/`.gltf` file, reading only the JSON, chunk headers and image headers. Never throws: failures are `GltfFacts.parseError`. |
| `.gltf` | `GltfUris` | External resource URIs (`externalResourceUris`), decoding (`decode`, `relativePath`), safe resolution next to the model (`resolve` → `Resolution`), and the `isInside` folder check. |
| `.gltf` | `Base64Decoder` | Base64 for `data:` URIs with Android `Base64.DEFAULT` leniency. |
| `.georef` | `GlbGeoreferenceDetector` | Is this GLB's first POSITION accessor a plausible WGS84 location? Reads the header and JSON only. Returns `Result.NotGlb / Rejected / NotGeoreferenced / Found`, each with a reason. |
| `.georef` | `GlbGeoreferenceReprojector` | Rewrites such a GLB into local ENU metres about its origin. Bytes in, `Result.NotGlb / NotGeoreferenced / Rejected / Failed / Reprojected` out. |
| `.georef` | `GeoreferenceRules`, `GeoreferencedOrigin`, `GeoreferenceConfidence`, `PositionSpan` | The shared plausibility thresholds and result types. |

## API sketch

```kotlin
val facts = GltfFactsReader.read(file)                 // GltfFacts; check parseError first

when (val r = GlbGeoreferenceDetector.detect(file)) {
    is GlbGeoreferenceDetector.Result.Found -> r.origin   // latitudeDeg, longitudeDeg, heightM + confidence
    else -> Unit                                          // NotGlb / Rejected / NotGeoreferenced: see r.reason
}

if (GlbGeoreferenceReprojector.isCandidate(file.name, file.length())) {
    val r = GlbGeoreferenceReprojector.reproject(file.readBytes())   // the array is used as scratch
    if (r is GlbGeoreferenceReprojector.Result.Reprojected) file.writeBytes(r.bytes)
}

val uris = GltfUris.externalResourceUris(json).getOrElse { emptyList() }
when (val res = GltfUris.resolve(modelFolder, uri)) { is GltfUris.Resolution.Found -> res.file; else -> null }
```

The app keeps thin wrappers that add its logging and map to its own types:
`util/GlbGeoreferenceDetector` and `util/GlbGeoreferenceReprojector` (→ `EmbeddedModelLocation`),
`ui/models/GltfResources` (logs unparseable JSON), and `ui/models/readiness/GeokitGltfFacts`
(`GltfBounds` → the readiness `Bounds` of float `Vec3`).

## Conventions

- **Never logs, never throws** across the public API: results and reasons are returned, and the caller
  logs (the app's wrappers write the same lines as before the move). Diagnostics must never contain
  coordinates, so results carry them only in typed fields.
- **Georeference heuristic** (Metashape Y-up export): X = longitude, Y = height (m), Z = −latitude, taken
  from the centre of the first POSITION accessor's `min`/`max`. Plausible when lon ∈ ±180, lat ∈ ±90,
  height ∈ −1000..9000 m, not null island (|lat|, |lon| < 1e-6), and both spans ≤ 0.05°. HIGH confidence
  for spans of 1e-7..0.005° on both axes, otherwise MEDIUM. Method label `GLB_POSITION_WGS_LIKE`.
- **Reprojection output** is float32 local ENU metres about that origin (`LocalEnu`, WGS84 ellipsoid)
  in the renderer's model convention: **east → −X, up → +Y, north → +Z**. Accessor `min`/`max` are
  rewritten and `asset.extras.surrealarReprojection = "wgs84-enu-north+z-v2"` is added. The output bytes
  are pinned by `GlbGeoreferenceGoldenTest` (same hashes as the app's golden test recorded before the
  move); a change there changes how existing imports look, so treat it as a format change.
- **URIs**: `data:` is embedded; other URIs are percent-decoded with `+` kept literal; a scheme or an
  absolute path is not a file next to the model; resolution must land strictly inside the folder
  (prefix plus separator, so `models2/` is not inside `models/`).
- Units and names follow geokit (`latitudeDeg`, `heightM`), `Double` for measurements, `null` = unknown.

## org.json

The module compiles against Android's own `org.json` API through `com.vaadin.external.google:android-json`
(`compileOnly`, plus `testImplementation` for the JVM tests), the same arrangement as
`geokit-receiver-emlid`. In the APK the platform's `org.json` is used.

**Key order differs on the JVM.** android-json is an old snapshot of Android's implementation whose
`JSONObject` keeps keys in a `HashMap`; current Android uses a `LinkedHashMap` (insertion order). So the
reprojector's re-serialised JSON has the same content but a different key order in this module's JVM
tests than on a device. The exact output bytes are therefore pinned by the app's
`GlbGeoreferenceGoldenTest` (Robolectric = Android's org.json); this module's golden test checks a
key-order-independent digest (`GlbDigest`: every chunk header, every non-JSON byte, the JSON content and
its padded length) whose expected values the app test records from the byte-pinned outputs. Anything
here that compares serialised JSON text must allow for this.

Base64: the app's `minSdk` is 24 and `java.util.Base64` needs API 26, so main code uses `Base64Decoder`
(an app Robolectric test, `Base64CompatTest`, checks it against `android.util.Base64.DEFAULT`). Tests may
use `java.util.Base64`.

## Known limitations

Documented, not changed (changing them would change how existing or new imports behave):

- **Reprojector bounds checks** each vertex write against the end of the whole file, not the BIN chunk
  or the bufferView's `byteLength`. A malformed accessor can therefore rewrite bytes of a later chunk
  instead of being rejected.
- **Sparse accessors and node transforms are ignored** by the reprojector and the detector: only the
  dense POSITION data and the accessor `min`/`max` are used. A model whose georeference lives in a node
  translation/matrix is not detected; sparse POSITION substitutions are not reprojected.
- **Whole file in memory**: the reprojector reads the entire GLB onto the heap and builds a full copy for
  the output (plus a copy of each non-JSON chunk), so peak memory is about 2–3× the file size.
- **Size caps differ**: reprojection is skipped above 128 MB (`GlbGeoreferenceReprojector.MAX_FILE_BYTES`)
  while the app imports up to 150 MB (`ModelFileIo.MAX_IMPORT_BYTES`). A georeferenced GLB of 128–150 MB
  imports unreprojected (and renders as a far-away sliver).
- **Detector LOW confidence is unreachable**: spans ≥ 0.05° are rejected before confidence is assigned,
  so the detector returns only HIGH or MEDIUM (the reprojector has no LOW branch at all).
- **The detector does not check the reprojection marker**: it runs on stored, already-reprojected files,
  where the vertices are local metres centred near (0, 0, 0), so such files normally read as not
  georeferenced (span or null-island checks) rather than "reprojected, origin = …"; a model under about
  5 cm across whose centre is not within 1e-6 of zero could read as a bogus location near (0°, 0°).
  The origin is kept on the model row at import instead.
- The detector looks at the first primitive of the first mesh only; the reprojector takes its origin
  from the first POSITION accessor and rewrites every POSITION accessor (`FLOAT`/`VEC3`, embedded BIN).

## Tests

`.\gradlew.bat :geokit-models:test` (plain JUnit, no Robolectric). Fixture: `src/test/resources/models/surreal-test-cube-1m.glb`
(also in the app's test resources). Golden inputs are built in code by `GoldenGlbs`, kept byte-identical
with the app copy (`app/src/test/java/app/surrealar/util/GlbGeoreferenceGoldenTest.kt`); their SHA-256 is
asserted too.
