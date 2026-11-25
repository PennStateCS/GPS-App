# SPCS2022 sources (Phase 3, WP S)

Checked 2026-11-24. Status of all SPCS2022 data: **NGS beta** ("finalized beta zone designs"), not yet in
the EPSG dataset or PROJ 9.8.1. In GeoKit every zone is at most `ProductStatus.VERIFIED_BETA` (opt-in,
labelled, never a default) and only once its reference frame is reachable by verified transformations.

## Official sources used

| Source | URL | Version / id |
|---|---|---|
| SPCS2022 home (beta) | https://beta.ngs.noaa.gov/SPCS/ | content updated 2026-05-14 |
| Change log | https://beta.ngs.noaa.gov/SPCS/change-log.html | 2023-05-16 alpha; 2025-06-16 initial beta; **2026-05-14 finalized beta designs**; 2026-06-01 rename of 311020 (Nebraska McCook) |
| Zone definitions table | https://beta.ngs.noaa.gov/SPCS/zone-definitions.shtml | "Last Updated 6/1/2026" |
| ↳ machine-readable data | https://beta.ngs.noaa.gov/SPCS/json_data/zoneDefinitions.json | Last-Modified 2026-06-01, sha256 `f222dac6…bc06a` (632,927 B) |
| Example coordinates (one NGS point per zone: lat/lon/h → N/E, k, combined factor, convergence) | https://beta.ngs.noaa.gov/SPCS/coordinates.shtml → `json_data/coordinates.json` | Last-Modified 2026-06-01, sha256 `90ed9bff…7a3` |
| Zone bounding boxes (lat/lon envelope, outward to 0.01°, from the zone polygons) | https://beta.ngs.noaa.gov/SPCS/zone-bounds.shtml → `json_data/zoneBounds.json` | Last-Modified 2026-06-01, sha256 `040f9d5a…b48b66` |
| Zone polygons | NGS ArcGIS Online (owner NGS.GIS_noaa), "Beta SPCS2022 Experience" item `dddb7bc0be6f4e56a1c370c8d529d1a0`, feature layers `Alpha_SPCS2022_{Statewide,Multizone_Complete,Multizone_Partial,Special_Use}_Zones_Feature_Layer_View` (fields ZoneCode, …; last edit 2026-09) | page digests pinned in the generator |
| Design procedures | https://geodesy.noaa.gov/INFO/Policy/files/SPCS2022-Procedures.pdf | NGS 2023-1214-03-A3, effective 2023-07-01 |
| Policy, background | https://geodesy.noaa.gov/INFO/Policy/files/SPCS2022-Policy.pdf; NOAA SP NOS NGS 13 (Dennis 2018) | — |

All JSON files are the data the NGS HTML tables load (`js/zoneDefinitionsJS.js`, `coordinatesJS.js`,
`zoneBounds.js`); no hand transcription. The Gulf special-use layer referenced by the web map
(`Beta_SPCS2022_Special_Use_Gulf_Zones_Feature_Layer_View`) answers "Invalid URL", so the seven offshore
Gulf zones 002101–002107 have no polygon (envelope only). The polygon layers are named "Alpha" but are
current (619 multizone-complete features etc. match the 953-zone tables; every polygon lies inside the
NGS envelope, checked by the generator).

## Content (zoneDefinitions.json, 2026-06-01)

- **953 zones**: 54 statewide, 619 multizone complete, 270 multizone partial, 10 special use;
  800 designed by states, 153 by NGS. Up to three layers per state (zones overlap legally).
- **Frames**: NATRF2022 949, PATRF2022 2 (Hawaii 150001, American Samoa 600001), CATRF2022 1 (Puerto Rico
  and US Virgin Islands 720001), MATRF2022 1 (Guam and Northern Mariana Islands 660001).
- **Methods** (Procedures 6.a, 6.f): TM 423 ("Gauss-Krüger" Transverse Mercator; false northing 0, or
  origin on the equator); LC1 387 (Lambert Conformal Conic, one parallel = latitude of origin, scale on it;
  EPSG 9801); OMC 143 (Hotine Oblique Mercator, centred/local rectified form, "rectified skew
  orthomorphic", skew azimuth at the centre in whole degrees, false origin at the centre → EPSG 9815
  variant B with γc = αc). No LCC 2SP, no other methods.
