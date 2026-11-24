# Coordinate-system data generators

These scripts produce checked-in data for :geokit-core (`io.github.pennstatecs.geokit`) including the bundled GEOID18 grid. They need Python 3 with
`pyproj` (PROJ 9.8.1 / pyproj 3.8.0 were used). Run them from the repo root.

| Output | Command |
|---|---|
| `libs/geokit-core/src/main/kotlin/io/github/pennstatecs/geokit/projection/SpcsZoneTable.kt` and `libs/geokit-core/src/test/resources/coords/spcs83_vectors.csv` | `python libs/geokit-core/tools/coords/gen_spcs.py libs/geokit-core/src/main/kotlin/io/github/pennstatecs/geokit/projection/SpcsZoneTable.kt libs/geokit-core/src/test/resources/coords/spcs83_vectors.csv` |
| `libs/geokit-core/src/main/resources/spcs/spcs83_zones.bin` and `libs/geokit-core/tools/coords/spcs83_county_zones.csv` (zone suggestion; also needs shapely 2.2.0) | `python -I libs/geokit-core/tools/coords/gen_spcs_boundaries.py cb_2023_us_county_500k.zip libs/geokit-core/src/main/resources/spcs/spcs83_zones.bin libs/geokit-core/tools/coords/spcs83_county_zones.csv` |
| `libs/geokit-core/src/main/resources/geoid/geoid18.srgt` | `python libs/geokit-core/tools/coords/pack_geoid.py libs/geokit-core/src/main/resources/geoid/geoid18.srgt g2018u0.bin g2018p0.bin` |
| `libs/geokit-core/src/test/resources/geoid/geoid18_vectors.csv` | `python libs/geokit-core/tools/coords/geoid_vectors.py libs/geokit-core/src/test/resources/geoid/geoid18_vectors.csv g2018u0.bin:500 g2018p0.bin:100` |

The GEOID18 grids are NGS's big-endian files:
`https://geodesy.noaa.gov/PC_PROD/GEOID18/Format_unix/g2018u0.bin` (CONUS) and `.../g2018p0.bin` (PR/VI).
Use Format_unix, not the original little-endian distribution (NGS's 2019 notice about a corrupted
little-endian/ASCII release).

The county file is the Census cartographic boundary file
`https://www2.census.gov/geo/tiger/GENZ2023/shp/cb_2023_us_county_500k.zip` (1:500,000, public domain; the expected
SHA-256 is in the generator). Zones are simplified to 0.0005° (~50 m). The 1:20M file is still accepted for comparison only:
it departs from the 1:500k lines by up to several km along coasts and rivers. The output is byte-for-byte deterministic; `SpcsZoneLocatorTest` pins its digest.

After regenerating, run `.\gradlew.bat :geokit-core:test testDebugUnitTest`: `SpcsZonesTest` (geokit-core) checks every zone against
the PROJ vectors, `SpcsZoneLocatorTest` checks zone suggestions, and `Geoid18GridTest` (geokit-core) checks the geoid against NGS and the raw grids.