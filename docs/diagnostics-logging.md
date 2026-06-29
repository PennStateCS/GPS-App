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

Built by `DiagnosticReportExporter.buildReport()` into `cacheDir/diagnostic_exports/`:

| File | Contents |
|------|----------|
| `diagnostic-summary.txt` | Report time, **Important warnings**, app/build/git, device, maps renderer |
| `app-log-current.txt`, `app-log-1..4.txt` | Recent diagnostic event log (all categories below) |
| `app-errors-current.txt`, `app-errors-1..2.txt` | Errors/crashes (separate so the latest crash is never lost) |
| `app-state-summary.txt` | Live GNSS/receiver/network/map state (selected source, active provider, current fix, host:port presence — no credentials) |
| `coordinates.txt` | Coordinate counts + coordinate→model association rows (no raw lat/lon) |
| `models.txt` | Model inventory: basename, file existence, size, linked-coordinate count |
| `current-settings-snapshot.txt` | Non-sensitive, debug-relevant settings (`SettingsSnapshotCollector`) |
| `map-troubleshooting.txt`, `nmea-stream-diagnostics.txt` | Map + NMEA-stream collectors |

**Important warnings** surfaced in the summary include: selected GNSS source ≠ active provider;
external GNSS selected but no receiver configured; coordinates link a model whose file is missing.
AR-tracking / parse / anchor outcomes are reconstructed from the event log (tags `AR` / `AR_MDL`).

Each section is wrapped so one failing section cannot abort the whole export (`addTextSafe`,
`runCatching` around data gathering); the failure is logged instead.

## 3. Event categories (tags)

Use a consistent tag string as the first arg to `DiagnosticsLogger`:

- `APP` — startup/session metadata
- `GNSS` / `SOURCE` — source selection, provider switching, fix/satellite summaries
- `CORRECTIONS` — correction age/source/station id
- `COORD` — coordinate create/edit/save/delete/link/visibility
- `MODEL` — model import/storage/link
- `AR` — AR screen + ARCore session/earth/camera tracking lifecycle
- `AR_MDL` — model preload/parse/asset/anchor/scene state (value of `ArFilamentRenderer.DIAG`)
- `EXPORT` — diagnostic export
- `ERROR` — non-fatal exceptions / crashes (use `DiagnosticsLogger.e`)

## 4. Where to add future diagnostic events

- **AR lifecycle / anchors / model load** → `ui/openinar/OpenInARFragment` and
  `ui/openinar/ArFilamentRenderer` (tags `AR` / `AR_MDL`). Already instrumented: screen open/close,
  session resume, anchor create/fail, accuracy gate, distance skips, preload start/success/fail,
  scene-state counts. **Never log per frame** — gate on state change (see `loggedAccuracyGate`,
  `lastDistanceSkippedIds`, the `progress == lastModelProgress` early-returns).
- **GNSS source / provider switching** → `gnss/source/GnssSourceCoordinator` + `SourceSettings`
  (tags `SOURCE` / `GNSS`). Summarize high-frequency fixes; log provider/state changes only.
- **Coordinate save flow** → the add/edit coordinate dialogs + `CoordinateRepository` callers
  (tag `COORD`). Do not log personal notes.
- **Export** → `DiagnosticReportExporter`; add a new `coordinates/models/...`-style section via
  `zos.addTextSafe(name) { ... }`, and add a warning in `computeWarnings()`.

To add a warning to the summary, append a line in `DiagnosticReportExporter.computeWarnings()`.
