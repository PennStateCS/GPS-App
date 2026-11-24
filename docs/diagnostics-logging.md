# Diagnostics logging & export — developer note

**Field workflow.** When something goes wrong, the tester (1) optionally taps Settings → About →
**Mark Problem** and adds a short note, (2) taps **Export Diagnostic Report**, and (3) shares the ZIP
from the Android share sheet. The report stays on the device until shared; nothing is uploaded. The
note and the incident are inside the ZIP, so they are not lost if the ZIP is sent another way. The
developer opens `diagnostic-summary.txt` first (see §5 for a reading guide).

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
- **User-chosen text** (point names, model names, imported file names): log the record id, or
  `LogPrivacy.ref(name)` (`nameRef=#4f1c2a`) / `LogPrivacy.file(fileName)` (`file=#9b03de.glb`, a short
  hash plus the extension) so the same item can be followed across lines without revealing it.
- **Exception messages are free text** (they can hold paths, hosts, names): attach the throwable to a
  W/E line rather than pasting `${e.message}` into an I line; use `error=${LogPrivacy.errorType(e)}`.

## 1. Loggers (where events are stored)

| Logger | Purpose | Storage | Retention | In export? |
|--------|---------|---------|-----------|------------|
| `util/DiagnosticsLogger` | App-event log (the primary diagnostic stream). `i`/`w`/`e` always write to file; `d` writes to file in debug builds only. Every line is timestamped. | `filesDir/diagnostics/logs/app-log-current.txt` (+ rotated `app-log-1..4.txt`) | **5 MB** per active file × (1 current + **4** rotated) ≈ 25 MB | Yes — `app-log-*.txt` |
| `DiagnosticsLogger` error stream | Errors/crashes are **also** mirrored to a separate file so the latest crash is not pushed out by ordinary events and survives across launches. Written by `DiagnosticsLogger.e(...)`. | `app-errors-current.txt` (+ rotated `app-errors-1..2.txt`) | 1 MB per file × (1 + 2) | Yes — `app-errors-*.txt` |
| `util/LogZip` | **Raw NMEA** stream logging — separate and optional. Never mixed into the app event log. | `getExternalFilesDir("logs")/nmea/` | 5 MB per file × 10 rolled | Only via its own NMEA export |

Raw NMEA must never be passed to `DiagnosticsLogger` (privacy + volume). Keep it in `LogZip`.

### Retention policy rationale
The event log is sized (5 MB × 5 files) so a full field session of **state-change** events survives
until the user exports. Below the 20 MB log budget the export bundles the whole set of files; above it,
`LogBudget` keeps crashes, incident windows and recent errors first (see §2), so an important event is
not dropped just because it is old. Crashes live in their own smaller, longer-lived files so the latest
crash — even from a previous app launch — is always in the export. Storage stays bounded: rotated log
files, 20 incidents, and the ZIP's log budget.

## 2. Exported ZIP contents

Built by `DiagnosticReportExporter.export()` (or `buildReport()`, which returns null on failure) into `cacheDir/diagnostic_exports/` as
`surreal-ar-<device>-v<version>-<yyyyMMdd-HHmm>.zip`. The exporter gathers `ReportSources` from the
running app; `report/ReportAssembler` flushes the logger, then writes every file. The list of files and
what each one is lives in one place: `report/ReadmeCollector.FILES` (it becomes README.txt in the ZIP).

- Each collector (`report/*Collector`) is a pure renderer with a golden test over fake inputs
  (`FakeReportSources`); a failing section is replaced by one "Section failed…" line and recorded in
  export-health.txt (see *Export reliability*) instead of breaking the export.
- **Reading order** (also in README.txt): `diagnostic-summary.txt` → `incidents.txt` → `timeline.txt`
  → `export-health.txt` → the detail files.
- `diagnostic-summary.txt` (`report/SummaryCollector`) is rendered **last**, after every collector, so
  it can state completeness. Sections, in order:
  1. **Report information** — report ID, app version + git commit, device, Android, generation time
     (UTC and local), session ID.
  2. **Reported problem** — the latest marked incident: ID, current or earlier session, time, screen,
     the tester's note; or "No problem was marked".
  3. **Important findings** — at most 8 rule-based findings (see §3c), ranked, each with evidence lines
     (time, file, message) and a next step.
  4. **Subsystem status** — one line each: GNSS, receiver connection, RTK corrections, AR tracking,
     models, database & storage, permissions, network.
  5. **Where to investigate** — the file and timestamp behind each finding, then incidents/timeline/health.
  6. **Export completeness** — verdict, failed or timed-out sections, unavailable sources, reduced logs.
  The older **First look** block and the app's own summary follow unchanged, so no detail is lost.
- `incidents.txt` (`report/IncidentsCollector`): every recent incident with its context snapshot and a
  timeline from **5 min before to 2 min after** it (W/E lines, DiagEvents codes and key tags first when
  over the per-incident cap); the incident line is marked.
- `timeline.txt`: the last 2 h of W/E lines plus the LIFECYCLE, NAV, RECEIVER, CORRECTIONS, CAPTURE, AR,
  MODEL, GNSS, DATA, ERROR and DIAG tags and every DiagEvents code; lines inside an incident window are
  prefixed `>>` (chronology is kept).
