# GNSS Architecture

This document describes how GNSS sourcing, routing, and capture are organized, and the rules that
keep live data correct when switching between Internal GPS and an external RS2+ receiver.

## Selected source vs active provider

Two distinct concepts — most past bugs came from conflating them:

| | Type | Meaning | Changes when |
|---|---|---|---|
| **Selected source** | `SettingsRepository.locationSource` (`LocationSourceType`, persisted) | What the user *chose* (Internal / External / Simulator) | Instantly on the Settings radio tap |
| **Active provider** | `SourceSettings.activeProvider` (`ProviderChoice` INTERNAL / EXTERNAL_TCP, in-memory) | What is *actually streaming* fixes right now | Internal: instantly. External: only **after** the receiver is validated |

The toolbar **label** follows the selected source (user intent, shown immediately). The live data
(coords/fix/sats/accuracy/battery) follows the **active provider** via `FixSwitchboard.currentFix`.

> For the full map of where each settings/source/config model lives — and which models were
> removed as duplicates — see [`settings-architecture.md`](settings-architecture.md). Note: the
> dead `domain.model.LocationConfig` and the unused `domain.model.LocationSettings` aggregate were
> deleted; the enums `LocationSourceType` / `ExternalConnectionType` now live in
> `domain/model/LocationSourceType.kt` (their names are persisted DataStore values — do not rename).

## Packages

- **`gnss.source`** — `SourceSettings` (+ `ProviderChoice`) and **`GnssSourceCoordinator`**, the single
  place that flips the **active provider**. Its `activate…Provider` methods ONLY call
  `setActiveProvider(...)` (they do not persist the selected source, disable mock, or validate the
  receiver — see below):
  - `activateInternalProvider(reason)`
  - `activateExternalTcpProvider(host, port, reason)` (host/port logged only; params read from
    settings by `TcpNmeaSource`)
  - `restoreSavedSourceOnStartup()` (the one orchestrating read-decide-activate method)
  `gnss.source` is for **selected source / active-provider coordination** of the *live* system only —
  it does not contain demo/replay code.
- **`gnss.bus`** — `FixSwitchboard` (routes the active provider's fixes/sky, clears stale state on
  switch), `FixBus`/`SkyBus` interfaces, and the shared `NmeaSource`/`GsvMessage` contracts.
- **`gnss.replay`** — asset/demo NMEA replay only: `NmeaLineSource` (raw-NMEA-line interface),
  `AssetNmeaReplaySource` (replays a bundled `.nmea` asset), and `NmeaReplayController` (feeds those
  lines through `NmeaRegistry` → `FixAccumulator`). This produces **raw NMEA lines for demo/testing**,
  not live provider fixes — the live Internal/External GNSS path uses the adapters + `FixSwitchboard`
  instead. (Renamed from the broad `GnssController`/`ReplaySource`/`GnssSource`.)
- **`gnss.internal`** — `InternalAdapter` + `InternalNmeaSource` (Android device GPS via NMEA).
- **`gnss.external`** — `ExternalAdapter`, `TcpNmeaSource`, the Reach services (`ReachDeviceService`,
  `ReachBatteryService`, `ReachCorrectionsService`, `ReachHttpClient`), `model/` (app-facing
  `ReachDeviceInfo`/`ReachBatteryInfo`/`ReachStorageInfo`/`ReachCorrectionsInfo`/…), and
  `repository/ReachDeviceRepository`. Service JSON DTOs (`ReachDeviceInfoDto`, `BatteryStatus`) stay
  private to their service files.
- **`gnss.capture`** — non-UI capture logic. Two distinct settings types here, no longer confusable:
  - **`GnssCaptureSettings`** — the **persisted** user capture/averaging policy (min/max duration,
    min samples, required RTK status, fix/correction age), stored via `SettingsRepository`/DataStore;
    `toAveragingPolicy()` converts it to the runtime `AveragingPolicy`.
  - **`AveragingPolicy`** — runtime averaging policy consumed by **`ObservationSession`** (which
    collects observations and produces **`CaptureResult`**).
  - **`FixAcceptanceSettings`** — **runtime-only** (DI default, not persisted) live-fix eligibility
    thresholds (min sats, max PDOP, allowed RTK set, min dwell) for the "OK to capture" check.
    (Renamed from the old `gnss.settings.CaptureSettings` to remove the name clash.)
  The capture *UI* lives in `ui.capture` / `ui.viewpoints`.
- **`gnss.accuracy`** — `UereTable` + `AccuracyEstimator` (DOP/UERE accuracy estimation). (The unused
  legacy `AccuracySettings`/`UereOverrides` were removed.)
- **`gnss.diagnostics`** — `DiagnosticsService`, `NmeaDiagnostics`, `NmeaLogger`.
- **`gnss.format`** — `GnssStatusFormatter`, shared status/source/accuracy wording.
- **`gnss.settings`** — only **GNSS-specific** settings: `CaptureSettings`/`AccuracySettings`/
  `UereOverrides` and `GnssReceiverSettings`. (GNSS capture policy lives in `gnss.capture` as
  `GnssCaptureSettings`.)

General (non-GNSS) app settings live **outside** the GNSS tree:
- **`settings.model`** — `AppearanceSettings` (+ `AppThemeMode`), `ArDisplaySettings`,
  `CoordinateDisplaySettings`, `DeveloperSettings`. These were moved out of `gnss.settings` so the
  GNSS packages represent only GNSS-specific logic. Persistence is unchanged (field-by-field DataStore
  keys; enum value names preserved).

GNSS **model** types stay in `gnss.model` (e.g. `TimestampSource`), but their **UI label/formatting**
helpers live under UI packages — e.g. `ui.common.TimestampLabels` (`TimestampSource.label()/symbol()`,
`formatTimestampWithSourceBadge`). UI may depend on `gnss.*`; `gnss.*` never depends on UI.

## Source-switching rules

- All source changes go through **`GnssSourceCoordinator`** (the only public path is
  `SourceSettings.setActiveProvider`).
- **External connect order** (in Settings `connectViaTcpFlow`, and the coordinator):
  1. save external connection type
  2. save external host/port
  3. save selected source = External
  4. validate the receiver (the external adapter connects, waits for first data, retries)
  5. **activate** EXTERNAL_TCP only after the persist + stale-attempt guard
- A stale connect attempt (user switched back to Internal mid-connect) must never activate External
  later — guarded by an attempt id and a re-check of the selected source before activation.
- Startup: `restoreSavedSourceOnStartup()` runs once per process. Saved External re-activates
  automatically (no Settings visit, no rotation); if the receiver is unreachable it stays in the
  waiting state rather than falling back to Internal under an RS2+ label.

## Stale-data clearing rules

On every provider rebind, `FixSwitchboard`:
- sets `currentFix` to `null`,
- resets the `_fixes` replay cache and the source-level replay caches,
- resets sky to empty,
- bumps a generation token so a late emission from the old provider's cancelled collector is dropped.

Consumers never read an unfiltered replayed fix stream. `currentFix` is the stale-proof source of
truth; the toolbar/map additionally guard by provider match, max-age (15 s), and valid coordinates.

## Capture source filtering rules

- Capture **mode** follows the selected source: Internal → instant fused capture; External → averaged
  GNSS capture.
- External averaging is fed **only external-provider fixes** (`fix.provider != INTERNAL`), so internal
  fixes can never enter an external average — even if required quality is lowered to Single/DGPS.
- The capture session is cancelled if the provider switches while a dialog is open.
- Maximum sampling time is a **safety timeout**: a timed-out, under-sampled capture shows a timeout
  message and keeps Save disabled (never silently saved).