- **Ellipsoid**: GRS80, a = 6 378 137 m, 1/f = 298.257 222 101 exactly (GeoKit's `Ellipsoid.GRS80`).
- **Units**: parameters defined in metres; feet are **international feet only** (1 ift = 0.3048 m exactly);
  the US survey foot is not supported for SPCS2022 (Federal Register 2020-21902). Most false
  northings/eastings are whole ift; North Carolina 370001's ift values are rounded to 0.001 ift by NGS.
  Origins are given in DMS (some state designs to 0.00001″), scales to 6 (some 10) decimals.
- **Areas of use**: NGS envelopes (lat/lon, outward to 0.01°); polygons from the NGS layers.

## How GeoKit uses them

`libs/geokit-core/tools/nsrs/gen_spcs2022.py` (Python -I, new empty download dir, every download SHA-256
pinned, deterministic) writes `crs/spcs2022_catalog.csv` (CrsCatalogCsv v2: `status=verified_beta`,
aliases = zone abbreviation / type / designer / method), `spcs/spcs2022_zones.bin` (946 zones, simplified to
0.001° ≈ 110 m after NGS's server-side 0.0005° generalisation, islets/holes < 2·10⁻⁵ deg² dropped, split at
180°; 1.7 MB), and test resources `crs/spcs2022/` (NGS example vectors, PROJ vectors, raw NGS JSON gzipped).

Verification (`CrsSpcs2022Test`):
- catalog parameters equal the raw NGS JSON (DMS → degrees, metres, scale, skew, envelopes, method mapping);
- GeoKit forward at all 953 NGS example points: worst 0.50 mm (NGS publishes 1 mm); inverse from the NGS
  E/N: worst 1.1·10⁻⁸ ° (rounding of the NGS E/N);
- PROJ 9.8.1 from the same parameters (independent implementation) reproduced every NGS point within
  0.6 mm in the generator; GeoKit vs PROJ at 4,612 points (NGS point + up to 4 interior points per zone):
  worst 0.6 µm for TM, LC1 and OMC; inverse round trips < 10⁻⁹ °.
- Representative zones explicitly: PA 420001/421001/421003/421004, OH 390001/391025/391048, TX 480001,
  California statewide, AK 020001 (crosses 180°)/021001/021009, HI 150001, AS 600001, PRVI 720001, GUMP 660001.

**Beta NCAT** (https://beta.ngs.noaa.gov/NCAT/): its web UI lists all 953 SPCS2022 zones and
"NATRF2022 epoch 2020.00", but its REST API (`https://beta.ngs.noaa.gov/api/ncat/llh`) rejects every
NATRF2022 spelling tried (`NATRF2022`, `natrf2022`, `NATRF2022 epoch 2020.00`, `NATRF2022(2020.00)`:
"Invalid inputDatum"/"Invalid outputDatum") and SPCS2022 zone codes ("Invalid SPC zone"), checked
2026-11-24 with 8 requests (2 of them NAD83(2011) controls, which work). The NGS-published example coordinates (computed by NGS, one per zone) are
therefore the NGS reference; no NCAT responses are stored.

## Status rules in GeoKit

- `verified_by` is filled for all 953 zones (NGS example point reproduced by PROJ at generation and by
  GeoKit in the tests).
- A zone is operational only if its frame is reachable from "WGS84 (ITRF2014)" through verified
  transformations. Today NATRF2022 has none (WP F), and PATRF2022/CATRF2022/MATRF2022 are not yet
  `ReferenceFrame`s: the catalog accepts these three ids (`CrsCatalogCsv.PENDING_FRAME_IDS`) and loads the
  four zones with `frame = null`, non-operational, reason "frame … is not defined in GeoKit yet". So the
  shipped registry has **0 operational SPCS2022 zones**; each lookup is `Unsupported` with its reason.
- When WP F makes a frame reachable, its zones become operational with status `VERIFIED_BETA`; `search` and
  `atPosition` return them only with `includeBeta = true`. If WP F adds a status to transformations, the
  registry should take the weaker of zone and path status (follow-up after merge).
- Zone location (`CrsRegistry.locateSpcs2022`): polygons with a 250 m margin, envelopes for zones without
  polygon or where no polygon matches (offshore, dropped islets); always all candidates, flagged ambiguous.

## Limitations

- Beta definitions: NGS may still change zones (a parameter change gives a new zone code); regenerate after
  each NGS change-log entry (pins make the generator stop on any change).
- Polygons are a generalised GIS rendering of the legal zone extents (±~150 m), not legal descriptions.
  Gulf zones 002101–002107 have envelopes only.
- No EPSG codes yet (column empty, ids `spcs2022:<zone code>`).
- Feet: only metres are native; display in international feet is the caller's job (never US survey feet).

## Update 2026-11-25 (Phase 4 WP N)

Re-checked: the change log still ends at 6/1/2026, and `zoneDefinitions.json`, `coordinates.json` and
`zoneBounds.json` are byte-identical to the pinned SHA-256 (Last-Modified 2026-06-01). No newer version,
nothing to regenerate. With `CrsRegistry(frameRegistry = FrameTransformationRegistry.NSRS2022_BETA)` the 949
NATRF2022 zones become operational `VERIFIED_BETA`. The default registry still has none (see
`phase4-ngs-investigation.md`).

## Update (Phase 4 WP X)

PATRF2022, CATRF2022 and MATRF2022 are `ReferenceFrame`s with beta edges in `NSRS2022_BETA`, so the four zones on them
(150001, 600001, 720001, 660001) parse like the others and, with `NSRS2022_BETA`, all **953** zones are operational
`VERIFIED_BETA`. `CrsCatalogCsv.PENDING_FRAME_IDS` no longer affects parsing. The app offers SPCS2022 output with the
NSRS 2022 beta preview ON (`phase4-ngs-investigation.md` section E); the default registry still has 0 zones.
