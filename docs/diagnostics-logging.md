# Diagnostics logging & export — developer note

This note describes how app diagnostics are captured, what the exported diagnostic ZIP contains,
and where to add new diagnostic events. It complements the KDoc on `DiagnosticsLogger`,
`DiagnosticReportExporter`, and `LogZip`.

## 0. Logging rules (read before adding or changing a log line)

The exported log exists so a tester's report shows **what failed and why** without drowning it in
routine chatter. Every line should answer "what happened" for a support reader.

### Which API

| API | Goes to | Use for |
|-----|---------|---------|
| `DiagnosticsLogger.e(tag, msg, t)` | logcat + event file + error file | An operation **failed** and the user lost a feature or data. Always pass the exception when there is one. |
| `DiagnosticsLogger.w(tag, msg, t)` | logcat + event file | **Unexpected** but handled: fallback used, retries exhausted, degraded mode, bad input skipped. Not for normal waiting states (no fix yet, connecting, localizing). Pass the exception when there is one. |
| `DiagnosticsLogger.i(tag, msg)` | logcat + event file | Lifecycle, user actions, **state changes** and **outcomes** (connected, saved, exported, model ready). |
| `DiagnosticsLogger.d(tag) { msg }` | debug builds only: logcat + event file | Developer detail useful in a debug build's report (config dumps, per-item detail). Nothing is built or printed in release. |
| `DevLog.d(tag) { msg }` / `DevLog.every(...)` | debug builds only: logcat | Live developer traces. Replaces raw `android.util.Log.d/i`. |
| `GnssDebugLog` / `GnssLog.every` | debug builds only: logcat (`GnssDebug`) | GNSS traces; the **only** place coordinates and raw NMEA may appear. |
| `GnssLog.diagnostic*` (libs) | → `DiagnosticsLogger.i/w/e` | Library events for the exported log. |

Don't call `android.util.Log` directly in app code. `DiagnosticsLogger` already writes to logcat, so
never pair `Log.x(...)` with `DiagnosticsLogger.x(...)` for the same event (it prints twice). Tags in
the file log must be one of `DiagTags` (debug builds warn once about any other tag).

### Volume rules

- **Nothing per frame, per fix, per NMEA sentence, per satellite or per loop item.** Use a summary
  line ("test grid preload n=25 ok=25 failed=0"), `logOnChange`, or `every`.
- **Ongoing status:** at most once a minute, and only when something changed (`logOnChange` on a
  bucketed value plus a long `every` as a heartbeat).
- **Flickering states** (link STALE/OK, FIX/FLOAT, tracking, distance-filter edges):
  `logOnChange(tag, key, value, stableMs = 3_000L) { … }` — logged only after the value holds.
- **Retry loops:** log the first failure in full (with the exception), then
  `every(tag, key, 60_000L, 'W') { "still failing: attempts=n last=…" }`, then the recovery once.
- **Repeated identical errors** (a parse error per message, a draw error per frame):
  `every(tag, key, 60_000L, 'E', throwable = e) { … }`.
- Build expensive messages inside the lambda forms so throttled / release calls cost nothing.

### Catch blocks

- An **unexpected** exception is never swallowed silently: log it (`w` if handled, `e` if the
  operation failed) with the throwable. Rethrow `CancellationException`.
- **Expected** exceptions (closing a socket on shutdown, number-parse fallbacks, best-effort cleanup)
  may be ignored, with a short comment saying why.

### Message style

One shape for every line, so the log reads like one voice and can be searched:

```
<Subject> <what happened>[: <short reason>] key=value key=value
```

- **Subject first, then a past-tense verb or a state**, sentence case, no trailing period:
  `External receiver connected attempt=1`, `Backup export failed rows=120 dest=saf`,
  `AR session resumed`, `Corrections stream connected channel=RTCM`.
  Not: `connected!`, `Error`, `NEW: …`, `We are now…`, `Oops`, `failed to do thing.`
- **Subjects are consistent nouns** for the same thing everywhere: `External receiver` (the Reach over
  TCP), `Device GNSS` (the tablet's own GNSS — never "phone"), `Corrections`, `Mock location`,
  `AR session`, `Home map`, `Map`, `Model`, `Backup`, `Settings`.
- **Failures:** `<Operation> failed[: <reason>] key=…` with the exception attached. Don't repeat
  `${e.message}` in the text when the throwable is attached (W/E print it); I lines that can't carry a
  throwable may end with `error=<ExceptionSimpleName>`.
- **Retries and waits:** `… (will retry)` / `still unreachable attempts=n` / `… recovered after n attempts`.
- **Transitions:** `<Subject> <OLD> → <NEW>` (the arrow `→`, spaces around it).
- **Event codes:** existing `UPPER_SNAKE` codes that readers or people search for stay as the first word
  (`SESSION_START`, `AR_VS_GNSS`, `AR_QUALITY`, `MOCK_LOCATION_INJECTED`, `MODEL_READINESS` …), followed
  only by `key=value` pairs. Don't invent new codes for one-off events — write a sentence instead.
- **key=value:** lowerCamel keys; units as a key suffix with plain numbers (`hAccM=0.02 ageMs=340
  distM=12.5`); booleans `true`/`false`; missing values `unknown` (not `?`, `n/a`, `null`); text with
  spaces in double quotes (`reason="no data"`); lists `[a,b]`. No space around `=`.
- **Separators:** `: ` introduces a reason or detail. Avoid ` — ` and ` - ` as separators in log text,
  and parentheses for key data (use key=value).
- **No:** exclamation marks, emojis, ALL-CAPS words for emphasis, first person, internal jokes, class or
  method names as the subject of an exported line (fine in DevLog), or stack-trace text in the message.

Readers depend on these line starts and fields — **don't change them**: `SESSION_START` (and its
`session=`, `lastSessionClean=` fields), `Previous process exit` (`reason=`), `Uncaught exception
(fatal)`, `Database migrated` / `Database created` / `Database reset` (see `report/LogIndex`,
`SessionsCollector`, `FirstLookCollector`).