- **Log budget** (`report/LogBudget`, replaces the old "last 24 h" cut): when the logs exceed the budget
  (`MAX_LOG_BYTES`, 20 MB, planned at 95 % to leave room for redaction), lines are kept in this order
  until the budget is full: crash/ANR lines with their stack traces; every incident window; errors from
  the last 7 days (at most a quarter of the budget); then the newest remaining lines. Gaps are marked in
  the copied log as `… [N lines omitted <from> → <to>] …`, and export-health.txt records each file's
  original and included bytes, time spans and omitted spans. The budget is enforced in bytes, not by a
  time filter; the old 24 h filter is only a fallback if planning fails.
- Everything written passes through `ReportRedactor`, the last line of defence (its KDoc lists every
  rule): raw NMEA; API keys, Authorization/Bearer values, JWTs, URL user info (`ntrip://user:pass@`),
  password/token/user-name/credential values and long tokens after key-like labels; e-mail
  addresses; personal file paths (`/storage/…`, `content://…`, `C:\Users\…`; file names hashed, the
  extension kept); quoted names and notes; and coordinates **by context** (labelled lat/lon/easting/
  northing/position, lat,lon pairs, NMEA `ddmm.mmmm,N`), so accuracy values such as `hAcc=0.0123456`
  survive. `ReportPrivacyTest` builds the complete ZIP from sources full of synthetic secrets and
  scans every entry; `ReportRedactorTest` covers each rule (positive and negative cases).
