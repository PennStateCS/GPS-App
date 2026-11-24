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
