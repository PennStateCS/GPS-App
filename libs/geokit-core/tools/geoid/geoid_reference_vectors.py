"""
Reference geoid undulations from PROJ for GlobalGeoidModelTest (EGM2008 2.5' and EGM96 15').

usage:
  python -I geoid_reference_vectors.py <dir with us_nga_egm08_25.tif + us_nga_egm96_15.tif> <out.csv>

N comes from PROJ itself (pyproj 3.8.0 / PROJ 9.8.1, network disabled) with the pipeline
  unitconvert(deg->rad) | vgridshift +grids=<file> +multiplier=1 | unitconvert(rad->deg)
on (lon, lat, h=0): with multiplier 1 the output height is the grid value N. The points are fixed
(seeded RNG + hand-picked tile corners/edges/nodes, the antimeridian, near-pole, PR/VI, AK, HI, Guam).

Columns: lat,lon,egm2008_m,egm96_m,egm2008_tile,label where egm2008_tile says where the library finds
the EGM2008 tile: "bundled" (main-resource US packs), "test" (egm2008_testtiles.srgp) or "none"
(TILE_NOT_AVAILABLE expected).
"""
import math
import os
import random
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))  # python -I does not add the script dir
import geoid_common as gc  # noqa: E402

import pyproj  # noqa: E402
from pyproj import Transformer  # noqa: E402

grid_dir, out_csv = sys.argv[1], sys.argv[2]
for f, sha in gc.KNOWN_SHA256.items():
    assert gc.sha256_of(os.path.join(grid_dir, f)) == sha, f
pyproj.network.set_network_enabled(False)
pyproj.datadir.append_data_dir(grid_dir)
print("pyproj", pyproj.__version__, "PROJ", pyproj.proj_version_str)


def pipeline(grid):
    return Transformer.from_pipeline(
        "+proj=pipeline +step +proj=unitconvert +xy_in=deg +xy_out=rad "
        f"+step +proj=vgridshift +grids={grid} +multiplier=1 "
        "+step +proj=unitconvert +xy_in=rad +xy_out=deg")


t08, t96 = pipeline("us_nga_egm08_25.tif"), pipeline("us_nga_egm96_15.tif")
bundled = set().union(*(gc.boxes_to_tiles(b) for b in gc.EGM2008_REGIONS.values()))
test_tiles = gc.boxes_to_tiles(gc.EGM2008_TEST_TILES)


def tile_of(lat, lon):
    lon_n = (lon + 180.0) % 360.0 - 180.0
    row = min(int(math.floor(lat + 90.0)), 179)
    return row, int(math.floor(lon_n + 180.0))


rng = random.Random(20261124)
pts = []


def add(label, lat, lon):
    pts.append((label, round(lat, 9), round(lon, 9)))


def rand_box(label, count, s, n, w, e):
    for _ in range(count):
        lon = rng.uniform(w, e if e > w else e + 360.0)
        add(label, rng.uniform(s, n), lon - 360.0 if lon >= 180.0 else lon)


rand_box("conus", 60, 24.0, 50.0, -125.0, -66.0)
rand_box("alaska", 25, 51.0, 72.0, -170.0, -129.0)
rand_box("aleutians", 12, 51.0, 55.0, 172.0, -170.0)
rand_box("hawaii", 15, 18.0, 23.0, -161.0, -154.0)
rand_box("prvi", 10, 17.0, 19.0, -68.0, -64.0)
rand_box("guam", 6, 13.0, 21.0, 144.0, 146.0)
for lat, lon in [(40, -77), (41, -76), (24, -82), (49, -95), (33, -117), (61, -150), (52, 179), (21, -158), (18, -66)]:
    add("corner", lat, lon)
    for dl, dn in [(1e-9, 1e-9), (-1e-9, 1e-9), (1e-9, -1e-9), (0.5, 0.0), (0.0, 0.5), (1 / 48, 1 / 48)]:
        add("edge", lat + dl, lon + dn)
for lat, lon in [(40 + 1 / 24, -77 + 5 / 24), (40 + 23 / 24, -77 + 23 / 24), (45 + 12 / 24, -100 + 1 / 24)]:
    add("node", lat, lon)
for lat in (51.5, 52.0, 52.25, 53.7):
    for lon in (180.0, -180.0, 179.99, -179.99, 179.97, 179.958333333, -179.958333333, 179.999999):
        add("antimeridian", lat, lon)
for lat in (89.0, 89.5, 89.99, 90.0):
    for lon in (179.5, -179.5, 180.0, 0.5, 179.98):
        add("north-pole", lat, lon)
for lat in (-89.0, -89.5, -89.99, -90.0):
    for lon in (-0.5, 0.5, 0.0):
        add("south-pole", lat, lon)
for lat, lon in [(0, 0), (0.3, 0.7), (-0.6, -0.2), (0.999, -0.001), (-1.0, -1.0), (0.5, 0.0)]:
    add("greenwich", lat, lon)
for lon in (179.2, 179.99, 180.0, -179.99, -179.5):
    add("antimeridian-south", -44.5, lon)
rand_box("world", 40, -89.9, 89.9, -180.0, 180.0)

rows = []
for label, lat, lon in pts:
    _, _, n08 = t08.transform(lon, lat, 0.0)
    _, _, n96 = t96.transform(lon, lat, 0.0)
    assert math.isfinite(n08) and math.isfinite(n96) and abs(n08) < 200 and abs(n96) < 200, (lat, lon)
    tile = tile_of(lat, lon)
    where = "bundled" if tile in bundled else "test" if tile in test_tiles else "none"
    rows.append(f"{lat!r},{lon!r},{n08:.6f},{n96:.6f},{where},{label}")

with open(out_csv, "w", encoding="utf-8", newline="\n") as f:
    f.write("lat,lon,egm2008_m,egm96_m,egm2008_tile,label\n")
    f.write("\n".join(rows) + "\n")
print("wrote", out_csv, len(rows), "points")
