# Settings & source-configuration architecture

This note records **where each settings/source/config concept lives** so new code uses the right
model and doesn't reintroduce the duplicate "comprehensive location config" blobs that were removed.

## Where things live

| Concern | Package | Type(s) | Persisted? |
|--------|---------|---------|-----------|
| Persisted user preferences (theme, AR/coordinate display, developer, capture, receiver) | `settings.model`, `gnss.settings`, `gnss.capture` | `AppearanceSettings`, `ArDisplaySettings`, `CoordinateDisplaySettings`, `DeveloperSettings`, `GnssReceiverSettings`, `GnssCaptureSettings` | Yes (DataStore) |
| **Selected** location source (what the user chose in Settings) | `domain.model` | `LocationSourceType` (enum) | Yes — stored by **name** in DataStore |
| External receiver transport | `domain.model` | `ExternalConnectionType` (enum) | Yes — stored by **name** in DataStore |
| External receiver connection values (host/port/name/bt addr) | DataStore keys, surfaced as individual `Flow`s on `SettingsRepository` | `externalTcpHost`, `externalTcpPort`, `externalTcpName`, `externalBtAddress`, … | Yes (DataStore) |
| **Active provider** (the live, runtime source actually feeding fixes) | `gnss.source` | `SourceSettings` → `ProviderChoice` (`INTERNAL` / `EXTERNAL_TCP`) | **No** — runtime only |
| Selected-vs-active coordination, startup restore | `gnss.source` | `GnssSourceCoordinator` | No |
| External/Reach receiver data (device info, discovery, corrections) | `gnss.external` / `gnss.external.model` | `ReachDeviceInfo`, … | No (live device data) |
| Core app domain objects | `domain.model` | `Coordinate`, `Model`, … | Room |

## Which model to use

- **"What source did the user pick?"** → `LocationSourceType` (persisted), read via `SettingsRepository.locationSource`.
- **"What source is live right now?"** → `SourceSettings.activeProvider` (`ProviderChoice`). This is **runtime state**, deliberately separate from the persisted selection: at startup the active provider always begins as `INTERNAL`; External is promoted only after the validated reconnect flow (see `docs/gnss-architecture.md`).
- **External transport** → `ExternalConnectionType`.
- **Receiver host/port/name** → the individual `SettingsRepository` flows (`externalTcpHost`, etc.).

## Removed / do-not-reintroduce

Two overlapping "comprehensive location/connection config" data classes were removed in the
domain-model cleanup because they were dead duplication:

- `domain.model.LocationConfig` — **deleted** (was referenced nowhere).
- `domain.model.LocationSettings` — **deleted**. It was a runtime aggregate `combine()`d from the
  individual DataStore flows and exposed as `SettingsRepository.locationSettings`, but nothing
  consumed it and most of its fields were never populated. The individual `SettingsRepository`
  flows (`locationSource`, `externalConnType`, `externalTcpHost`, `externalTcpPort`, `externalTcpName`)
  remain the source of truth.

Do **not** create a new aggregate connection-config class. Read the specific persisted flow you need.

> The enums `LocationSourceType` and `ExternalConnectionType` now live in
> `domain/model/LocationSourceType.kt`. Their constant **names are persisted keys** — never rename them.
