# GNSS toolbar and GNSS Status report

The app-wide GNSS toolbar (the navy strip under the app bar) is a compact, quiet summary. Everything else — exact
correction age, satellites, vertical accuracy, battery, diagnostics — lives in the **GNSS Status report**, a bottom
sheet opened by tapping the toolbar.

Code: `app/src/main/java/app/surrealar/ui/toolbar/` (mapper, renderer, pipeline), `ui/toolbar/report/` (report),
`gnss/status/` (shared interpretation + correction hysteresis), `res/layout/location_status_bar.xml`,
`res/layout/sheet_gnss_status.xml`.

## Data flow

```
FixSwitchboard.currentFix / sky ─┐
settings (source, coordinates,   │   combine → sample(250 ms) → convert + map (Dispatchers.Default)
  receiver, capture limits)      ├─▶ ToolbarPipeline ──────────────────────────────▶ ToolbarFrame ──▶ GnssToolbarRenderer
ReceiverConnectionController     │     • last-fix memory (lost-fix display)              │
  .liveSource (kind+transition)  │     • CorrectionHealthTracker (hysteresis)            └──▶ GnssStatusSheet (while open)
ReceiverTiltMonitor.status       │     • GnssToolbarStateMapper + GnssStatusInterpreter         + battery / device info /
  (RS4 position reference)       │                                                               corrections / tilt /
link state, 1 s tick ────────────┘                                                               DiagnosticsService
```

No new GNSS subscription: the report reads the toolbar's own latest frame (`GnssStatusHost.gnssToolbarFrames`) plus
existing app-wide StateFlows, and only while it is visible. Live source, tilt reference and the capture limit are
`distinctUntilChanged` before they join the toolbar pipeline, so they do not add per-fix work.

## Compact toolbar

One line. The **left group** is anchored left, the **right group** right, flexible space between.

