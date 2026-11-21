# Coordinate-system data generators

These scripts produce checked-in data for :geo-core (`app.surrealar.geo`) and the app's GEOID18 asset. They need Python 3 with
`pyproj` (PROJ 9.8.1 / pyproj 3.8.0 were used). Run them from the repo root.

| Output | Command |
|---|---|
| `geo-core/src/main/kotlin/app/surrealar/geo/projection/SpcsZoneTable.kt` and `geo-core/src/test/resources/coords/spcs83_vectors.csv` | `python geo-core/tools/coords/gen_spcs.py geo-core/src/main/kotlin/app/surrealar/geo/projection/SpcsZoneTable.kt geo-core/src/test/resources/coords/spcs83_vectors.csv` |
| `app/src/main/assets/geoid/geoid18.srgt` | `python geo-core/tools/coords/pack_geoid.py app/src/main/assets/geoid/geoid18.srgt g2018u0.bin g2018p0.bin` |
| `app/src/test/resources/coords/geoid18_vectors.csv` | `python geo-core/tools/coords/geoid_vectors.py app/src/test/resources/coords/geoid18_vectors.csv g2018u0.bin:500 g2018p0.bin:100` |

The GEOID18 grids are NGS's big-endian files:
`https://geodesy.noaa.gov/PC_PROD/GEOID18/Format_unix/g2018u0.bin` (CONUS) and `.../g2018p0.bin` (PR/VI).
Use Format_unix, not the original little-endian distribution (NGS's 2019 notice about a corrupted
little-endian/ASCII release).

After regenerating, run `.\gradlew.bat :geo-core:test testDebugUnitTest`: `SpcsZonesTest` (geo-core) checks every zone against
the PROJ vectors, and `CoordinateSystemReferenceTest` checks the geoid against NGS and the raw grids.