# Phase 2 implementation plan (frames, epochs, geoid models, CRS registry)

Branch `survey/phase2` from `survey/phase1` @ b036ced. Builds on docs/geodesy/reference-frames-roadmap.md.

## Two-datum assumptions found (audit)

| Where | Assumption |
|---|---|
| geokit-core `convert/ConversionTypes.kt` `GeodeticDatum` | Exactly `WGS84_ITRF2014` and `NAD83_2011` |
| geokit-core `datum/DatumTransform.kt` | One transformation (ITRF2014 ⇄ NAD83(2011) Helmert); "WGS84" = ITRF2014 |
| geokit-core `convert/CoordinateConverter.kt` | Source is WGS84 or NAD83; output datum picks one of the two; State Plane always via NAD83; GEOID18 always via NAD83 |
| geokit-gnss `datum/FixDatum.kt` | Receiver feed is `ITRF2014` or `NAD83_2011` (off-limits: unchanged) |
| app `settings/model/CoordinateSystemSettings.kt` | `OutputDatum` {WGS84 (ITRF2014), NAD83(2011)}; `ReceiverDatum` {ITRF2014, NAD83_2011}; `VerticalReference` {ELLIPSOIDAL, NAVD88_GEOID18, RECEIVER_MSL} |
| app `coords/CoordinateSystemService.kt` | Stored points are EPSG 4979 (WGS84) or 6319 (NAD83); `StoredDatum.toWgs84` |
| app Room `CoordinateEntity` | Frame only via `crsEpsg`; epoch only via observation `timestamp`; no height-model metadata |
| geokit-core `projection/SpcsZones.kt` | One SPCS generation (SPCS83 on NAD83(2011)) |
| geokit-core `geoid/Geoid18.kt` | The only geoid model; called directly by the converter |

## Verified availability (PROJ 9.8.1 / pyproj 3.8.0, checked 2026-11-24)

- ITRF2014 → ITRF2020: EPSG 14-parameter Helmert, position-vector convention, t0 = 2015.0
  (T = 1.4, 0.9, −1.4 mm; D = 0.42 ppb; rates 0, 0.1, −0.2 mm/yr; accuracy 1 mm). **Implement + PROJ vectors.**
- NATRF2022 (EPSG 10966/10967/10968) is defined, but PROJ's only ITRF2020/NAD83 → NATRF2022 operations are
  "ballpark" no-ops. **Frame defined; conversion unavailable** (no identity, no placeholder).
- SPCS2022: no zones in PROJ/EPSG beyond the offshore "NATRF2022 / Gulf" CRSs. **Registry ready; catalog
  unsupported** until NGS/EPSG publish verifiable definitions.
- EGM2008 / EGM96 grids: NGA via PROJ-data (`us_nga_egm08_25.tif`, `us_nga_egm96_15.tif`), public domain.

## Work packages

| WP | Scope | Module | Depends on |
|---|---|---|---|
| A | Reference-frame model (`ReferenceFrame` with stable ids incl. ITRF2014/2020, WGS84 realisations, NAD83(2011), NATRF2022), coordinate-epoch type, transformation registry (source, target, epoch, availability), ITRF2014⇄ITRF2020 Helmert, existing NAD83 Helmert registered unchanged, converter routed through the registry with bit-identical legacy outputs | geokit-core | — |
| C | `GeoidModel` interface + metadata; GEOID18 adapter (numerically unchanged); EGM96 15′ bundled (labelled fallback); EGM2008 2.5′ in 1° tiles: bundled US-region packs, LRU cache with limits, explicit "tile unavailable"; generator from NGA/PROJ-data grids; PROJ reference vectors | geokit-core | — |
| B | Room v13 → v14: nullable `sourceFrame`, `coordinateEpoch`, `heightSource`, `orthometricModel` (NULL = unknown/legacy, no rewriting); capture fills them going forward; backup JSON v-next reads old backups; migration test from the real v13 schema | app | — |
| D | CRS / projection registry over existing UTM + SPCS83 (stable ids = existing EPSG codes, settings keys unchanged), catalog loader for future SPCS2022 (unsupported entries never operational) | geokit-core | A (frame ids) |
| E | Wire A/C/D into the app: output frame ITRF2020 option, vertical options H (EGM2008) / H (EGM96, fallback), labels + provenance in display/CSV, settings (existing keys/defaults preserved), Map/AR/stakeout path untouched | app | A, C, B, D |

Then: independent review, full sequential verification, consolidated Phase 1+2 tablet checklist.