| Group | Field | Notes |
|-------|-------|-------|
| Left  | Source | Receiver profile short label (`RS4 Pro`), `Internal`, or `REPLAY` (with icon). `SRC` prefix hidden in portrait. |
| Left  | Solution | `Fix` / `Float` / `DGPS` / `Single` / `No Fix`; without a fix it says why (below). Colour from the shared interpretation: **green only for RTK Fix with a receiver-reported hAcc ≤ 0.05 m and no warning** — RTK Fix is never treated as guaranteed accuracy. |
| Left  | H accuracy | Horizontal only, 68 % radius; `~` = estimated from DOP (never green). External colours: green ≤ 0.05 m reported, amber ≤ 1 m, red above (same thresholds as Home). Device GNSS keeps 5 m / 15 m. |
| Left  | Warning | Only when something needs attention (icon + text on a red/amber pill — never colour alone). Highest priority first; TalkBack reads all. No permanent "OK". |
| Left  | Low battery | Receiver battery only at ≤ 20 % (the existing graphic's critical band: charging keeps the warning colour below 20 %). Normal levels → report. |
| Right | Coordinates | `NE` grid or `LL` lat/lon in the selected system (unchanged format). |
| Right | Elevation | Height-reference label (`NAVD88`, `h`, `H rcvr`, `EGM2008`, `NAPGD2022β`) + value; `loading` while the model loads. |
| Right | Chevron | "More": opens the report. |

Removed from the compact toolbar (now live in the report): exact correction age, satellite counts, vertical
accuracy, battery % / charging at normal levels, receiver diagnostics.

**Stable widths:** tabular figures; the solution and accuracy values have minimum widths (`Single`, `~±99.99 m`);
coordinates and elevation ratchet to the widest value of their current format (reset when LL↔NE, height reference or
unit changes).

### Narrow widths — priority rules (`ToolbarPriority`)

Applied in order until the line fits:

1. hide the `SRC` / `SOL` prefixes;
2. warning → short text (`Corrections stale` → `Corr. stale`); sticky until the width changes, so the live region
   does not flip;
3. drop the low-battery token;
4. drop the elevation (value **and** label together — height references are never abbreviated);
5. drop the coordinates;
6. drop the source name.

**Never dropped:** solution, horizontal accuracy, the warning, the chevron. On a 360–411 dp phone in portrait the
toolbar typically shows source · solution · H · warning · chevron; on the SM-X940 (924 / 1480 dp) everything fits.

### No-fix states

| Toolbar | When |
|---------|------|
| `Connecting…` | external link connecting |
| `Reconnecting…` | selection core: link dropped, retrying |
| `Switching…` | another receiver is being activated (the previous receiver's fix is never shown, and its last position is forgotten) |
| `Not connected` | connect failed / external selected but not active |
| `No data 12s` | connected, NMEA silent |
| `No fix` | data flowing, no position (or device GNSS acquiring) |
| `Location off` | device GNSS without permission / location setting |
| `Fix (lost 12s)` | link lost after a fix: last position kept **greyed** for `STALE_KEEP_MS` (60 s, unchanged), no accuracy, no warnings; spoken as "…not for capture" |

An external fix counts only when `ReceiverConnectionController.liveSource` attributes it to the selected, connected
receiver (`LiveSourceKind.EXTERNAL`); otherwise the toolbar shows the transition instead. Unattributed fixes are not
remembered for the lost-fix display.

## Correction-health warnings (`CorrectionHealthTracker`)

Existing rules only:

| State | Enter when | Threshold source |
|-------|------------|------------------|
| Corrections aging (amber) | age > **capture limit** | `GnssCaptureSettings.resolvePolicy(external).maxDiffAgeSec` — the age above which capture stops accepting fixes (10 s by default, 5 s for the precise preset, Custom = Settings → Capture) |
| Corrections stale (red) | age > **15 s** | `GnssStatusInterpreter.STALE_CORRECTION_S` — the Home card's long-standing rule |
| Corrections unavailable (red) | no correction age **and** the solution has no differential corrections (Single / No fix / DR), after corrections were seen from this receiver | a receiver never used with corrections is not nagged ("Single" already says it) |

Aging is capped at the stale threshold.

**Hysteresis:** a more severe state is entered **immediately** (problems are never delayed). A less severe state is
entered only when the age is **2 s below** the threshold being left (or an age is reported again, for unavailable)
**continuously for 3 s**. The tracker resets on a source / receiver change or a receiver switch. Device GNSS and
replay never show correction warnings.

**Who owns what:** the toolbar pipeline owns the one `CorrectionHealthTracker` (hysteresis). The GNSS Status report
reads the toolbar's own frames, and Home Field Status consumes the same stabilised state
(`HomeToolbarCorrection`), so all three show the same aging / stale / unavailable warning. Home uses it only when the
frame was computed for the same selected source, receiver (profile + host:port) and live provider; otherwise (for at
most one 250 ms sample after a source or receiver change, or when the toolbar has no live frame) Home falls back to
its raw rule (stale at > 15 s, no hysteresis), so a previous receiver's warning is never applied to a new receiver.

**Recovery:** by design a warning persists for ≥ 3 s after the live age is fine again. While it is held only by the
hysteresis (`CorrectionHealthTracker.recovering`), Home's RTK chip reads "RTK 1.0s · recovering" (caution) next to
the warning, so the live age and the warning never read as a contradiction. The report keeps the live age row.

## Shared interpretation (`GnssStatusInterpreter`)

One pure model used by the toolbar, the report **and** Home Field Status (`HomeFieldStatusMapper` rows 8–16 now
delegate to it, and Home takes the toolbar's stabilised correction health for the same live fix stream):

- **Quality:** High accuracy (RTK Fix, reported hAcc ≤ 0.05 m) · RTK Fixed — accuracy unverified · Accuracy below
  target · RTK Float · Low accuracy (> 1 m reported) · Position available.
- **Warnings, in precedence order:** Corrections stale · Corrections unavailable · Position reference unconfirmed
  (may be compensated: red; unconfirmed: amber) · Corrections aging · Vertical accuracy invalid.
- Any warning outranks a good quality; only GOOD with no warning is green.

## GNSS Status report (`GnssStatusSheet`)

Material 3 bottom sheet, expanded, max 640 dp wide (two columns from 840 dp). **Open:** tap anywhere on the toolbar
(source, solution, accuracy, warning, battery, elevation, empty space) or the chevron; in AR (which hides the global
toolbar) tap the AR GNSS chip; in the capture dialog tap "GNSS status". **Dismiss:** swipe down, Back, or tap outside;
the screen underneath is unchanged. There is no swipe-down gesture on the toolbar itself (tap only, so nothing competes
with map, AR or scrolling gestures).

Headline: the toolbar's top warning, otherwise the Home Field Status headline for the same inputs.

| Section | Rows |
|---------|------|
| A Receiver & connection | source, receiver name · model (only when the live fix is from it; otherwise "Selected — not the live source yet"), connection (from the live source / transition), battery & charging, firmware, replay notice |
| B GNSS solution | status (+ other warnings), solution (stale marked "not for capture"), horizontal accuracy with provenance (reported / estimated, 68 %), vertical accuracy (reported 1σ / invalid marker / not reported), satellites used / visible, HDOP · VDOP · PDOP |
| C Corrections | health (with the threshold that applies), exact live age, correction link (receiving / channel), base station id + baseline, the limits in use |
| D Position | last-known marking when stale, what the toolbar shows, lat/lon, grid + zone (outside-zone warning), reference system summary, coordinate epoch, elevation + vertical reference (or why unavailable), units |
| E Receiver-specific | RS4 / RS4 Pro: tilt state, position reference (pole tip / antenna / unconfirmed) with its explanation, tilt warning, compensated NMEA output |
| F Advanced diagnostics (collapsed) | NMEA lines/s (DiagnosticsService), lines processed, parse errors (total + last minute), active provider, receiver address, link, profile + origin, fix time, fix provider |

Missing values read **Unavailable** / **Not reported**; a real 0 is shown as 0.

Rows update **in place** (keyed by section + label): scroll position, the expanded Advanced section and TalkBack focus
survive live updates; views are rebuilt only when rows appear or disappear.

**Copy status** copies a readable snapshot: device timestamp, headline, warnings and every row (including the collapsed
diagnostics) — receiver identity, solution, accuracy provenance, correction status, coordinates, reference system,
coordinate epoch, elevation. The receiver's network address is never included.

## Interactions

| Gesture | Effect |
|---------|--------|
| Tap toolbar / chevron | open the report |
| Tap coordinates (grid system configured) | toggle LL ↔ NE in the toolbar (unchanged; Settings untouched) |
| Tap coordinates (no grid) | open the report |
| Long-press coordinates | copy the shown position (unchanged) |
| Long-press a token | tooltip help |
| Report → "Show lat/lon / grid in toolbar" | the same toggle, clearly labelled |
| Report → "Receiver settings" | Settings → Receiver (what the battery / solution taps used to do) |

## Accessibility

Each token has a spoken description; the accuracy token speaks H only. The warning token is a **polite live
region** whose text changes only when the warning itself changes, so TalkBack announces a warning when it appears or
escalates — not on every 250 ms render, and not when it clears. Tokens have 48 dp touch targets and a "double-tap to
open GNSS status report" action label (coordinates: "switch between latitude/longitude and grid coordinates" when
available). Report rows speak "warning, …" / "caution, …" and show a ⚠ mark, so nothing is colour-only. The strip
uses the same navy in light and dark themes; the warning pills use red/white (5.9:1) and amber/near-black.

## Tests and previews

Pure: `GnssStatusInterpreterTest`, `CorrectionHealthTrackerTest`, `ToolbarInterpretationTest`,
`ToolbarPipelineStatusTest`, `GnssStatusReportPresenterTest`, plus the existing toolbar and Home tests. Robolectric:
`ToolbarFitTest` (priority at 360 / 411 / 924 / 1480 dp, grouping, stable widths, a11y, taps), `GnssStatusSheetTest`.
Previews: `GnssToolbarPreviewTest` writes `app/build/previews/gnss_toolbar_*` and `gnss_report_*` (light / dark).
