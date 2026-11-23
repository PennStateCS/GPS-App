# geokit-ar

`io.github.pennstatecs.geokit:geokit-ar` · version `GEOKIT_VERSION` (0.1.0-SNAPSHOT) · package `io.github.pennstatecs.geokit.ar` · Gradle project `:geokit-ar` in `libs/geokit-ar`. Part of the [geokit](../README.md) libraries. Depends on [`:geokit-core`](../geokit-core/README.md).

Android-free maths for placing surveyed models and points in AR: the frame conventions, the model
placement types and transform, touch-gesture maths for moving a model, screen projection, the
AR-vs-GNSS offset and floor selection. Plain Kotlin/JVM with `explicitApi()`: no Android, ARCore,
Filament, Room or Hilt types. The app's ARCore/Filament code (`app.surrealar.ui.openinar`) feeds it
plain numbers and float arrays and applies the results.

| Package | Contents |
|---|---|
| `.frame` | `ArFrames`: East-Up-South ⇄ ENU (`anchorToEnu`, `enuToAnchor`, `worldDirToAnchor`, `worldDeltaToEnu`), column-major `transformPoint` / `multiplyMV` / `multiplyMM` / `identity`, NDC ⇄ screen pixels |
| `.model` | `Vec3`, `ModelPlacementOrigin` (stored tokens), `PlacementOffsets`, `ModelBounds` (`anchorPoint`, `resolveOffsets`), `ModelPlacement` + `ModelMove`, `ModelCorrectionMatrix.base`, `ModelTransform` (`NORMALIZE_HALF_EXTENT_M`, `normalizationScale`, `nudge`, `compose`, `toLocal`) |
| `.gesture` | `ArMoveMath` (screen ray to ground plane, world delta / view nudge to east-north-up, twist to yaw, vertical drag to metres) |
| `.projection` | `projectToScreen`, `ScreenProjection` |
| `.accuracy` | `ArGnssOffset.compute` (WGS84 geodesic north/east offset from the GNSS fix to ARCore's camera, bearing, camera-height difference), `ArPlacementError.expectedErrorM` (h + d·tan yaw) |
| `.ground` | `GroundSurface`, `MainFloor.choose` (lowest large detected surface) |

## Frame conventions

- **ARCore world**: metres, right-handed, +Y up against gravity; horizontal axes arbitrary per session.
- **Geospatial anchor frame**: anchors are created with an identity rotation, so the frame is
  **East-Up-South** — east = +X, up = +Y, **north = −Z**. `ArFrames.enuToAnchor` / `anchorToEnu` are
  the only place this sign flip lives; the gesture maths and the renderer's move shift go through it
  (`ModelTransform.nudge` keeps the renderer's exact `nudge.z − moveNorthM`).
- **glTF model frame**: metres, +Y up, +Z treated as north. The base correction
  `R(180°Y) · S(effScale) · T(−anchorPoint)` puts model +Z on anchor −Z (north) and model −X on +X (east).
- **Full correction** (`ModelTransform.compose`):
  `T(nudge) · Ry(yaw) · Rx(pitch) · Rz(roll) · base`, then `world = anchorWorld · correction`.
  Yaw is counter-clockwise seen from above. `compose` follows `android.opengl.Matrix`
  `setRotateM` / `rotateM` / `translateM` / `multiplyMM` operation by operation; the app's
  `ModelTransformGoldenTest` (Robolectric) checks it against the real Android sequence.
- **Matrices**: column-major `FloatArray(16)` (element row r, column c at `m[c*4 + r]`; translation in
  `m[12..14]`), as ARCore and OpenGL give them.
- **Screen**: pixels, origin top-left, y down. NDC: x right, y up, −1..1.

## Float vs Double

Render-side maths (matrices, model placement, gestures, projection) is `Float`, because ARCore poses,
Filament transforms and the stored placement values the renderer applies are `Float`. Geodetic maths
(`ArGnssOffset`, `ArPlacementError`) is `Double`, like geokit-core. Code moved here kept its types and
float expressions exactly, so the app renders the same numbers as before; do not widen or narrow types
when changing it.

## How the app uses it

- `ArFilamentRenderer` builds `ModelBounds` from Filament's bounding box, splits offsets with
  `resolveOffsets`, and sets each model's correction from `ModelCorrectionMatrix.base` +
  `ModelTransform.compose`.
- `OpenInARFragment` keeps a session `ModelMove` per point and uses `ArMoveMath` for drag/twist/nudge;
  `projectToScreen` places labels and off-screen arrows; `ArPlacementError` feeds the anchor-gate log.
- `ArVsGnss` (app) wraps `ArGnssOffset` with ARCore's accuracy and the GOOD/FAIR/POOR grade;
  `GroundSnapper` (app) reads ARCore planes and picks the floor with `MainFloor`.
- `ModelPlacement.resolve(coordinate, model)` is an app extension on `ModelPlacement.Companion`, because
  it reads Room entities.
- The model readiness preview (`ArPlacementPreview`) has its own copy of the inverse that matches
  `ModelTransform.toLocal` bit for bit (checked by `ModelTransformGoldenTest`). Known gap: the preview
  ignores the `move*` fields (it previews the saved placement without AR moves).

Policy, thresholds and UI (anchor gate profiles, GNSS gate, altitude policy, view quality, lighting,
depth occlusion, grades) stay in the app.

Tests: `..\gradlew.bat :geokit-ar:test` from `libs/` (no Android), or `.\gradlew.bat :libs:geokit-ar:test` from the repository root.

## Future: control-point alignment

Not implemented. The seam is `ArFrames` (anchor EUS ⇄ ENU). A calibration would fit, from two or more
surveyed control points seen in AR, a 2D similarity in the horizontal plane — yaw, tE, tN (and
optionally scale) — plus a vertical offset, using surveyed ENU from geokit-core `LocalEnu` about the
site origin; checkpoint residuals (horizontal and vertical) would be summarised with geokit-core
`RunningStats`. The result would be applied as an extra outer transform in the anchor frame.