- Raw NMEA is only ever in the separate, optional NMEA export (`LogZip`); nothing in the ordinary
  report attaches it.
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
  never held in memory), then `diagnostic-summary.txt` (so it can include completeness), then
  `export-health.txt`, then `manifest.txt`.
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
RECEIVER, CORRECTIONS, NMEA, CAPTURE, COORD, DATA, MODEL, AR, MAP, STAKEOUT, EXPORT, ERROR, DIAG`.
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
## 3a. Incident markers ("Mark Problem")

Settings → About → **Mark Problem** lets a tester flag the moment something went wrong and export a
report later. `util/diagnostics/incident/IncidentStore` appends one JSON line per incident to
`filesDir/diagnostics/incidents.jsonl` (newest 20 kept; corrupt or partial lines are skipped and
counted). Each incident has an ID, UTC + local time and zone, session ID, app version, current screen,
the tester's optional note (trimmed, max 500 chars) and an `IncidentContext` snapshot read once from
existing state: source, receiver profile, link (`ReceiverLinkTracker`), RTK / fix age / hAcc / vAcc /
correction age (`GnssFeedTracker.latest`), AR tracking + model counts (`ArSessionDiagnostics`, only
while an AR session is open), free storage and permissions. No coordinates, point names or NMEA.

- The event log gets only `I DIAG INCIDENT_MARKED id=… screen=… session=… hasNote=true|false`.
  (Don't name a non-user field `description=`, `note=` or `comment=`: the report redactor hides those values.)
  The note is free text: it stays in the incident file (and the share text the tester sees), never in the log.
- `IncidentMarker.render()` is the plain-text block for reports (note excluded unless asked for).
- The share text adds the latest incident (last 24 h) and its note under the three prompts.
- Incidents persist across restarts; the report labels each one "current session" or
  "earlier session (session=…)". A mark made from Settings normally shows AR as inactive (AR state is
  only live while the AR screen is open).

## 3b. Structured state-change events and correlation IDs

`util/diagnostics/DiagEvents` defines codes for state **changes** the report reasons about. A coded line
is `CODE key=value … (old human sentence)`, built with `DiagLine.of(...)`; the old sentence stays in
parentheses so people and rules written for older builds still recognise it. Strip the trailing
`(…)` before `DiagEvents.parse`.

| Area | Codes |
|---|---|
| Receiver / GNSS | `RECEIVER_LINK` (CONNECTING, CONNECTED, NO_DATA = TCP up but no NMEA, DISCONNECTED, UNREACHABLE), `RECEIVER_RECONNECTS`, `GNSS_FEED` (LIVE, NO_FIX = NMEA but no usable position, STALE), `RTK_CHANGE`, `CORRECTIONS_STATE`, `SOURCE_MISMATCH`, `ANTENNA_HEIGHT` |
| AR | `AR_UNAVAILABLE`, `AR_STATE` (camera/Earth tracking), `AR_GEO_ACCURACY`, `AR_ANCHOR` (OK/FAILED/DELAYED), `AR_MODEL_HIDDEN` (DISTANCE, VISIBILITY, OFFSCREEN, TRACKING), `AR_ALIGNMENT_JUMP` |
| Models | `MODEL_LOAD`, `MODEL_MISSING_FILE` |

Correlation IDs (`DiagIds`, minted once per operation and reused on all its lines, never per line):
`conn=c…` receiver connection attempt (`ReceiverLinkTracker`), `capture=cap…` capture session
(`CaptureDiagnostics`, also in captures.log), `ar=a…` AR screen session (`ArSessionDiagnostics`),
`op=op…` model import/load and backup/import operations (`DataOpLog`, `ModelsFragment`, renderer).
With `session=` they let you follow one connection, capture or AR session through the timeline.

Rules: log on transitions only (debounce with `logOnChange(stableMs = …)`), short enum values, no
coordinates, names, file names (use `LogPrivacy`) or free text. `DiagEventsContractTest` drives the real
trackers and checks the report recognises every line they write — extend it when you add a code.

## 3c. Findings (summary "Important findings")

`report/ReportSignals.classify` maps a log line (code first, then the legacy sentence) to a signal;
`report/ReportFindings.evaluate` applies fixed rules over the signals, crashes, live state, device
health and data health. The rules look at the 15 min before to 2 min after the latest incident (or
the current session without one). Every finding says how sure it is:

- **OBSERVED** — the logs or the state at export show it happened.
- **POSSIBLE_CAUSE** — consistent with the evidence, not shown directly. A finding that happened up to
  5 min before the mark gets a relation line "POSSIBLE_CAUSE of the reported problem … (timing only,
  not proof)". The report never claims a root cause from timing.
- **UNKNOWN** — the information needed is missing (e.g. integrity check could not run, network unknown).

Rules: crash/ANR (current or previous process), main thread blocked, receiver down/unreachable, TCP up
but no NMEA, repeated reconnects, NMEA but no usable fix, stale fix, RTK FIX lost, corrections stale,
location/camera permission denied, ARCore unavailable, AR tracking lost, geospatial poor or no network,
AR anchors failed/delayed, models hidden (with reason), AR/GNSS alignment jump, point without altitude
(placed on terrain), source mismatch, antenna height problem, model load failed, model file missing,
database integrity/reset/errors, low storage (< 500 MB or < 5 %), network offline/unvalidated/unknown.
`ReportFindingsTest` has a test per rule with both the coded and the legacy line.

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
- **A new state change worth diagnosing** → add a code to `DiagEvents` (with its fields in the KDoc),
  log it with `DiagLine.of` at the place that already knows the state (on change only), reuse the
  operation's correlation ID, then teach `ReportSignals.classify` (keep the legacy sentence) and, if it
  deserves a summary line, add a rule in `ReportFindings` with tests for both line forms.
- **Export** → add a collector in `util/diagnostics/report`, a section in `ReportAssembler` (it gets a
  status and timeout automatically), and an entry in `ReadmeCollector.FILES`.

## 5. Troubleshooting guide: a tester sent a diagnostic ZIP

1. **Open `diagnostic-summary.txt`.** Read *Reported problem* (what and when; current or earlier
   session), then *Important findings* (OBSERVED first) and *Export completeness* — if a section failed
   or logs were reduced, keep in mind that evidence may be missing.
2. **Open `incidents.txt`** and read the 5 min before the mark (`>>` lines in `timeline.txt` show the
   same window in context).
3. **Follow the evidence** — each finding names a file and a time; search that file for the time or the
   correlation ID (`conn=`, `ar=`, `capture=`, `op=`).

Examples:

- **Receiver problem** ("no position", "RTK keeps dropping"): findings *receiver down*, *TCP up but no
  NMEA* (wrong port or receiver output off), *repeated reconnects* (Wi-Fi/hotspot), *RTK FIX lost* with
  *corrections stale* before it (caster/NTRIP or cell coverage). Check `RECEIVER_LINK` lines with one
  `conn=`, `gnss-stats.txt` (reconnects, correction age), `nmea-stream-diagnostics.txt` (sentence
  counts), `settings-snapshot.txt` (receiver and correction settings, credentials hidden) and *source mismatch*.
- **AR tracking problem** ("models drift or vanish"): *AR tracking lost*, *geospatial poor* (no network,
  poor coverage), *anchors delayed/failed*, *alignment jump*, *models hidden* (DISTANCE/VISIBILITY are
  display settings; OFFSCREEN means outside the camera view). Use `ar-session-summary.txt` and the `ar=`
  lines; compare with `RTK_CHANGE` just before an alignment jump.
- **Model failure** ("model doesn't appear"): *model load failed*, *model file missing*, *point without
  altitude* (placed on terrain, not at the surveyed height). `model-integrity.txt` lists each model's
  file state (names hashed) and last AR status; `op=` follows one import or load.
- **Crash / ANR**: *crash* finding (current or previous process). `exits.txt` and `exits/` hold the
  saved traces, `app-errors-*.txt` the stack trace; `sessions.txt` shows whether the session before the
  report ended cleanly.

## 6. Privacy rules (summary)

- Fix leaks where they are logged: ids, counts, `LogPrivacy.ref/file/errorType`, never names, notes,
  coordinates, raw NMEA, credentials or user paths. The tester's incident note stays in the incident
  file, never in the event log.
- `ReportRedactor` is the last line of defence for everything written to the ZIP (§2).
- The ordinary report never includes raw NMEA; that is a separate, optional export (`LogZip`).
- Tests: `ReportPrivacyTest` and `DiagnosticExportEndToEndTest` scan every entry of a complete ZIP for
  synthetic secrets.
