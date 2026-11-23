# Coordinate-system data generators

These scripts produce checked-in data for :geokit-core (`io.github.pennstatecs.geokit`) including the bundled GEOID18 grid. They need Python 3 with
`pyproj` (PROJ 9.8.1 / pyproj 3.8.0 were used). Run them from the repo root.

| Output | Command |
|---|---|
| `libs/geokit-core/src/main/kotlin/io/github/pennstatecs/geokit/projection/SpcsZoneTable.kt` and `libs/geokit-core/src/test/resources/coords/spcs83_vectors.csv` | `python libs/geokit-core/tools/coords/gen_spcs.py libs/geokit-core/src/main/kotlin/io/github/pennstatecs/geokit/projection/SpcsZoneTable.kt libs/geokit-core/src/test/resources/coords/spcs83_vectors.csv` |
| `libs/geokit-core/src/main/resources/geoid/geoid18.srgt` | `python libs/geokit-core/tools/coords/pack_geoid.py libs/geokit-core/src/main/resources/geoid/geoid18.srgt g2018u0.bin g2018p0.bin` |
| `libs/geokit-core/src/test/resources/geoid/geoid18_vectors.csv` | `python libs/geokit-core/tools/coords/geoid_vectors.py libs/geokit-core/src/test/resources/geoid/geoid18_vectors.csv g2018u0.bin:500 g2018p0.bin:100` |

The GEOID18 grids are NGS's big-endian files:
`https://geodesy.noaa.gov/PC_PROD/GEOID18/Format_unix/g2018u0.bin` (CONUS) and `.../g2018p0.bin` (PR/VI).
Use Format_unix, not the original little-endian distribution (NGS's 2019 notice about a corrupted
little-endian/ASCII release).

After regenerating, run `.\gradlew.bat :geokit-core:test testDebugUnitTest`: `SpcsZonesTest` (geokit-core) checks every zone against
the PROJ vectors, and `Geoid18GridTest` (geokit-core) checks the geoid against NGS and the raw grids.