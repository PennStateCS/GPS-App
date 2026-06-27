# Module Surveying App Developer API

Developer-facing API reference for the SurReal AR surveying app. This is generated from KDoc for
developers working on the codebase — it is **not** an end-user manual.

## Data ownership at a glance

- **Room** (`AppDatabase`) stores saved **coordinates** and imported **model metadata** (not the
  model files themselves). It is the source of truth for survey data.
- **DataStore** stores **settings** (the single `app_settings` Preferences store). Settings are
  never stored in Room.

## Coordinate ↔ model linking

- `modelId` links a coordinate to a 3D model record. It supersedes the legacy
  `icon = "model:<id>"` convention, which is still read for backward compatibility through
  `CoordinateModelLink` (the single place that knows about the legacy format).
- `iconKey` is for built-in/simple icons only (e.g. `ic_pin`), used when no model is linked.

## Survey data vs. visual placement

- **Survey data** — latitude/longitude/altitude, RTK status, accuracy, UTM, corrections, etc. —
  is the measured position and must be preserved exactly.
- **Model placement fields** — `modelScale`, `modelYawDeg`/pitch/roll, vertical and origin offsets
  — are **visual overrides** for how a linked model is drawn in AR. They never change the
  coordinate's measured position.

## Stability expectations

- Room **migrations must preserve all saved field data**; the schema is additive and the
  migration chain is contiguous. Do not casually change entities, mappers, or converters.
- These generated docs are for developers; nothing here is published externally.
