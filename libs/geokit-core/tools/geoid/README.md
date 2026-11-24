# Global geoid data generators (EGM2008, EGM96)

Produce the bundled EGM2008/EGM96 tile packs and the PROJ reference vectors of :geokit-core.

## Source grids

| File | URL | SHA-256 | Size |
|---|---|---|---|
| `us_nga_egm08_25.tif` | https://cdn.proj.org/us_nga_egm08_25.tif | `4191d471eefebf24091b56dbc604353cb3b8cf8cc70e448bb9ae56a272bef17a` | 80 585 622 B |
| `us_nga_egm96_15.tif` | https://cdn.proj.org/us_nga_egm96_15.tif | `db493027562c9b004d7220fa881f5603adada4e1c5029b933fa7de4547b0e78d` | 2 710 815 B |

The digests equal the `sha256sum` entries of https://cdn.proj.org/files.geojson (checked 2026-11-24); the
generators refuse other files with these names. Licence, as stated in PROJ-data's `us_nga_README.txt`
(https://cdn.proj.org/us_nga_README.txt): both "*License*: Public Domain", source NGA; EGM96 is a
"15 minute worldwide geoid undulation grid that transforms physical heights to WGS84 ellipsoidal heights";
EGM2008 is the "2.5 minute worldwide geoid undulation grid", "produced by GeographicLib using the EGM2008
gravity model".

Grid layout (checked by `geoid_common.load_grid`): float32, `GTRasterTypeGeoKey = PixelIsPoint`
(node-registered), tie point (−180, +90), rows +90 → −90 inclusive (pole rows constant), columns −180 →
180 − step (the 180° column is not repeated; longitude wraps). PROJ treats the grids as global and wraps
across the antimeridian; the library does the same.

## Tooling

Python 3.12.0 (zlib 1.2.13) with `numpy 2.5.5`, `tifffile 2026.11.16` (+ `imagecodecs` for the DEFLATE/predictor
decoding) and, for the vectors, `pyproj 3.8.0` (PROJ 9.8.1). Download the grids into a new, empty directory
and run the scripts with `python -I` from the repository root:

```
python -I libs/geokit-core/tools/geoid/pack_geoid_tiles.py egm96 <dir>/us_nga_egm96_15.tif libs/geokit-core/src/main/resources/geoid
python -I libs/geokit-core/tools/geoid/pack_geoid_tiles.py egm2008 <dir>/us_nga_egm08_25.tif libs/geokit-core/src/main/resources/geoid libs/geokit-core/src/test/resources/geoid
python -I libs/geokit-core/tools/geoid/geoid_reference_vectors.py <dir> libs/geokit-core/src/test/resources/geoid/global_geoid_vectors.csv
```

The packer prints each pack's SHA-256; after regenerating, update `GlobalGeoidModels.EGM2008_BUNDLED_PACKS` /
`EGM96_SHA256` (and `TEST_PACK_SHA256` in `GlobalGeoidModelTest`) and run
`.\gradlew.bat -p libs :geokit-core:test`.

## Outputs (2026-11-24)

| Resource | Tiles | Bytes |
|---|---|---|
| `egm96_global.srgp` (5° tiles, whole globe) | 2 592 | 1 715 960 |
| `egm2008_conus.srgp` (24–50 °N, 125–66 °W) | 1 534 | 995 368 |
| `egm2008_alaska.srgp` (51–72 °N, 172 °E–129 °W) | 1 239 | 684 957 |
| `egm2008_hawaii.srgp` (18–23 °N, 161–154 °W) | 35 | 26 218 |
| `egm2008_prvi.srgp` (17–19 °N, 68–64 °W) | 8 | 6 612 |
| `egm2008_guam_cnmi.srgp` (13–21 °N, 144–146 °E) | 16 | 12 681 |
| test: `egm2008_testtiles.srgp` (poles, Greenwich/equator, 45 °S × 180°) | 11 | 4 559 |

EGM2008 US packs total 1 725 836 B; with EGM96, 3 441 796 B.

## SRGP v1 format

Documented in `pack_geoid_tiles.py`: header (magic, version, nodes/degree, tile degrees, model id, source
file, source SHA-256), a sorted tile index, and per-tile zlib blobs of integer millimetres with a planar
predictor (same scheme as the GEOID18 SRGT pack). Tiles store both edges, so neighbouring tiles share
identical edge nodes and a bilinear cell never spans two tiles. Storage rounding ≤ 0.5 mm.

## Reference vectors

`geoid_reference_vectors.py` evaluates PROJ's `vgridshift +multiplier=1` (network disabled, local grids) at
309 fixed points: seeded random points in CONUS, Alaska, the Aleutians, Hawaii, PR/VI and Guam, tile
corners/edges/nodes, the antimeridian (±180, 179.99…), near both poles, Greenwich/equator and the world
(EGM96 only for points without an EGM2008 tile). NGA's own EGM2008 test values were not used: the PROJ-data
README contains none and they were not found in a search (2026-11-24).
