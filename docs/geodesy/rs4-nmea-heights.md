# Reach RS4 NMEA: receiver orthometric heights, invalid vertical accuracy, fixtures

Status: Phase 4 stabilization, work package B (2026-11-25). Companion to [rs4-pro-tilt.md](rs4-pro-tilt.md)
(tilt, ETC) and [phase4-position-references.md](phase4-position-references.md). The app's rule
**h = H + N from the same GGA** (one geoid term, never two) is unchanged.

## Sources (all retrieved 2026-11-25; no page shows a last-updated date)

| # | Source |
|---|---|
| E1 | RS4 NMEA format: https://docs.emlid.com/reachrs4/specifications/nmea-format/ |
| E2 | RS4 changelog: https://docs.emlid.com/reachrs4/changelogs/ |
| E3 | ArcGIS Field Maps (Android): https://docs.emlid.com/reachrs4/integration/arcgis-field-maps-android/ |
| E4 | TcpGPS: https://docs.emlid.com/reachrs4/integration/tcpgps/ |
| E5 | ETC developer document linked from E1: https://files.emlid.com/docs/ETC_rev.3_RS4.pdf (read in WP T, 2026-11-24; its text layer could not be extracted again here) |

No Emlid Flow 15.16 changelog was found on E2.

## Findings

"Documented" = an Emlid page says it (quoted). "Not documented → unknown, needs receiver test" otherwise.

