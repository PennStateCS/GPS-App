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

## Packages

- **`gnss.source`** — `SourceSettings` (+ `ProviderChoice`) and **`GnssSourceCoordinator`**, the single
  non-UI entry point for source actions (`switchToInternal`, `connectExternalTcp`,
  `disconnectExternal`, `restoreSavedSourceOnStartup`).
- **`gnss.bus`** — `FixSwitchboard` (routes the active provider's fixes/sky, clears stale state on
  switch), `FixBus`/`SkyBus` interfaces, and the shared `NmeaSource`/`GsvMessage` contracts.
- **`gnss.internal`** — `InternalAdapter` + `InternalNmeaSource` (Android device GPS via NMEA).
- **`gnss.external`** — `ExternalAdapter`, `TcpNmeaSource`, the Reach services (`ReachDeviceService`,
  `ReachBatteryService`, `ReachCorrectionsService`, `ReachHttpClient`), `model/` (app-facing
  `ReachDeviceInfo`/`ReachBatteryInfo`/`ReachStorageInfo`/`ReachCorrectionsInfo`/…), and
  `repository/ReachDeviceRepository`. Service JSON DTOs (`ReachDeviceInfoDto`, `BatteryStatus`) stay
  private to their service files.
- **`gnss.capture`** — `ObservationSession`, `AveragingPolicy`, `CaptureResult`,
  `GnssCaptureSettings` (non-UI averaging logic). The capture *UI* lives in `ui.capture` /
  `ui.viewpoints`.
- **`gnss.diagnostics`** — `DiagnosticsService`, `NmeaDiagnostics`, `NmeaLogger`.
- **`gnss.format`** — `GnssStatusFormatter`, shared status/source/accuracy wording.

UI may depend on `gnss.*`; `gnss.*` never depends on UI.

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