### Message content

- Say what failed and the key context: `"Backup export failed rows=120 dest=SAF"`, not `"error"`.
- Use `key=value` for values so lines can be searched and compared.
- **Never**: coordinates (lat/lon/height, ECEF, northing/easting), raw NMEA, point names or notes,
  passwords, keys, tokens, full file paths with user names. Use IDs, counts, booleans and accuracy
  values instead.

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

Built by `DiagnosticReportExporter.export()` (or `buildReport()`, which returns null on failure) into `cacheDir/diagnostic_exports/` as
`surreal-ar-<device>-v<version>-<yyyyMMdd-HHmm>.zip`. The exporter gathers `ReportSources` from the
running app; `report/ReportAssembler` flushes the logger, then writes every file. The list of files and
what each one is lives in one place: `report/ReadmeCollector.FILES` (it becomes README.txt in the ZIP).

- Each collector (`report/*Collector`) is a pure renderer with a golden test over fake inputs
  (`FakeReportSources`); a failing section is replaced by one "Section failed…" line and recorded in
  export-health.txt (see *Export reliability*) instead of breaking the export.
- `diagnostic-summary.txt` starts with the **First look** block (report ID, time span, sessions,
  last session clean, crashes/ANRs in 24 h, source/RTK, link, correction age, storage, top warnings).
- Logs over 20 MB → only the last 24 h of each log file; README and manifest say so.
- Everything written passes through `ReportRedactor` (raw NMEA, API keys, password/token values and
  6+-decimal coordinates are replaced). `ReportRedactionTest` builds a full report from leaky fakes.
- The share intent: To = `BuildConfig.DIAG_REPORT_EMAIL` (Gradle property `DIAG_REPORT_EMAIL`, blank by
  default), subject with device/version/time, body with three prompts and the report ID.

### Export reliability

- **Off the main thread:** `DiagnosticReportExporter.export()` / `buildReport()` run on
  `Dispatchers.IO` (flush, database reads, log copies). The Settings button only launches it.
- **Section statuses:** `ReportAssembler.write` runs every text section and file-list source through a
  runner that records a `SectionStatus` (`OK`, `FAILED` with exception type + redacted, capped message,
  `TIMED_OUT`, `UNAVAILABLE` with a reason) and the elapsed ms. Suspending sources get a per-section
  timeout (`DEFAULT_SECTION_TIMEOUT_MS` = 10 s, injectable); blocking sources cannot be interrupted and
  run to the end. `CancellationException` is always rethrown (`ReportFailures.catching`, never
  `runCatching` around suspend calls). The flush result (`FLUSHED` / `NOT_CONFIRMED` / `FAILED`) is kept.
- **Order:** text sections are collected into memory first, then the ZIP is streamed (logs line by line,
  never held in memory), then `export-health.txt`, then `manifest.txt`. So a summary can be rendered
  after the collectors; `ReportResult.completenessSummary()` is the short block meant for it (for now
  README.txt points to export-health.txt).
- **export-health.txt:** every status and time, the flush, files omitted (unreadable, unsafe or duplicate
  name) or truncated (original → included bytes; the 24 h log filter is expected, a read error is not),
  and the verdict `COMPLETE` / `PARTIAL`.
- **Manifest:** lists every entry with its size and itself as "(this file)"; it matches the ZIP exactly.
- **ZIP integrity:** `ReportAssembler.export` writes `<name>.zip.part`, closes it, reopens it with
  `ZipFile` and checks the entry count and every name + size, then renames it. Any failure deletes the
  temp file and returns `ExportOutcome.Failure(NO_SPACE | WRITE_FAILED | VALIDATION_FAILED | CANCELLED |
  UNKNOWN)`; the caller's own cancellation deletes it and is rethrown. A valid ZIP with failed sections is
  `Success(partial = true)`. `.part` files older than 1 h are removed by the next export.
- **Entry names** built from file names (logs, exit traces, AR tracks) are limited to `A-Z a-z 0-9 . _ - /`,
  no `..`, no leading `/`, at most 120 chars (`ZipBuilder.safeEntryName`).

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
