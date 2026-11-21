# Diagnostics logging & export — developer note

This note describes how app diagnostics are captured, what the exported diagnostic ZIP contains,
and where to add new diagnostic events. It complements the KDoc on `DiagnosticsLogger`,
`DiagnosticReportExporter`, and `LogZip`.

## 1. Loggers (where events are stored)

| Logger | Purpose | Storage | Retention | In export? |
|--------|---------|---------|-----------|------------|
| `util/DiagnosticsLogger` | App-event log (the primary diagnostic stream). `i`/`w`/`e` always write to file; `d` writes to file in debug builds only. Every line is timestamped. | `filesDir/diagnostics/logs/app-log-current.txt` (+ rotated `app-log-1..4.txt`) | **5 MB** per active file × (1 current + **4** rotated) ≈ 25 MB | Yes — `app-log-*.txt` |
| `DiagnosticsLogger` error stream | Errors/crashes are **also** mirrored to a separate file so the latest crash is not pushed out by ordinary events and survives across launches. Written by `DiagnosticsLogger.e(...)`. | `app-errors-current.txt` (+ rotated `app-errors-1..2.txt`) | 1 MB per file × (1 + 2) | Yes — `app-errors-*.txt` |
| `util/LogZip` | **Raw NMEA** stream logging — separate and optional. Never mixed into the app event log. | `getExternalFilesDir("logs")/nmea/` | 5 MB per file × 10 rolled | Only via its own NMEA export |

Raw NMEA must never be passed to `DiagnosticsLogger` (privacy + volume). Keep it in `LogZip`.

### Retention policy rationale
The event log is sized (5 MB × 5 files) so a full field session of **state-change** events survives
until the user exports. The export bundles the *whole* set of files (not a last-N-lines tail), so the
AR failure sequence is never truncated. Crashes live in their own smaller, longer-lived files so the
latest crash — even from a previous app launch — is always in the export.

## 2. Exported ZIP contents

Built by `DiagnosticReportExporter.buildReport()` into `cacheDir/diagnostic_exports/` as
`surreal-ar-<device>-v<version>-<yyyyMMdd-HHmm>.zip`. The exporter gathers `ReportSources` from the
running app; `report/ReportAssembler` flushes the logger, then writes every file. The list of files and
what each one is lives in one place: `report/ReadmeCollector.FILES` (it becomes README.txt in the ZIP).

- Each collector (`report/*Collector`) is a pure renderer with a golden test over fake inputs
  (`FakeReportSources`); `ZipBuilder.textSafe` keeps one failing section from breaking the export.
- `diagnostic-summary.txt` starts with the **First look** block (report ID, time span, sessions,
  last session clean, crashes/ANRs in 24 h, source/RTK, link, correction age, storage, top warnings).
- Logs over 20 MB → only the last 24 h of each log file; README and manifest say so.
- Everything written passes through `ReportRedactor` (raw NMEA, API keys, password/token values and
  6+-decimal coordinates are replaced). `ReportRedactionTest` builds a full report from leaky fakes.
- The share intent: To = `BuildConfig.DIAG_REPORT_EMAIL` (Gradle property `DIAG_REPORT_EMAIL`, blank by
  default), subject with device/version/time, body with three prompts and the report ID.

## 3. Event categories (tags)

Only the tags in `util/diagnostics/DiagTags` are used: `APP, LIFECYCLE, NAV, SETTINGS, SYSTEM, GNSS,
RECEIVER, CORRECTIONS, NMEA, CAPTURE, COORD, DATA, MODEL, AR, MAP, STAKEOUT, EXPORT, ERROR`.
Line format (`LogLineFormat`): `2026-11-21T14:03:07.412-05:00 W RECEIVER [main] message`.

- One place per kind of event: `LifecycleLogger`, `NavLogger`, `SettingsChangeLogger`,
  `SystemEventsLogger`, `GnssFeedLogger` (`GnssFeedTracker`, `ReceiverLinkTracker`, `ClockOffset`),
  `CaptureDiagnostics` (+ `diagnostics/captures.log`), `DataOpLog` / `DbDiagnostics`, `CoordDiagnostics`,
  `HeadingDiagnostics`, `ToolbarLinkLog`.
- Hot paths use `DiagnosticsLogger.logOnChange(tag, key, value) { msg }` or
  `every(tag, key, intervalMs) { msg }` — the message lambda runs only when a line is written.
  `LogBudgetTest` keeps an hour of 5 Hz fixes plus app events under 2 MB.
- Crashes: `DiagnosticsLogger.writeCrashSync` (fsynced, before the process dies). Exit reasons and
  ANR traces: `ExitReasonRecorder` (`diagnostics/exits/`). Sessions: `SessionTracker` (SESSION_START).
- Never log raw NMEA, coordinates (lat/lon/height, ECEF, N/E), passwords or keys.
## 4. Where to add future diagnostic events

- **AR lifecycle / anchors / model load** → `ui/openinar/OpenInARFragment` and
  `ui/openinar/ArFilamentRenderer` (tag `AR`). Already instrumented: screen open/close,
  session resume, Earth + camera tracking **state-change** events, anchor create/fail, accuracy gate,
  distance skips, preload start/success/fail, scene-state counts. **Never log per frame** — gate on
  state change (see `loggedAccuracyGate`, `lastDistanceSkippedIds`, `lastEarthTrackingLogged`,
  `lastCameraTrackingLogged`, the `progress == lastModelProgress` early-returns).
- **Per-AR-session model status** → `util/ArSessionDiagnostics` (process-wide holder). The fragment
  records QUEUED/SKIPPED + skip reason and tracking/anchor tallies; the renderer reports
  LOADING/READY/FAILED (`parse_failed`/`filament_asset_failed`/`lifecycle_cancelled`); IN_SCENE is
  set from `modelLoadState`. It feeds `ar-session-summary.txt` and `model-integrity.txt`'s
  `lastArStatus`. Keep it a compact summary — do NOT turn it into an event log.
- **GNSS source / provider switching** → `gnss/source/GnssSourceCoordinator` + `SourceSettings`
  (tag `GNSS`). Summarize high-frequency fixes; log provider/state changes only.
- **Coordinate save flow** → the add/edit coordinate dialogs + `CoordinateRepository` callers
  (tag `COORD`). Do not log personal notes.
- **Export** → add a collector in `util/diagnostics/report`, a line in `ReportAssembler`, an entry in
  `ReadmeCollector.FILES`, and a warning in `DiagnosticReportExporter.computeWarnings()` if needed.

To add a warning to the summary, append a line in `DiagnosticReportExporter.computeWarnings()`.
