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

# Package app.surrealar.domain.usecase

Small, injectable use cases that coordinate the highest-risk coordinate workflows, keeping that logic
out of Fragments/ViewModels. `ValidateCoordinateForSaveUseCase` is the shared save gate (bounds, null
island, finite altitude, model-placement checks); `ExportCoordinateBackupUseCase` /
`ImportCoordinateBackupUseCase` build and apply the official full JSON backup (delegating duplicate and
missing-model decisions to `data.backup.BackupImportPlanner`); `CaptureCoordinateUseCase` converts a
completed `CaptureResult` into a validated, saved coordinate. They depend on repository interfaces, not
implementations, and leave Android concerns (URIs, dialogs, files) to the UI. AR data preparation lives
next to its view types in `ui.openinar.PrepareArCoordinateModelsUseCase`.

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

# Package app.surrealar.ui.capture

Standalone averaged-capture screen. `CaptureViewModel` runs an `ObservationSession` against the active
GNSS source under the configured `FixAcceptanceSettings` and saves the result via the repository;
`CaptureDialogFragment` is its view. Capture only completes with a real averaged fix (including
altitude) — it does not fabricate position or altitude.

# Package app.surrealar.ui.home

Home dashboard. `HomeFragment` hosts the map and the plain-language "Field Status" card; `HomeViewModel`
holds its state; `HomeFieldStatusMapper` is a pure mapper from live GNSS fix/source/stream data to that
summary, reusing the shared `GnssStatusFormatter` so it never drifts from the GNSS toolbar.

# Package app.surrealar.ui.rendermap

The full survey map screen. `RenderMapFragment` draws saved coordinates, the meter grid, point labels,
the per-coordinate visibility toggles, and the info bottom sheet; `MapUiStateViewModel` retains camera
and UI state across recreation. The grid/label/spacing logic (`MapGrid`, `PointLabel`, `MapSettings`)
is pure and unit-tested — only the on-map drawing lives in the fragment.

# Package app.surrealar.ui.toolbar

The app-wide GNSS status toolbar. `GnssToolbarStateMapper` maps live fixes to a `GnssToolbarState`,
returning `ToolbarMapResult.Ignore` for stale fixes (older than the max-age threshold) so the toolbar
never shows outdated status; `GnssToolbarRenderer` is the view-only renderer. This is the single source
of GNSS status wording shared across screens.

# Package app.surrealar.ui.viewcoordinates

Saved-coordinates list and export screen. `ViewCoordinatesFragment` + `CoordinatesAdapter` present the
stored points; exports are delegated to `data.export` (CSV/GeoJSON are lossy interchange, not backups).

# Package app.surrealar.ui.development

Debug-only developer screen. `DevelopmentFragment`/`DevelopmentViewModel` surface live fix, sky, and
diagnostics data for troubleshooting GNSS/source issues. Gated behind the developer-tools setting; not
part of the normal field workflow.

# Package app.surrealar.ui.filepicker

In-app file/folder browser used when importing a 3D model file. `FilePickerActivity` + `FilePickerAdapter`
render rows modeled by the `FileItem` hierarchy (back / browse / directory / regular file); the "browse"
entry hands off to the Android Storage Access Framework for the actual file grant.

# Package app.surrealar.ui.map

Map styling helpers. `MapThemeHelper` applies the day/night JSON style to the Google map; a debug flag
can disable custom styling when diagnosing a blank or mis-styled map.

# Package app.surrealar.ui.common

Shared, presentation-only UI building blocks used across screens: the two-pane base fragment, the GNSS
visualizations (`SkyplotView`, `SnrBarView`, `SatelliteSignalChartView`), and formatters
(`DrawerBadgeFormatter`, `StatusBarFormatter`, `TimestampLabels`). These format or render already-computed
state and hold no GNSS or persistence logic.

# Package app.surrealar.ui.components

Small reusable custom views (e.g. `FixBadgeView`) shared across screens. Presentation only.

# Package app.surrealar

App entry points: the `SurRealApplication` (Hilt `Application`) and the single-host `MainActivity`.
`SurRealApplication` runs process startup — settings DataStore, theme, GNSS-source restore, UTM
backfill, mock-location publisher, Maps init; `MainActivity` owns the navigation drawer, `NavController`,
and the GNSS status toolbar. Most screens are fragments under `MainActivity`; see the `ui.*` packages.

# Package app.surrealar.service

Foreground services and their notifications. `LocationService` keeps location running in the
background with a foreground notification; `LocationNotifications` builds that notification and owns
the shared channel id (`surveying_location`) — keep the two in sync so the channel doesn't drift.

# Package app.surrealar.stakeout

Stakeout (navigate-to-target) guidance. `StakeoutGuidance` turns already-computed distance/bearing into
a `StakeoutGuidanceState` (status, arrow angle, direction text); `StakeoutFeedbackGate` rate-limits the
haptic/audio cues. Pure and Android-free — it computes neither distance nor bearing, so the existing
map math is untouched, and tolerance handling is display-only.

# Package app.surrealar.data.files

Model file housekeeping on disk. `ModelFileCleaner` deletes the GLB and thumbnail files backing a
removed model so they don't leak storage. It touches files only — the database row is the repository's
responsibility.

# Package app.surrealar.gnss.accumulator