| Question | Answer |
|---|---|
| What 34.10 adds | **Documented** (E2, "Reach Firmware 34.10 (Nov 20, 2026)"): "Added support for orthometric heights in NMEA output, allowing ArcGIS Field Maps and other third-party applications that cannot apply a geoid model to receive mean sea level elevations directly." E3: "Now, Reach RS4 / RS4 Pro outputs NMEA with the correct orthometric height, which will be included in the NMEA GGA." |
| GGA field 9 with the setting on | **Documented in substance**: the orthometric height of the selected geoid model is "included in the NMEA GGA" (E3); E3: "To provide mean sea level elevation, Emlid Flow applies the selected geoid model to GNSS height measurements." Which field is not stated; field 9 is the only GGA height field. |
| GGA field 9 with the setting off | **Not documented → unknown.** Our captures (before 34.10) carry H with N = −33.1 m in field 11, i.e. H of some receiver-internal geoid model. |
| GGA field 11 (geoid separation) with the setting on | **Not documented → unknown, needs receiver test.** It could be the selected model's N (H + N = h holds), the old internal model's N (h off by the model difference, likely decimetres to ~1 m), 0, or empty. |
| Is the selected geoid model exposed to external apps? | **Not documented.** GGA has no model field, and E1/E3 mention none. The app labels receiver H "receiver geoid (model unknown)" and never infers NAVD88 or NAPGD2022. |
| Does H + separation (same GGA) reconstruct h? | **Not documented as a formula.** E3 describes the geoid only conceptually ("defines the difference between the ellipsoid and the geoid at a given location"). It holds in all our captures (pre-34.10). For 34.10 with the setting on: unknown, needs receiver test. |
| How invalid heights are indicated | **Documented, without the field**: E3 (caution box): "If the app is force-closed or geoid correction updates are interrupted for more than 10 seconds, the altitude accuracy is marked as invalid (999m)." No sentence or field is named. The most likely carrier is GST field 8 (altitude σ), which Field Maps reads, but that is **not documented → needs receiver test**. Whether GGA field 9 still holds an orthometric (stale) height, falls back to ellipsoidal, or empties during the interruption is **not documented**. |
| Does the receiver need Emlid Flow running? | **Documented** in substance: the 999 m note says "If the app is force-closed …", and E3: "When connected to your Reach via Wi-Fi, Emlid Flow will prompt you to connect to Reach over Bluetooth to use orthometric height." The geoid corrections appear to be supplied by the Emlid Flow app over Bluetooth. |
| Bluetooth vs TCP | **Documented for Bluetooth only**: E3 sets it under "Position streaming 1" → "Bluetooth": "Tap _Orthometric height_ and turn on the toggle to use vertical datum." E2 says "in NMEA output" with no transport. Whether the TCP position output (this app's interface) offers the toggle, and behaves identically, is **not documented → needs receiver test**. Bluetooth documentation is not taken as proof for TCP. |
| Does 34.10 change our GGA parsing assumptions? | **No parser change is justified.** The GGA layout is unchanged; field 9/11 meanings may change (above), which is handled by assessment, not parsing. |
| 34.7 GGA/RMC fix | **Documented** (E2, 34.7, Aug 31 2026): "Fixed an issue where the NMEA GGA and RMC messages reported different positions when tilt compensation was enabled, improving compatibility with third-party applications." No code change: the app takes positions from GGA only (RMC supplies date, speed, course), and the WP T/WP C rules already require firmware ≥ 34.7 for a compensated pole-tip classification. |
| Default message rates | **Documented** (E1): "By default, Reach RS4 / RS4 Pro outputs all the messages with the GN talker ID at the following frequencies": GGA 5 Hz, GSA 1 Hz, GST 1 Hz, GSV 1 Hz, RMC 1 Hz, VTG 1 Hz, ZDA 1 Hz, EBP 1 Hz, ETC 5 Hz; "…change the frequency for all of them except for the ETC message." E4 (TcpGPS) recommends "GGA, GSA, GST, EBP, and ETC messages with a 5Hz frequency and the GSV message with a 0.5Hz frequency." |
| EBP | **Documented** (E1): `$GNEBP`, latitude, N/S, longitude, E/W, "Altitude above ellipsoid in meters", M. Our captures show a constant position (the base), as the parser already assumes; never used for the rover. |

## What the app does

### Heights stay distinguishable (unchanged, verified)

- Receiver H: `VerticalReference.RECEIVER_MSL` "Orthometric height H (receiver geoid, model unknown)",
  `ElevationDetail.RECEIVER_MODEL` "receiver geoid (model unknown)", `orthometricModel` stored null.
- h: H + N from the same GGA (fuser), shown as "h (ellipsoidal)".
- App-computed GEOID18 / EGM / GEOID2022 (beta) H: from h only, H = h − N(model). Receiver H is never fed to
  an app geoid (`ReceiverHeightNoDoubleCorrectionTest`), so a receiver streaming orthometric H cannot get a
  second geoid correction as long as field 11 is consistent with field 9.

### New: geokit-gnss `ReceiverHeights` / `VerticalAccuracyValidity` (pure, no parsing change)

- `VerticalAccuracyValidity.classify`: vertical accuracy (`Fix.vAccM`, `Fix.stdDevUpM`) ≥ 500 m, non-finite or
  ≤ 0 → INVALID (Emlid's marker is 999 m; 500 m leaves margin for similar sentinels and is far above any real
  1σ). Null → NOT_REPORTED (not invalid).
- `ReceiverHeights.assess(fix)`: receiver H, N, model (always null = unknown), h and a status:
  RECONSTRUCTED, NO_ALTITUDE, NO_SEPARATION (h null, as the fuser already does), SEPARATION_ZERO_AMBIGUOUS,
  VERTICAL_ACCURACY_INVALID.

### New: capture height gate (app `ui/capture/CaptureHeightGate`, one hook in `CaptureViewModel`)

- Epochs with an INVALID vertical accuracy are **not averaged**. The averaging is 3-D (ECEF), so the vertical
  cannot be dropped while keeping the horizontal without changing `ObservationSession`; excluding the epoch is
  the smallest safe rule. A point can therefore never be saved with a 999 m-marked height or vertical accuracy.
- Recovery is automatic: the first epoch with a valid vertical accuracy is averaged again. One diagnostics line
  is written when the interruption starts and one when it ends. If the interruption outlasts the capture, the
  capture times out the usual way (no accepted fix / requirements not met).
- With the fuser's GST rules a single 999 m GST affects its own epoch and the 5 Hz epochs it is carried to
  (at most one GST period), and the next valid GST clears it.
- SEPARATION_ZERO_AMBIGUOUS epochs are averaged as before but logged once per occupation as "ellipsoidal height
  uncertain" (field 9 may be ellipsoidal with N = 0, which is right, or orthometric with N omitted, which would be
  wrong by tens of metres in the US). Blocking them would break receivers that stream h in field 9.

