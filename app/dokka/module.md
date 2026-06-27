# Module SurReal AR Developer API

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

# Package app.surrealar.gnss.accumulator

Fuses parsed NMEA sentences into a single current fix. `FixAccumulator` merges fields arriving across
different sentence types (position from GGA/RMC, accuracy from GST, DOP/used-satellite count from GSA)
into an immutable `FixSnapshot`. A snapshot is a point-in-time view: missing fields stay null rather
than carrying stale values forward, and callers must treat an old snapshot as stale, not current.

# Package app.surrealar.gnss.bus

In-memory routing of live GNSS data from the active source to UI/consumers. `FixSwitchboard` selects
exactly one active source (a `SourceAdapter`/`SkyProvider`) and republishes its fixes/sky data through
`FixBus` and `SkyBus`. On source switch the buses must reset so a new source never inherits the
previous one's fix or satellite data. The concrete producers and the NMEA fusion live in
`gnss.bus.adapters`.

# Package app.surrealar.gnss.bus.adapters

Adapter-side contracts and NMEA fusion behind the buses. `NmeaSource`/`RawNmeaProvider` are the
producer interfaces a source implements; `NmeaFuser` consumes raw NMEA and drives a `FixAccumulator`
to publish fused fixes; `GsvMessage`/`GsvEntry` carry normalised satellite data. A malformed or
partial sentence must not break fusion — it updates only what parsed.

# Package app.surrealar.gnss.model

Shared GNSS value types that feed the fused `gnss.accumulator.FixSnapshot`: `Fix`, `Provider`,
`RtkStatus`, `ConnectionStatus`, `LocationStatus`, `TimestampSource`, `Constellation`, and the
satellite/sky models (`SatInfo`, `SkySnapshot`, `SkySource`, `SkyGeometry`). These are plain data
carriers with no Android dependencies. Enum fallbacks (`UNKNOWN`/`OTHER`) mean "not reported by this
receiver", not "none" — quality fields (RTK status, DOP, satellite counts) are indicators that may be
absent or receiver-specific, never guarantees.

# Package app.surrealar.gnss.nmea.parse

Per-sentence NMEA parsers and the registry that dispatches by talker/type. Each parser (`GgaParser`,
`GsaParser`, `GstParser`, `GsvParser`, `RmcParser`, etc.) turns one raw sentence into a typed
`gnss.nmea.sentence` model; `NmeaRegistry`/`DefaultNmeaRegistry` route a line to the right parser.
Parsers must tolerate missing/empty fields and malformed input by returning null rather than throwing —
a bad sentence must never break the stream.

# Package app.surrealar.gnss.nmea.sentence

Typed, immutable representations of individual NMEA sentences (`GGA`, `GSA`, `GST`, `GSV`, `RMC`,
`ZDA`, …) implementing `NmeaSentence`. They model exactly what the sentence carries; absent fields are
nullable and units follow the NMEA spec (e.g. altitude in metres, lat/lon already decimal-degrees from
the parser). They hold no parsing logic — construct them via `gnss.nmea.parse`.

# Package app.surrealar.settings

Settings defaults. `SettingsDefaults` holds the canonical default values applied when a preference has
never been written; the settings value types themselves live in `settings.model`. These are
configuration, not survey records — persisted in DataStore (never Room) and read at startup, so a
settings change must never alter saved coordinates or models.

# Package app.surrealar.settings.model

Immutable settings value types: `AppearanceSettings` (+ `AppThemeMode`), `ArDisplaySettings`,
`CoordinateDisplaySettings`, `DeveloperSettings`, `ExternalReceiverSettings` (+ `ExternalReceiverProfile`),
and `StakeoutSettings`. Each is a plain configuration snapshot with no Android or persistence logic;
the repositories read/write them against DataStore, and the defaults live in `settings.SettingsDefaults`.

# Package app.surrealar.ui.models

Screens for managing imported 3D models: list (`ModelsFragment`/`ModelsViewModel`), add/edit dialogs,
picker (`ModelPickerActivity`), the Filament-based `ModelViewerActivity`, and `ThumbnailCaptureActivity`.
This layer manages model **metadata** and files via the repositories; it does not own the survey
coordinate records that link to a model.

# Package app.surrealar.ui.settings

The settings UI: `SettingsFragment` plus its category list (`SettingsCategory`,
`SettingsCategoryAdapter`) and the developer `SelfTestDisplay`. It edits values through the settings
repositories (DataStore-backed) only — it holds no settings state of its own and writes nothing to Room.

# Package app.surrealar.ui.viewpoints

Coordinate browsing and editing: list/detail (`CoordinatesFragment`, `CoordinateDetailFragment`,
`CoordinatesViewModel`), add/edit dialogs, and the formatting/badge mappers (`CoordinateDetailFormatter`,
`CoordinateDetailUiMapper`). UI mappers are presentation-only — they format stored values for display
and must not mutate the underlying survey position.

# Package app.surrealar.gnss.source

Source selection and persistence for the active GNSS provider. `GnssSourceCoordinator` activates one
source (internal Android location vs. an external NMEA receiver) and restores the last choice at
startup; `SourceSettings` persists it. Switching sources must fully tear down the previous one so its
fixes/satellites do not leak, and external receiver data must never be reported as internal GPS.

# Package app.surrealar.data.local.dao

Room DAOs (`CoordinateDao`, `ModelDao`) — the only sanctioned query surface for the database. Most
methods are ordinary CRUD/observe queries; pay attention to bulk and delete operations and to any
ordering or `@Transaction` guarantees, since those are the parts that affect data integrity. DAOs
return/accept entities (`data.local.entity`); convert to domain models in the repositories, not here.

# Package app.surrealar.data.repository.impl

Repository implementations (`CoordinateRepositoryImpl`, `ModelRepositoryImpl`) — the boundary between
Room entities and domain models. They own the entity↔domain mapping (via `data.repository.mapper`) and
must preserve every survey/model field, including provider/RTK/capture-method values on older rows.
Methods must avoid silent data loss; these are injected via their domain interfaces (`domain.repository`).

# Package app.surrealar.data.settings.repository

DataStore-backed settings repository. `SettingsRepositoryImpl` implements every settings interface
(aggregate and focused) over the single Preferences store. Settings live in DataStore, never in Room;
a settings read/write must never touch saved coordinates or models. Values are read early during
startup, so keep access cheap.

# Package app.surrealar.data.settings.datastore

Low-level settings storage: `SettingsLocalDataSource` (the single `app_settings` Preferences store)
and `SettingsKeys` (the preference key definitions). This is the only place that talks to DataStore
directly; everything else goes through `data.settings.repository`.

# Package app.surrealar.data.settings.migration

Preference-key migrations for the settings store — `SettingsMigrationRunner` upgrades older DataStore
layouts. These are **not** Room migrations and never touch the database; they only reconcile settings
keys/defaults.

# Package app.surrealar.di

Hilt modules wiring the object graph. `DatabaseModule` is the sanctioned bridge to the `AppDatabase`
singleton (UI code must not call `AppDatabase.getDatabase(...)` directly); `RepositoryModule` binds
domain repository interfaces to their impls so consumers inject the interface; `SettingsModule` and
`GnssModule` provide settings/GNSS singletons; `CoroutineModule` provides injected dispatchers and
qualifiers so threading is explicit and testable.