Fuses parsed NMEA sentences into a single current fix. `FixAccumulator` merges fields arriving across
sentence types (position from GGA/RMC, accuracy from GST, DOP/used count from GSA) into an immutable
`FixSnapshot`. A snapshot is point-in-time: missing fields stay null rather than carrying stale values,
and callers must treat an old snapshot as stale.

# Package app.surrealar.gnss.accuracy

Position-accuracy estimation. `AccuracyEstimator` prefers receiver-reported GST 1-sigma values and
falls back to DOP × an expected UERE (`UereTable`) when GST is absent, returning an `Accuracy1Sigma`.
Either component can be null when nothing is available — an estimate is a quality indicator, not a
guarantee.

# Package app.surrealar.gnss.capture.math

Pure math behind averaged capture: `Geodesy` (lat/lon/alt ↔ ECEF conversions for averaging in a metric
frame) and `RunningStats` (streaming mean/standard deviation). No Android or GNSS-source dependencies,
so both are unit-tested directly. Do not change these conversions without matching test coverage.

# Package app.surrealar.gnss.diagnostics

GNSS troubleshooting tools. `DiagnosticsService` aggregates live diagnostic data; `NmeaDiagnostics`
runs the platform-NMEA self-test; `NmeaLogger` records raw NMEA for inspection. Read-only with respect
to captured coordinates — these observe and report, they don't alter survey data.

# Package app.surrealar.gnss.external

External receiver integration (Emlid Reach RS2+). `TcpNmeaSource`/`ExternalAdapter` stream and adapt
NMEA over TCP into the fix pipeline; the `Reach*Service` classes and `ReachHttpClient` talk to the
device's HTTP/socket API for battery, corrections, and device info. The JSON DTOs here
(`BatteryStatus`, `ReachDeviceInfoDto`) are raw service shapes mapped into the app-facing
`gnss.external.model` types. External data must never be reported as internal GPS.

# Package app.surrealar.gnss.external.model

App-facing, normalized models for the external Reach receiver (`ReachDeviceInfo`, `ReachStorageInfo`,
`ReachBatteryInfo`, `ReachCorrectionsInfo`, plus the connection/command enums). These are deliberately
separate from the service-layer JSON DTOs in `gnss.external`, which parse and map into them.

# Package app.surrealar.gnss.external.repository

Owns external-device state. `ReachDeviceRepository` is the singleton that polls the Reach HTTP API for
device info/storage/battery/connection, keyed off the configured connection profile, and exposes it as
state. It does not handle the NMEA fix stream (that is the external NMEA source); network failures
surface as state rather than throwing.

# Package app.surrealar.gnss.format

Presentation formatting for GNSS status. `GnssStatusFormatter` turns fix quality, accuracy, and source
into the short human-readable strings shown in the toolbar, Home card, and capture dialog. Centralized
here so the wording stays consistent across screens.

# Package app.surrealar.gnss.internal

Internal (Android platform) location source. `InternalNmeaSource`/`InternalAdapter` feed the phone's
fused/GNSS location and platform NMEA into the same fix pipeline as external receivers, so consumers
see one fix stream regardless of source.

# Package app.surrealar.gnss.mock

Mock-location publishing. `AndroidMockLocationPublisher` pushes the active fix into Android's test
provider so other apps see the surveyed position; it requires the app to be selected as the mock
location app in Developer Options and reports a `MockLocationError` when it is not.

# Package app.surrealar.gnss.parser

`NmeaParser` — a thin façade over the `gnss.nmea.parse` registry used by tests and the replay pipeline.
Production sources drive `NmeaFuser` with the injected registry directly, so they do not instantiate
this class. Parsing never throws; an unrecognized line is a normal `ParseResult.Error`.

# Package app.surrealar.gnss.replay

Deterministic NMEA replay for testing and demos. `NmeaReplayController` drives lines from an
`NmeaLineSource` (e.g. `AssetNmeaReplaySource`) through the normal parse/fuse pipeline, so recorded
sessions reproduce a fix stream without a live receiver. Test/dev tooling only.

# Package app.surrealar.gnss.satellites

`SatelliteInventory` — accumulates per-constellation GSV satellite entries into the current sky view
for the skyplot and signal charts. On a source switch it must reset so a new source never shows the
previous receiver's satellites.

# Package app.surrealar.gnss.settings

`GnssReceiverSettings` — receiver tuning preferences (e.g. the high-accuracy location mode). Plain
configuration consumed by the GNSS sources; persisted via the settings layer, not here.

# Package app.surrealar.util

Cross-cutting helpers with no obvious home: coordinate conversion (`UtmConverter`, `GeoUtils`), GLB
georeference detection/reprojection, runtime permissions (`PermissionManager`/`PermissionsGuard` —
keep their permission lists in sync), Reach network discovery (`ReachDiscoveryHelper`,
`ReachNameResolver`), and diagnostics export (`DiagnosticReportExporter`, `LogZip`, `DiagnosticsLogger`).
Stateless utilities; avoid putting feature logic here.

# Package app.surrealar.util.diagnostics

Collectors that assemble the developer diagnostic report: signing fingerprints (`AppSigningInfo`), map
runtime/state (`MapDiagnosticCollector`, `MapRuntimeDiagnostics`), NMEA stream stats, and a settings
snapshot. Read-only — they gather and format existing state for support/debugging and never change it.
