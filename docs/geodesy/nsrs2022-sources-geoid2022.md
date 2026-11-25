# NSRS 2022 sources — GEOID2022 (WP G)

Checked 2026-11-24. Status in geokit: `ProductStatus.VERIFIED_BETA` (`Geoid2022Model`, `GeoidModelId.GEOID2022`).

## Official sources

| What | URL |
|---|---|
| NAPGD2022 home (frame, ellipsoid, tide system) | https://beta.ngs.noaa.gov/NAPGD2022/index.html |
| Downloads (GEOID2022 grids, extents) | https://beta.ngs.noaa.gov/NAPGD2022/download.html#geoid2022 |
| Developer guide (file formats, interpolation, time dependency) | https://beta.ngs.noaa.gov/NAPGD2022/developer-guide.html |
| Change log (version) | https://beta.ngs.noaa.gov/NAPGD2022/change-log.html |
| NGS test values | https://beta.ngs.noaa.gov/NAPGD2022/files/NAPGD2022TestCases.beta_v0a.csv |
| Beta NCAT | https://beta.ngs.noaa.gov/NCAT/ (no API: "API functions will be added after internal testing is complete") |

## What NGS states

- **Version / status:** `beta_v0a`, 2026-04-27 (change log; files dated 2026-05-11). Beta (pre-release).
  The test file: values "should only be used for testing purposes and not as geodetic control nor the actual
  NAPGD2022 value". Earlier `beta_v0` (2025-06-16). `beta_v0a` changed the recommended interpolation "from
  biquadratic to bicubic" (the GGXF metadata quoted in the developer guide still says `biquadratic`; the
  guide text and the change log say bicubic, and NGS's test values match bicubic — see below).
- **Frame / ellipsoid / tide system:** "The geoid heights, which are in the tide-free system, are with respect
  to the reference ellipsoid of the Geodetic Reference System 1980 (GRS80) in the ITRF2020 geometric
  reference frame." The test file gives its inputs as "Latitude_decdeg: ITRF2020, Longitude_decdeg: ITRF2020,
  Ellipsoid_ht_m: ITRF2020". → `horizontalFrameId = "itrf2020"` (not NAD83(2011), not NATRF2022).
- **Resolution / grids:** SGEOID2022 (static N, metres, at epoch 2020.0) 1′ in three regions; DGEOID2022
  (dN/dt, m/yr) 15′ global. Extents (download page): North America "NA" 0–90 °N, 170–350 °E; American Samoa
  "AS" −22–0 °N, 172–193 °E; Guam & CNMI "GC" 5–22 °N, 130–170 °E; global "GL" −90–90 °N, 0–360 °E. No
  separate PR/USVI or Hawaii grid: both are in NA (as are Alaska and Midway).
- **Formats:** GGXF (NetCDF-4, all grids, 757 778 288 B), `.bin` and `.b` per grid. We use `.bin`.
- **Interpolation:** "NAPGD2022 grids are to be interpolated in latitude and longitude using local 4x4
  bicubic interpolation as described in Numerical Recipes (Press, et al., 2007). Interpolation within 1 pixel
  of the edge should also use bicubic interpolation with linear extrapolation for padding the missing parts
  of the 4x4 window. Users should not reproject coordinates prior to interpolation." (Uncertainty grids and
  DEMs: bilinear; not used here.)
- **Time dependency:** `GEOID = SGEOID + DGEOID*(mjdn-58849)/365.25`, `mjdn` the integer MJD of the date
  (NGS gives the integer algorithm; MJD 58849 = 2020-01-01).
- **No-data:** not mentioned; the `.bin` grids contain none (0 NaN, all |N| < 72 m).

## Files used (downloaded 2026-11-24; NGS publishes no checksums — SHA-256 computed on download)

| File | Bytes | SHA-256 |
|---|---|---|
| `SGEOID2022.NA.N.beta_v0a.bin` | 233 344 848 | `7b1d43bc6c65b05a07335272dc11de8231c7b31f1975060d1eae4f078bf72386` |
| `SGEOID2022.AS.N.beta_v0a.bin` | 6 663 168 | `89c5c6878451ef396281718e24ff05d37ae86065a8c4fbe985f10e34fbacb66a` |
| `SGEOID2022.GC.N.beta_v0a.bin` | 9 805 728 | `6b336c3ef76f325dd593b953b498779549ae624fa105819e7c16fdb950743599` |
| `DGEOID2022.GL.Ndot.beta_v0a.bin` | 4 155 888 | `735c2c29b07cacd9308a1c9790a3410d28d837d7ae3ca43c79218dd651f33afb` |
| `NAPGD2022TestCases.beta_v0a.csv` ("Last Updated: 20260423") | 10 393 | `d475435f9223fc5c336326c50f80f73dca8cc6d0aff826379a881e51438e68b3` |

`.bin` layout (verified): little-endian header float64 south, west (0–360), dlat, dlon; int32 nlat, nlon,
ikind = 1; then float32 rows south→north. Node-registered: NA 5401 × 10801, AS 1321 × 1261, GC 1021 × 2401,
GL 721 × 1441 (lon 0 and 360 both present). Precision: AS is stored in whole mm; NA and GC are not
(only 0.2 % of CONUS nodes are whole mm), so packing to integer mm is a real ≤ 0.5 mm rounding.

## Implementation (geokit-core `geoid/`)