## Invariants

- Existing outputs for existing settings stay bit-identical (converter golden, PROJ/NCAT vectors).
- NMEA h = H + N from the same GGA; never two geoid corrections on one height.
- No identity transformation between WGS84/ITRF2020 and NATRF2022; unverified systems are not selectable.
- Historical rows are never reinterpreted; unknown metadata stays NULL.
- GNSS feed, NMEA fusion, antenna geometry, AR/Maps WGS84 path unchanged.

## WP E (app wiring) notes

- Settings (appended values; keys, old tokens and defaults unchanged; unknown tokens fall back to the old
  defaults): Datum `itrf2020` "ITRF2020" (Geographic/UTM; the 2020 frame at each position's coordinate
  epoch: stored `coordinateEpoch`, else the capture time; live fixes at fix time; State Plane stays
  NAD83(2011)); Height `egm2008` "Orthometric height H (EGM2008)" / "H EGM2008" and `egm96`
  "Orthometric height H (EGM96, fallback)" / "H EGM96". No receiver-frame options were added.
- EGM heights are computed by the app adapter (`coords/CoordinateConverter`) as H = h − N(model), with h
  and the lookup position = the source in WGS84 (ITRF2014), i.e. the stored WGS84-as-ITRF2014 numbers as
  they are (NAD83(2011) sources transformed at their epoch). One subtraction per height; GEOID18 path and
  receiver H passthrough unchanged. A missing EGM2008 tile is "outside EGM2008 coverage bundled in this
  app (US regions)", never EGM96. One model instance per process (`coords/AppGeoidModels`), opened on
  Dispatchers.IO when the option is selected.
- Display: ITRF2020 positions are labelled "ITRF2020 epoch 2026.886" (system summary, CSV System column,
  copy text, detail Lat/Lon row). Point details show a Provenance block (source frame, coordinate epoch,
  receiver frame, orthometric height source and model; NULL → "unknown").
- Zone picker: `CrsRegistry.search` over the operational SPCS83 catalog; "SPCS2022: not yet available."
- `CoordinateConverterGoldenTest` now iterates the pre-Phase-2 enum values explicitly (same order); the
  golden resource is unchanged.

## Review fixes (survey/phase2-fixes)

- EGM lookups never load on the main thread: `AppGeoidModels.model()` returns a `NonBlockingGeoidModel`.
  On the UI thread, before the packs are loaded, the height reads "EGM2008 data is loading" (geokit
  `GeoidUnavailableReason.DATA_LOADING`) and one background load starts on Dispatchers.IO; off the main
  thread lookups load synchronously (exports, tests). Same idea as the GEOID18 grid, which reads as
  unavailable until its background load finishes.
- A failed geoid load is retried after a 30 s back-off (geokit `LazyLoad`); the app's warm-up marks a
  model opened only when its data loaded.
- Points exactly on the outer south/west edge of a missing EGM tile (CONUS 50 °N, Alaska 72 °N, the
  east-edge longitudes) are read from the bundled neighbour that stores the same edge node.
- Stored `coordinateEpoch` must be a finite decimal year in 1980..2100: backup import stores anything else
  as unknown, and conversions fall back to the capture time.
- Points stored in ITRF2020 (`crsEpsg` 9989/9990, imports only — captures never store it) are converted
  from ITRF2020 through the verified registry at their coordinate epoch, never read as WGS84. Maps/AR
  plot them as they are, like WGS84 (ITRF2020 − ITRF2014 is a few millimetres).

## Known limitations

- `receiverFrame` (provenance) comes from the settings snapshot taken at capture start. Changing the
  External receiver datum during an averaging capture records the old frame. Provenance only: the
  numbers are unaffected, because the feed normalises every fix as it arrives.
- Device-GNSS (internal) captures use the legacy `wgs84-as-itrf2014` convention: the device's WGS84
  output is treated as ITRF2014. Its accuracy is metre level, far beyond any WGS84/ITRF difference.
- EGM2008 is interpolated bilinearly on the 2.5′ grid (as PROJ `vgridshift`). NGA's reference software
  uses spline interpolation; the difference is about 1–2 cm in steep terrain, well below 1 cm elsewhere.
- American Samoa is not in the bundled EGM2008 tiles (US regions bundled: CONUS, Alaska, Hawaii, PR/USVI,
  Guam/CNMI); its heights read "outside EGM2008 coverage bundled in this app" (EGM96 remains selectable
  as the labelled fallback).