### Undecidable from NMEA (needs receiver tests)

- Field 11 under 34.10 orthometric streaming: if it stays the old internal model's N while field 9 is the
  selected model's H, h = H + N is off by the model difference, and nothing in the stream reveals it. A receiver
  test (setting off vs on, same mark, compare H + N) settles it.
- What GGA field 9 holds during a 999 m interruption.
- Which sentence/field carries the 999 m (GST field 8 assumed).
- TCP vs Bluetooth behaviour of the setting.

## Fixtures

| Kind | Where | What |
|---|---|---|
| **Official** | `libs/geokit-receiver-emlid/src/test/resources/nmea/official/` (README with URLs, dates) | E1's six ETC examples and EBP example verbatim (valid checksums); E5's ETC examples (wrong checksums except `*5A`). Test: `EmlidOfficialFixturesTest`. The parser agrees with the official spec — no parser change. |
| **Synthetic** | `libs/geokit-receiver-emlid/src/test/resources/nmea/synthetic/SYNTHETIC_*.nmea`, generator `libs/geokit-receiver-emlid/tools/synthetic/gen_mixed_rs4.py` | GGA 5 Hz + ETC 5 Hz + RMC 1 Hz + GST 1 Hz: nominal state sequence; faults (dropped / out-of-order / late-duplicate ETC, bad ETC and GGA checksums, dropped GGA, 3 s ETC gap → stale → recovery, missing RMC, alignment relapse). Test: `EmlidSyntheticMixedStreamTest`. Synthetic GGA/GST height cases: `ReceiverHeightsTest` (geokit-gnss). |
| **Captured** | `libs/geokit-gnss/src/test/resources/nmea/reach_nmea_*.txt` | Real RS4 / RS2+ output (pre-34.10). Goldens unchanged. |

## Diagnostics export: opt-in NMEA sample (investigated, not implemented)

Today `ReportRedactor` removes every NMEA sentence and NMEA-style coordinate from the report as the last line of
defence, and `NmeaDebugSampler` only traces in debug builds. A short opt-in sample is feasible but not "clean"
enough for this package:

- It needs a bounded ring buffer of raw lines fed from the live line hook (`DiagnosticsService.recordLine`), a
  checkbox in the export UI (off by default, reset after each export), and a dedicated report entry that is
  **exempt** from the NMEA rule while still passing every other redaction rule — the exemption is the risky part.
- Rules if built: last ≤ 60 s only; sentence allow-list (GGA, RMC, GST, GSA, GSV, ZDA, VTG, ETC, EBP); never
  RTCM, HTTP, NTRIP or socket.io text; latitude/longitude truncated to 2 decimal minutes unless the user also
  ticks "exact coordinates"; EBP (base position) always truncated; checksums recomputed or the lines marked
  "coordinates truncated"; a header stating the user authorised it; redaction tests that hosts, credentials and
  lines older than 60 s never appear.
- Until then, a receiver test recording should be taken with Emlid Flow's own logging or a TCP client.

## Receiver test plan (RS4 / RS4 Pro, firmware ≥ 34.10, Emlid Flow ≥ 15.16, known mark)

1. Orthometric height off, then on (Bluetooth and, if offered, TCP): record GGA + GST 60 s each. Compare field 9,
   field 11 and field 9 + field 11 between the two runs (h must not change).
2. With it on, force-close Emlid Flow for > 10 s: which field shows 999 m, what does field 9 hold, how fast does it
   recover after Emlid Flow returns?
3. Check whether the TCP output follows the Bluetooth setting.
