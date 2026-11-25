# NSRS 2022 data generators

| Output | Command (repo root; new empty download dir outside the repo) |
|---|---|
| `src/main/resources/crs/spcs2022_catalog.csv`, `src/main/resources/spcs/spcs2022_zones.bin`, `src/test/resources/crs/spcs2022/` (NGS example vectors, PROJ vectors, raw NGS JSON) | `python -I libs/geokit-core/tools/nsrs/gen_spcs2022.py <new empty dir> libs/geokit-core` |

Needs Python 3.12 with pyproj 3.8.0 (PROJ 9.8.1) and shapely 2.2.0. Every download is pinned by SHA-256
(NGS JSON tables; NGS ArcGIS zone-polygon layers); the script stops if NGS changes a table. Output is
deterministic. Sources, versions and limitations: `docs/geodesy/nsrs2022-sources-spcs2022.md`.

After regenerating run `.\gradlew.bat -p libs :geokit-core:test`: `CrsSpcs2022Test` checks the catalog
against the raw NGS tables, GeoKit's projections against every NGS example point (forward + inverse) and
the PROJ vectors, and pins the polygon resource digest.