- `Geoid2022Model`: `undulation(lat, lon)` = static SGEOID2022 at 2020.0; `velocity(lat, lon)` = DGEOID2022;
  `undulationAt(lat, lon, y, m, d)` = NGS formula above (unavailable if either grid is — never the static
  value instead). Label "GEOID2022 (beta)", vertical datum "NAPGD2022", `dataVersion` names `beta_v0a`,
  `referenceEpoch = 2020.0`, `interpolation = BICUBIC`.
- **Interpolation:** Numerical Recipes bicubic patch with centred-difference corner derivatives, evaluated as
  the tensor product of Catmull-Rom cubics over the 4×4 nodes around the cell; nodes beyond an official grid
  edge are linearly extrapolated (2·edge − inner). This choice was made by testing candidates against all 87
  NGS test rows on the raw grids: Catmull-Rom/NR bicubic max 0.048 mm (= NGS's 0.1 mm rounding), 4×4
  Lagrange 2.9 mm, 3×3 biquadratic 4.7 mm, bilinear 17.1 mm.
- No coordinate transformation inside the model; region chosen geometrically (NA, then GC, then AS; they
  only share edges) and never substituted.
- **Packs** (SRGP v2 = v1 + grid region, quantity, units-per-value; N in integer mm, dN/dt in integer µm/yr),
  1° tiles of the official grids: CONUS 24–50 °N 125–66 °W (3 193 628 B), Alaska incl. Aleutians 51–72 °N
  172 °E–129 °W (2 379 964 B), Hawaii 18–23 °N 161–154 °W (75 008 B), PR/USVI 17–19 °N 68–64 °W (20 148 B),
  Guam/CNMI 13–21 °N 144–146 °E (37 824 B), American Samoa 15–11 °S 172–168 °W (33 076 B), DGEOID 5° tiles
  around them (42 487 B): **5 782 135 B** total. 0.1 mm storage was measured at ≈ 11.4 MB for the six
  SGEOID packs and rejected. Everything else covered by NGS is `TILE_NOT_AVAILABLE` (installable as extra
  SRGP v2 packs); outside the three grids `OUTSIDE_COVERAGE`. Points within 1′ inside a pack's outer
  boundary are `TILE_NOT_AVAILABLE` (the 4×4 window needs the neighbouring tile); points exactly on it
  are available.
- Generators: `libs/geokit-core/tools/geoid/pack_geoid2022.py`, `geoid2022_reference_vectors.py`,
  `geoid2022_common.py` (Python 3.12.0, numpy 2.5.5, zlib 1.2.13).

## Validation

- **NGS values** (`NAPGD2022TestCases.beta_v0a.csv`; SGEOID to 0.1 mm, DGEOID to 0.01 mm/yr): 72 of the 87
  rows (63 distinct points: CONUS/Canada/Mexico border cities, Alaska incl. Denali / Mount Logan / bench
  marks, San Juan and PR bench mark, Mauna Kea, Guam, Pago Pago and AS bench mark) fall in the bundled packs.
  Kotlin worst |ΔN| 0.46 mm (tolerance 0.85 mm = storage bound 0.79 mm + NGS rounding 0.05 mm); worst
  DGEOID 0.0054 mm/yr. The other 15 rows (Mexico City, Greenland, Hudson Bay, Midway, …) return
  `TILE_NOT_AVAILABLE`. NGS publishes no values at tile edges/corners and beta NCAT has no API; edges and
  corners are covered by the independent check below. (The raw-grid Python interpolation reproduces all 87
  NGS rows to ≤ 0.048 mm and DGEOID to ≤ 0.005 mm/yr.)
- **Independent interpolation** (Python, raw `.bin`, separate code path) at 256 points (random, nodes, tile
  corners, tile edges, windows spanning four tiles, pack boundaries, antimeridian, official grid edges and
  corners via test-only packs): Kotlin equals the same interpolation over mm-rounded nodes to < 1 µm, and is
  within 0.50 mm of the raw float grid (bound 0.79 mm: 0.5 mm × the bicubic weights' absolute sum 1.5625);
  237 DGEOID rates within 1e-12 m/yr (µm nodes) / 0.8 µm/yr (raw).
- GEOID18 / EGM2008 / EGM96 code paths and data unchanged; their tests pass unchanged (one persistable-token
  list assertion was extended by `GEOID2022`).

## Limitations

- Beta: NGS may change values and method before release; re-download, re-pack and re-validate per version.
- Validation against NGS is limited to NGS's published test points (no NGS API); tile-edge behaviour is
  validated only against our independent Python implementation.
- Interpolation on the NA/GC and NA/AS shared edges: NGS does not say which grid wins; we use NA.
- Behaviour at the edges of official grids (linear-extrapolation padding) follows the developer-guide text;
  no NGS test value lies within 1′ of a grid edge.
- Uncertainty (`Nsigma`) and geoid–quasigeoid separation grids are not bundled.
- Positions must be ITRF2020 (current epoch is fine for N at the sub-mm level); the app must transform first.

## Update 2026-11-25 (Phase 4 WP N)

Re-checked: the download page lists only `beta_v0a`, and the change log is unchanged (4/27/2026 `beta_v0a`,
6/16/2025 `beta_v0`). The pinned `.bin` files keep their sizes and Last-Modified 2026-05-11, and a `v1a` URL
returns 404. OPUS sample reports mention "GEOID2022v1.0.0", but NGS publishes no such file. `beta_v0a`
stays current and nothing changes.
