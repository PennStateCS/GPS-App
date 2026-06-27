# Module Surveying App Developer API

Developer-facing API reference for the SurReal AR surveying app, generated from KDoc. This is a
reference for developers working on the codebase — **not** an end-user manual.

> **Public developer docs.** These pages are generated from the `main` branch by GitHub Actions and
> published at <https://pennstatewilkes-barre.github.io/GPS-App/>. They are publicly visible. KDoc
> must **not** contain secrets, API keys, credentials, internal hostnames, or sensitive deployment
> details. Keep those in private docs/secrets, not in source comments.

## Developer starting points

- **Capture a coordinate** → `gnss.capture.ObservationSession` (averaging) → `domain.model.CoordinateFactory`
  builds a `domain.model.Coordinate`, persisted via `data.repository.mapper`.
- **GNSS data flow** → `gnss.bus.FixSwitchboard` routes the active source's fixes;
  `gnss.accumulator.FixAccumulator` fuses NMEA sentences into a `FixSnapshot`.
- **Persistence** → `data.local.db.AppDatabase` (Room) is the source of truth for coordinates and
  imported model metadata. Settings live in DataStore (`data.settings`), never in Room.
- **Backup / restore** → `data.export.CoordinateBackup` (format) + `data.backup.BackupImportPlanner`
  (merge/replace + missing-model decisions).
- **AR rendering** → `ui.openinar` loads model-linked coordinates and draws them at geospatial anchors.

## Quick links by concept

| Concept | Where to look |
|---|---|
| Coordinate domain model & validation | `domain.model.Coordinate`, `domain.coordinates.CoordinateValidator` |
| Coordinate ↔ model linking (legacy compat) | `domain.model.CoordinateModelLink` |
| Capture method / provider semantics | `domain.model.CaptureMethod`, `gnss.model.Provider` |
| Entity ↔ domain mapping | `data.repository.mapper` (`CoordinateMappers`), `data.repository.impl.ModelRepositoryImpl` |
| Room schema / converters / migrations | `data.local.db` (`AppDatabase`, `Converters`, `Migrations`) |
| Full backup export / import | `data.export.CoordinateBackup`, `data.backup.BackupImportPlanner` |
| Data health diagnostics | `data.health.DataHealthChecker` |
| Averaged GNSS capture | `gnss.capture.ObservationSession`, `AveragingPolicy` |
| NMEA fusion | `gnss.accumulator.FixAccumulator` |
| AR model placement & rendering | `ui.openinar.ModelPlacement`, `ui.openinar.ArFilamentRenderer` |

## Data ownership at a glance

- **Room** (`AppDatabase`) stores saved **coordinates** and imported **model metadata** (not the
  model files themselves).
- **DataStore** stores **settings** (the single `app_settings` Preferences store).
- `modelId` links a coordinate to a model; the legacy `icon = "model:<id>"` convention is still read
  through `CoordinateModelLink`. `iconKey` is for built-in icons.
- Model placement fields on a coordinate are **visual overrides** for AR — they never change the
  measured survey position.

## Stability expectations

- Room **migrations must preserve all saved field data**; the schema is additive and the migration
  chain is contiguous. Do not casually change entities, mappers, or converters.
- These docs are generated from `main` by CI for developer reference.

# Package app.surrealar.domain.coordinates

Pure (no Android, no DAO) coordinate validation and statistics. `CoordinateValidator` gates saves and
imports — rejecting out-of-range, NaN, and 0,0 "null island" positions — and detects duplicates.

# Package app.surrealar.domain.model

Core domain models and their helpers: `Coordinate` (the survey record), `Model` (imported 3D model
metadata), `CaptureMethod`, and `CoordinateModelLink` (the single compatibility layer for the legacy
`icon = "model:<id>"` convention). `CoordinateFactory` builds coordinates from captured fixes/results.

# Package app.surrealar.domain.repository

Repository interfaces (the domain's data-access contract): `CoordinateRepository`, `ModelRepository`,
and the settings interfaces. Settings are split into focused interfaces (`GnssCaptureSettingsRepository`,
`ArDisplaySettingsRepository`, etc., in `FocusedSettingsRepositories.kt`) plus the aggregate
`SettingsRepository` that unions them. **Prefer the narrowest focused interface** so a consumer
depends only on the settings area it uses; all of them resolve to the same singleton
`SettingsRepositoryImpl` over the single Preferences DataStore. Implementations live in `data`, not
here — keep this layer free of Android and Room types.

# Package app.surrealar.data.local.db

Room database layer: `AppDatabase` (the single connection, obtained via Hilt), type `Converters`
(provider/RTK/correction enums and Instant/Duration), and `Migrations` (a contiguous, additive,
data-preserving chain). Schema changes here are high-risk — see the stability notes above.

# Package app.surrealar.data.local.entity

Room `@Entity` rows: `CoordinateEntity` (table `coordinates`) and `ModelEntity` (table `models`).
These define the on-disk schema — column names, types, and indices are load-bearing. **Do not edit an
entity without a matching `Migrations` step and a regenerated exported schema JSON**; an unmigrated
change corrupts existing installs. Entities are persistence shapes only; convert to/from
`domain.model` via `data.repository.mapper` rather than using them as domain objects.

# Package app.surrealar.data.export

Export and full-backup formats. `CoordinateBackup` is the official, schema-versioned full backup
(`format = "surreal-coordinate-backup"`): it round-trips every survey-quality and model-link field so
a restore loses no data, and it bundles referenced model metadata. `CsvExporter` / `GeoJsonExporter`
are lossy, human/GIS-friendly views (id/name/lat/lon/alt/timestamp/icon/color) — not for backups.
Bump `SCHEMA_VERSION` and keep readers backward-compatible when the backup shape changes; import
planning (duplicates, missing-model handling) lives in `data.backup`, not here.

# Package app.surrealar.data.repository.mapper

Entity ↔ domain mapping for coordinates. Every field must round-trip between `Coordinate` and
`CoordinateEntity`; the provider mapping is deliberately lossless (e.g. `RS2_EXTERNAL` does not
degrade to `RS2_TCP`), and audit timestamps fall back to the capture timestamp when unset.

# Package app.surrealar.data.backup

Pure import-planning for coordinate backups. `BackupImportPlanner` turns parsed coordinates plus the
local id sets into a `BackupImportPlan` (duplicate handling, missing-local-model flagging, and the
user-facing summary) without touching Android or the database.

# Package app.surrealar.data.health

Read-only developer diagnostics. `DataHealthChecker` scans coordinate/model records for invalid
positions, missing provenance, dangling model links, legacy icons, and suspicious placement/file
state. It never modifies data and is exposed only in debug builds.

# Package app.surrealar.gnss.capture

Averaged GNSS capture. `ObservationSession` collects accepted fixes under an `AveragingPolicy`
(minimum sampling time, minimum accepted fixes, required RTK quality, max fix/correction age) and
finishes when both minimums are met or the maximum sampling time is reached.

# Package app.surrealar.ui.openinar

AR rendering of model-linked coordinates. Loads GLB models at ARCore geospatial anchors and applies
per-coordinate `ModelPlacement` (scale/rotation/offset) on top of each model's defaults. Placement is
a visual transform only.
